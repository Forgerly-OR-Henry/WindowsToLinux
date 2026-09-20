application_lock_directory() {
  require_app "$1"
  local directory="$base_root/application-locks/$1"
  assert_storage_parent "$directory"; install -d -o root -g root -m 700 -- "$directory"
  assert_root_owned_directory "$directory"; printf '%s' "$directory"
}
application_maintenance() {
  local directory deadline=$((SECONDS + 30))
  directory="$(application_lock_directory "$1")"
  exec {application_admission_fd}>"$directory/admission"
  flock -w 30 -x "$application_admission_fd" || reject application-busy
  exec {application_run_fd}>"$directory/run"
  flock -w "$((deadline > SECONDS ? deadline - SECONDS : 0))" -x "$application_run_fd" || reject application-busy
  application_assert_no_orphan "$directory"
}
application_admit_run() {
  local directory
  directory="$(application_lock_directory "$1")"
  exec {application_admission_fd}>"$directory/admission"
  flock -n -x "$application_admission_fd" || reject application-maintenance-in-progress
  [ "${application_validation:-0}" = 1 ] || [ ! -e "$directory/maintenance" ] || reject application-maintenance-in-progress
  exec {application_run_fd}>"$directory/run"
  flock -n -x "$application_run_fd" || reject application-busy
  application_assert_no_orphan "$directory"
  flock -u "$application_admission_fd"
}
application_assert_inputs() {
  local index=0 source target part current container_user user="$(service_identity_name "${runtime_identity_override:-$1}")" binding target_root
  while [ "$index" -lt "${#application_input_sources[@]}" ]; do
    source="${application_input_sources[$index]}"; target="${application_input_targets[$index]}"
    for current in "$source" "$target"; do
      case "$current/" in /proc/*|/sys/*|/dev/*|/run/credentials/*|/usr/local/lib/windowstolinux/*|/var/lib/windowstolinux/*) reject application-input-control-path ;; esac
    done
    case "$target/" in /bin/*|/sbin/*|/lib/*|/lib64/*|/usr/*|/etc/*|/opt/*|/var/*|/root/*|/run/*|/tmp/*) reject application-input-target-control ;; esac
    [ -e "$source" ] && [ ! -L "$source" ] && { [ -d "$source" ] || [ -f "$source" ]; } || reject application-input-source
    for current in "$source" "$target"; do
      while [ "$current" != / ]; do [ ! -L "$current" ] || reject application-input-symlink; current="${current%/*}"; [ -n "$current" ] || current=/; done
    done
    # No root read, ownership change, ACL change or SELinux relabel is granted by declaring an input.
    local -a reader=(runuser -u "$user" --)
    if [ "${application_backend:-native}" = container ]; then
      container_user="${container_input_user:-$(container_nonroot_user "$container_engine" "$(container_image "$1" "${application_release##*/}")")}"
      reader=(setpriv --reuid="${container_user%%:*}" --regid="${container_user#*:}" --clear-groups --)
    fi
    "${reader[@]}" test -r "$source" || reject application-input-permission
    if [ -d "$source" ]; then "${reader[@]}" test -x "$source" || reject application-input-permission; fi
    for binding in "${managed_data_bindings[@]}"; do
      managed_binding_parts "$binding"; target_root="$(managed_data_binding_root "$binding")"
      case "$target/" in "$target_root/"*) reject application-input-storage-overlap ;; esac
      case "$target_root/" in "$target/"*) reject application-input-storage-overlap ;; esac
    done
    for part in "${application_input_targets[@]}"; do
      [ "$part" = "$target" ] && continue
      case "$target/" in "$part/"*) reject application-input-overlap ;; esac
    done
    index=$((index + 1))
  done
}
application_systemd_escape() {
  local value="$1"
  value="${value//\\/\\\\}"; value="${value//\"/\\\"}"; value="${value//%/%%}"; value="${value//\$/\$\$}"
  printf '"%s"' "$value"
}
application_argv() {
  local kind="$1" root="$2" entry="$3"; shift 3
  application_relative "$entry"
  application_exec=()
  if [ -z "$entry" ]; then
    application_exec=("${application_primary_exec[@]}")
  else
    local path="$root/source/$entry"
    if [ "${application_rendering:-0}" != 1 ]; then assert_root_owned_regular "$path"; fi
    case "$kind" in
      python) application_exec=("$root/source${application_builddir:+/$application_builddir}/.venv/bin/python" "$path") ;;
      node) application_exec=("${toolchain_node:-/usr/bin/node}" "$path") ;;
      php|phpcli) application_exec=("${toolchain_php:-/usr/bin/php}" "$path") ;;
      ruby|rubycli) application_exec=("${toolchain_ruby:-/usr/bin/ruby}" "$path") ;;
      dotnet) application_exec=("${toolchain_dotnet:-/usr/bin/dotnet}" "$path") ;;
      java|javasource|springboot|kotlin) [[ "$entry" = *.jar ]] || reject application-java-entry; application_exec=("$toolchain_java" -jar "$path") ;;
      go|rust|cmake) [ "${application_rendering:-0}" = 1 ] || [ -x "$path" ] || reject application-entry-not-executable; application_exec=("$path") ;;
      *) reject application-entry-kind ;;
    esac
  fi
  [ "${#application_exec[@]}" -gt 0 ] || reject application-entry-required
  application_exec+=("$@")
}
render_application_command() {
  local kind="$1" root="$2" item
  application_primary_command="$command"
  if [ -n "$application_entry" ]; then
    # File existence is checked after the candidate is sealed and on each invocation.
    case "$kind" in
      python) command="$(application_systemd_escape "$root/current/source${application_builddir:+/$application_builddir}/.venv/bin/python")" ;;
      node) command="${toolchain_node:-/usr/bin/node}" ;;
      php|phpcli) command="${toolchain_php:-/usr/bin/php}" ;;
      ruby|rubycli) command="${toolchain_ruby:-/usr/bin/ruby}" ;;
      dotnet) command="${toolchain_dotnet:-/usr/bin/dotnet}" ;;
      java|javasource|springboot|kotlin) [[ "$application_entry" = *.jar ]] || reject application-java-entry; command="$toolchain_java -jar" ;;
      go|rust|cmake) command= ;;
      *) reject application-entry-kind ;;
    esac
    command="${command:+$command }$(application_systemd_escape "$root/current/source/$application_entry")"
  fi
  for item in "${application_args[@]}"; do command="$command $(application_systemd_escape "$item")"; done
  if [ -n "${application_worker_ids[*]:-}" ]; then render_application_workers "$kind" "$root"; fi
}

render_application_workers() {
  local kind="$1" root="$2" index offset=0 count encoded program application_rendering=1
  [ "$application_mode" = DAEMON ] || reject application-workers-require-daemon
  local -a arguments=()
  application_argv "$kind" "$root/current" "$application_entry" "${application_args[@]}"
  arguments+=("${#application_exec[@]}" "${application_exec[@]}")
  for index in "${!application_worker_ids[@]}"; do
    count="${application_worker_counts[$index]}"
    application_argv "$kind" "$root/current" "${application_worker_entries[$index]}" "${application_worker_arguments[@]:offset:count}"
    arguments+=("${#application_exec[@]}" "${application_exec[@]}"); offset=$((offset + count))
  done
  encoded="$(python3 -I - "${arguments[@]}" <<'PY'
import base64,json,sys
fields=sys.argv[1:]; commands=[]
while fields:
    count=int(fields.pop(0)); commands.append(fields[:count]); del fields[:count]
print(base64.b64encode(json.dumps(commands,ensure_ascii=False).encode()).decode())
PY
)"
  program="$(base64 -w0 <<'WTL_WORKER_SUPERVISOR'
import base64,json,signal,subprocess,sys,time
commands=json.loads(base64.b64decode(sys.argv[2]))
children=[]
stopping=False
status=1
def stop(signum,frame):
    global stopping,status
    stopping=True
    status=128+signum
signal.signal(signal.SIGTERM,stop)
signal.signal(signal.SIGINT,stop)
try:
    for command in commands:
        if stopping: break
        children.append(subprocess.Popen(command))
    while not stopping:
        if any(child.poll() is not None for child in children): break
        time.sleep(0.1)
finally:
    for child in children:
        if child.poll() is None:
            try: child.terminate()
            except ProcessLookupError: pass
    deadline=time.monotonic()+5
    for child in children:
        try: child.wait(timeout=max(0,deadline-time.monotonic()))
        except subprocess.TimeoutExpired: child.kill(); child.wait()
sys.exit(status)
WTL_WORKER_SUPERVISOR
)"
  command="/usr/bin/python3 -I -c $(application_systemd_escape 'import base64,sys;exec(base64.b64decode(sys.argv[1]))') $program $encoded"
}
application_load_current() {
  local app="$1" manifest="$2" root
  require_app "$app"; require_digest "$manifest"
  root="$(app_root "$app")"; application_release="$(readlink -f -- "$root/current")"
  case "$application_release" in "$root/releases/"*) ;; *) reject application-current-path ;; esac
  require_digest "${application_release##*/}"; assert_root_owned_directory "$application_release"
  assert_root_owned_regular "$application_release/.windowstolinux-owner"
  [ "$(cat -- "$application_release/.windowstolinux-owner")" = "$manifest" ] || reject application-owner
  current_application="$app"
  if [ -e "$application_release/.windowstolinux-container-parameters" ]; then
    load_container_parameters "$application_release/.windowstolinux-container-parameters"; application_backend=container
    container_current_release "$app" "$manifest"
  else
    load_deployment_parameters "$application_release/.windowstolinux-deployment-parameters"; application_backend=native
    render_deployment_unit "$app" "${deployment_runtime_parameters[@]}" >/dev/null
    assert_deployment_current_or_empty "$app" "$manifest"
  fi
}
application_job() {
  [ "$#" -ge 3 ] || reject application-run-arguments
  local app="$1" manifest="$2" operation="$3" status=0; shift 3
  [ "${1:-}" = -- ] || reject application-run-argument-separator; shift
  [ "$#" -le 64 ] || reject application-argument-count
  local arg
  for arg in "$@"; do [ "${#arg}" -le 4096 ] && [[ "$arg" != *$'\n'* && "$arg" != *$'\r'* ]] || reject application-argument; done
  application_admit_run "$app"; application_load_current "$app" "$manifest"
  case "$operation" in
    run) [ "$application_mode" = ON_DEMAND ] || reject application-not-on-demand ;;
    client) [ "$application_mode" = DAEMON ] && [ "$application_has_client" = 1 ] || reject application-client-unavailable ;;
    *) reject application-operation ;;
  esac
  application_assert_inputs "$app"
  if [ "$application_backend" = container ]; then run_application_container "$app" "$operation" "$@"; return; fi
  run_application_native "$app" "$operation" "$@"
}

application_assert_no_orphan() {
  local directory="$1" record first second state
  record="$directory/job"; [ ! -e "$record" ] || {
    assert_root_owned_regular "$record"; mapfile -t job < "$record"; first="${job[0]}"
    if [[ "$first" = windowstolinux-job-*.service ]]; then
      application_assert_native_job_stopped "$first"
    else
      case "$first" in docker|podman) ;; *) reject application-job-record ;; esac
      second="${job[1]:-}"; [[ "$second" = windowstolinux-job-* ]] || reject application-job-record
      state="$("$first" inspect --format '{{.State.Running}}' "$second")" || reject application-job-state-unverified
      [ "$state" = false ] || reject application-busy
      "$first" rm "$second" >/dev/null || reject application-job-cleanup-unverified
    fi
    rm -f -- "$record"
  }
}
application_maintenance_control() {
  [ "$#" = 4 ] || reject application-maintenance-arguments
  local action="$1" app="$2" manifest="$3" token="$4" directory
  [[ "$token" =~ ^((backup|restore)-[0-9a-f]{32}|deployment-[0-9a-f]{64})$ ]] || reject application-maintenance-token
  directory="$(application_lock_directory "$app")"
  application_maintenance "$app"
  if [ -e "$directory/maintenance" ]; then
    assert_root_owned_regular "$directory/maintenance"
    [ "$(cat -- "$directory/maintenance")" = "$token" ] || reject application-maintenance-in-progress
  fi
  case "$action" in
    begin)
      if [ -L "$(app_root "$app")/current" ]; then application_load_current "$app" "$manifest"; fi
      printf '%s\n' "$token" > "$directory/maintenance"; chmod 600 -- "$directory/maintenance" ;;
    end) rm -f -- "$directory/maintenance" ;;
    *) reject application-maintenance-action ;;
  esac
  printf 'MAINTENANCE=%s\n' "$action"
}

render_application_bindings() {
  local root="$1"
  local input_index=0
  while [ "$input_index" -lt "${#application_input_sources[@]}" ]; do
    printf 'BindReadOnlyPaths=%s\n' "$(application_systemd_escape "${application_input_sources[$input_index]}:${application_input_targets[$input_index]}")"
    input_index=$((input_index+1))
  done
  input_index=0
  while [ "$input_index" -lt "${#application_companion_ids[@]}" ]; do
    printf 'Environment=%s\n' "$(application_systemd_escape "${application_companion_environments[$input_index]}=$root/current/source/${application_companion_sources[$input_index]}/${application_companion_artifacts[$input_index]}")"
    input_index=$((input_index+1))
  done
}

prepare_application_primary_argv() {
  local root="$1" index argument
  read -r -a application_primary_exec <<< "$command"
  command=
  for index in "${!application_primary_exec[@]}"; do
    argument="${application_primary_exec[$index]}"
    if [ -n "$application_builddir" ]; then argument="${argument//$root\/current\/source/$root\/current\/source\/$application_builddir}"; fi
    application_primary_exec[$index]="$argument"
    command="${command:+$command }$(application_systemd_escape "$argument")"
  done
}

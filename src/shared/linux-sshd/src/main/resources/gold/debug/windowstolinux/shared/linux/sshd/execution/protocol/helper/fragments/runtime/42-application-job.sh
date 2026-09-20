application_native_properties() {
  local app="$1" property index=0 path key source target
  application_properties=()
  while IFS= read -r property; do application_properties+=("--property=$property"); done < <(render_service_runtime_identity "$app")
  path="$application_release/source${application_workdir:+/$application_workdir}"
  assert_root_owned_directory "$path"
  if [ -n "${application_restore_root:-}" ]; then path="$(app_root "$app")/current/source${application_workdir:+/$application_workdir}"; fi
  application_properties+=(--property="WorkingDirectory=$path" --property="EnvironmentFile=$(configuration_path "$app" "$deployment_configuration_digest" systemd)"
    --property="Environment=PATH=$toolchain_path" --property=TasksMax=256 --property=MemoryMax=1G)
  while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
    key="${deployment_secret_names[$index]}"
    source="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
    application_properties+=(--property="LoadCredential=$key:$source" --property="Environment=$key=%d/$key")
    index=$((index + 1))
  done
  index=0
  while [ "$index" -lt "${#application_input_sources[@]}" ]; do
    source="${application_input_sources[$index]}"; target="${application_input_targets[$index]}"
    application_properties+=(--property="BindReadOnlyPaths=$(application_systemd_escape "$source:$target")")
    index=$((index + 1))
  done
  index=0
  while [ "$index" -lt "${#application_companion_ids[@]}" ]; do
    path="$application_release/source/${application_companion_sources[$index]}/${application_companion_artifacts[$index]}"
    assert_root_owned_regular "$path"
    if [ -n "${application_restore_root:-}" ]; then path="$(app_root "$app")/current/source/${application_companion_sources[$index]}/${application_companion_artifacts[$index]}"; fi
    application_properties+=(--property="Environment=$(application_systemd_escape "${application_companion_environments[$index]}=$path")")
    index=$((index + 1))
  done
  if [ -n "${application_restore_root:-}" ]; then
    assert_root_owned_regular "$application_restore_root/storage-unit"
    while IFS= read -r property; do
      case "$property" in BindPaths=*|BindReadOnlyPaths=*) application_properties+=("--property=$property") ;; esac
    done < "$application_restore_root/storage-unit"
  fi
}
application_verification_storage() {
  [ -z "${application_restore_root:-}" ] || return
  local app="$1" spec binding source target owner="$(service_identity_name "$1")"
  application_validation_root="$(mktemp -d "$base_root/application-locks/$app/verify.XXXXXXXX")"
  chmod 755 -- "$application_validation_root"
  for spec in "${managed_data_bindings[@]}"; do
    managed_binding_parts "$spec"; source="$(managed_data_binding_root "$binding")"
    target="$application_validation_root/$binding"; install -d -o "$owner" -g "$owner" -m 700 -- "$target"
    if [ "$storage_kind" = DATABASE ] && [ -f "$source/$database_file" ]; then
      sqlite3 "$source/$database_file" ".backup '$target/$database_file'" || reject application-verification-database
    elif [ "$storage_kind" = CONFIGURATION ]; then
      application_properties+=(--property="BindReadOnlyPaths=$(application_systemd_escape "$(managed_storage_target)")"); continue
    else cp -a --no-preserve=ownership -- "$source/." "$target/"; fi
    chown -R "$owner:$owner" -- "$target"
    application_properties+=(--property="BindPaths=$(application_systemd_escape "$target:$source")")
  done
}
application_job_cleanup() {
  local status="$1"
  trap - EXIT HUP INT TERM
  if [ -n "${application_job_unit:-}" ]; then
    systemctl stop "$application_job_unit" >/dev/null 2>&1 || true
    application_assert_native_job_stopped "$application_job_unit"
  fi
  if [ -n "${application_job_record:-}" ]; then rm -f -- "$application_job_record"; fi
  if [ -n "${application_validation_root:-}" ]; then
    case "$application_validation_root" in "$base_root/application-locks/$current_application/verify."*) rm -rf --one-file-system -- "$application_validation_root" ;; *) exit 64 ;; esac
  fi
  exit "$status"
}
run_application_native() {
  local app="$1" operation="$2" entry status=0 kind="$application_runtime_kind"; shift 2
  if [ -z "${application_restore_root:-}" ]; then
    local primary_index
    for primary_index in "${!application_primary_exec[@]}"; do
      application_primary_exec[$primary_index]="${application_primary_exec[$primary_index]//$(app_root "$app")\/current/$application_release}"
    done
  fi
  local -a arguments
  case "$operation" in
    run) entry="$application_entry"; arguments=("${application_args[@]}" "$@") ;;
    client) entry="$application_client_entry"; arguments=("${application_client_args[@]}" "$@") ;;
    verify) entry="$application_verify_entry"; arguments=("${application_verify_args[@]}") ;;
    probe) entry="$application_health_entry"; arguments=("${application_health_args[@]}") ;;
    *) reject application-operation ;;
  esac
  application_argv "$kind" "$application_release" "$entry" "${arguments[@]}"
  if [ -n "${application_restore_root:-}" ]; then
    local index
    for index in "${!application_exec[@]}"; do application_exec[$index]="${application_exec[$index]//$application_release/$(app_root "$app")/current}"; done
  fi
  local secret
  for secret in "${deployment_secret_names[@]}"; do
    if [[ "$secret" =~ ^WINDOWSTOLINUX_SECRET_DB_[0-9A-F]{12}_ENV_[A-Z0-9_]+_FILE$ ]]; then
      application_exec=(/usr/bin/python3 /usr/local/lib/windowstolinux/runtime-db-env "${application_exec[@]}"); break
    fi
  done
  application_native_properties "$app"
  application_job_unit="windowstolinux-job-$app-$(cat /proc/sys/kernel/random/uuid).service"
  application_job_record="$(application_lock_directory "$app")/job"
  printf '%s\n' "$application_job_unit" > "$application_job_record"
  trap 'application_job_cleanup $?' EXIT
  trap 'application_job_cleanup 129' HUP
  trap 'application_job_cleanup 130' INT
  trap 'application_job_cleanup 143' TERM
  if [ "$operation" = verify ]; then application_verification_storage "$app"; fi
  if [ "$operation" = verify ] || [ "$operation" = probe ]; then
    application_properties+=(--property="RuntimeMaxSec=$application_health_timeout" --property=LimitFSIZE=1048576)
  fi
  local argv_payload
  argv_payload="$(printf '%s\0' "${application_exec[@]}" | base64 -w0)"
  [ "${#argv_payload}" -le 87384 ] || reject application-arguments-total-size
  # Base64 carries literal argv through systemd's dollar/specifier expansion without invoking a shell.
  application_exec=(/usr/bin/python3 -I -c 'import base64,os,sys; a=base64.b64decode(sys.argv[1]).decode("utf-8").split("\0")[:-1]; os.execvpe(a[0],a,os.environ)' "$argv_payload")
  systemd-run --quiet --wait --pipe --collect --property=Slice=system.slice --unit="$application_job_unit" "${application_properties[@]}" -- "${application_exec[@]}" || status=$?
  return "$status"
}

application_assert_native_job_stopped() {
  local unit="$1" state load group="/sys/fs/cgroup/system.slice/$1" processes
  load="$(systemctl show --value --property LoadState "$unit" 2>/dev/null)" || [ "$load" = not-found ] || reject application-job-cleanup-unverified
  case "$load" in
    not-found) ;; # --collect may unload the transient unit before the foreground command returns.
    loaded)
      state="$(systemctl show --value --property MainPID "$unit")" || reject application-job-cleanup-unverified
      [ "$state" = 0 ] || reject application-busy
      state="$(systemctl show --value --property ActiveState "$unit")" || reject application-job-cleanup-unverified
      case "$state" in inactive|failed) ;; *) reject application-busy ;; esac ;;
    *) reject application-job-cleanup-unverified ;;
  esac
  if [ -d "$group" ]; then
    processes="$(find "$group" -name cgroup.procs -exec cat {} +)" || reject application-job-cleanup-unverified
    [ -z "$processes" ] || reject application-busy
  fi
}

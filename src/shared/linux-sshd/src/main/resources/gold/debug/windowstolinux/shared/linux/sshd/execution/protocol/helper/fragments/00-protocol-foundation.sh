#!/usr/bin/env bash
set -euo pipefail
IFS=$' \t\n'
PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
helper_path=/usr/local/lib/windowstolinux/managed-helper
helper_directory=/usr/local/lib/windowstolinux
helper_protocol=4
base_root=/var/lib/windowstolinux
applications_root="$base_root/apps"
work_root="$base_root/work"
snapshots_root="$base_root/snapshots"
configurations_root="$base_root/configurations"
secrets_root="$base_root/secrets"
backups_root="$base_root/backups"
reject() {
  printf 'MANAGED_HELPER_REJECT=%s\n' "$1" >&2
  exit 64
}
require_root() {
  [ "$(id -u)" -eq 0 ] || reject not-root
  [ ! -L "$helper_path" ] || reject helper-symlink
  [ "$(stat -c '%u:%g' -- "$helper_path")" = 0:0 ] || reject helper-owner
  [ "$(stat -c '%u:%g' -- "$helper_directory")" = 0:0 ] || reject helper-directory-owner
  [ -z "$(find -P "$helper_path" -maxdepth 0 -perm /022 -print -quit)" ] || reject helper-writable
  [ -z "$(find -P "$helper_directory" -maxdepth 0 -perm /022 -print -quit)" ] || reject helper-directory-writable
}
require_deployer() {
  deployer="${SUDO_USER:-}"
  [[ "$deployer" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || reject deployer
  id "$deployer" >/dev/null 2>&1 || reject deployer
  deployer_group="$(id -gn "$deployer")"
}
require_app() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]] || reject application-id
}
require_candidate() {
  local app="$1"
  local candidate_id="$2"
  [[ "$candidate_id" =~ ^${app}-[0-9a-f]{16}$ ]] || reject candidate-id
}
require_digest() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]] || reject digest
}
require_secret_identifier() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || reject secret-identifier
}
require_revision() {
  [[ "$1" =~ ^[1-9][0-9]{0,17}$ ]] || reject secret-revision
}
require_snapshot_token() {
  [[ "$1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || reject snapshot-token
}
app_root() {
  printf '%s/apps/%s' "$base_root" "$1"
}
unit_name() {
  printf 'windowstolinux-%s.service' "$1"
}
unit_path() {
  printf '/etc/systemd/system/windowstolinux-%s.service' "$1"
}
candidate_root() {
  printf '%s/%s' "$work_root" "$1"
}
snapshot_root() {
  printf '%s/%s-%s' "$snapshots_root" "$1" "$2"
}
assert_root_owned_directory() {
  local path="$1"
  [ -d "$path" ] || reject controlled-directory
  [ ! -L "$path" ] || reject controlled-directory-symlink
  [ "$(stat -c '%u:%g' -- "$path")" = 0:0 ] || reject controlled-directory-owner
}
assert_root_owned_regular() {
  local path="$1"
  [ -f "$path" ] || reject controlled-file
  [ ! -L "$path" ] || reject controlled-file-symlink
  [ "$(stat -c '%u:%g' -- "$path")" = 0:0 ] || reject controlled-file-owner
}
assert_candidate_for_deployer() {
  local candidate="$1"
  assert_root_owned_directory "$candidate"
  assert_root_owned_regular "$candidate/.deployer"
  [ "$(cat -- "$candidate/.deployer")" = "$deployer" ] || reject candidate-deployer
}
render_unit() {
  local app="$1"
  local root
  root="$(app_root "$app")"
  cat <<UNIT
[Unit]
Description=WindowsToLinux managed $app
After=network.target
[Service]
Type=simple
User=$deployer
WorkingDirectory=$root/current
ExecStart=/usr/local/lib/windowstolinux/java-21 -jar $root/current/app.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
[Install]
WantedBy=multi-user.target
UNIT
}
require_relative_path() {
  [[ "$1" =~ ^[A-Za-z0-9._/-]{1,255}$ ]] || reject relative-path
  [[ "$1" != /* && "$1" != *..* && "$1" != *//* ]] || reject relative-path
}
require_java_main() {
  [[ "$1" =~ ^[A-Za-z_$][A-Za-z0-9_$.]{0,255}$ ]] || reject java-main
}
require_safe_name() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || reject safe-name
}
require_service_port() {
  [[ "$1" =~ ^[0-9]{1,5}$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ] || reject service-port
}
require_safe_argument() {
  [[ "$1" =~ ^[A-Za-z0-9@%_+=:,./-]{1,512}$ ]] || reject runtime-argument
}
require_count() {
  [[ "$1" =~ ^[0-9]{1,2}$ ]] || reject runtime-argument-count
  [ "$1" -le 32 ] || reject runtime-argument-count
}
render_deployment_unit() {
  local app="$1"
  shift
  parse_deployment_inputs "$@"
  set -- "${deployment_remaining_arguments[@]}"
  [ "$#" -ge 1 ] || reject runtime-arguments
  local kind="$1"
  shift
  local root command argument_count argument config index secret name
  root="$(app_root "$app")"
  case "$kind" in
    gradle)
      [ "$#" -eq 0 ] || reject runtime-arguments
      command="/usr/local/lib/windowstolinux/java-21 -jar $root/current/app.jar"
      ;;
    springboot)
      [ "$#" -eq 1 ] || reject runtime-arguments
      case "$1" in GRADLE_WRAPPER|MAVEN_WRAPPER|MAVEN) ;; *) reject springboot-build-tool ;; esac
      command="/usr/local/lib/windowstolinux/java-21 -jar $root/current/app.jar"
      ;;
    java|javasource)
      [ "$#" -ge 4 ] || reject runtime-arguments
      local jar="$1"
      local main="$2"
      shift 2
      require_relative_path "$jar"
      require_java_main "$main"
      if [ "$kind" = javasource ]; then [ "$jar" = .w2l/java/app.jar ] || reject java-source-artifact; fi
      require_count "$1"
      argument_count="$1"
      shift
      [ "$#" -ge "$argument_count" ] || reject runtime-arguments
      command="/usr/local/lib/windowstolinux/java-21"
      while [ "$argument_count" -gt 0 ]; do
        argument="$1"
        require_safe_argument "$argument"
        command="$command $argument"
        shift
        argument_count=$((argument_count - 1))
      done
      require_count "$1"
      argument_count="$1"
      shift
      [ "$#" -eq "$argument_count" ] || reject runtime-arguments
      command="$command -cp $root/current/app.jar $main"
      while [ "$argument_count" -gt 0 ]; do
        argument="$1"
        require_safe_argument "$argument"
        command="$command $argument"
        shift
        argument_count=$((argument_count - 1))
      done
      ;;
    node)
      [ "$#" -eq 2 ] || reject runtime-arguments
      [[ "$1" =~ ^(18|19|20|21|22|23|24)$ ]] || reject node-version
      case "$2" in
        NPM) command="/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin npm --prefix $root/current/source start" ;;
        PNPM) command="/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin pnpm --dir $root/current/source start" ;;
        YARN) command="/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin yarn --cwd $root/current/source start" ;;
        *) reject node-package-manager ;;
      esac
      ;;
    python)
      [ "$#" -eq 3 ] || reject runtime-arguments
      [[ "$1" =~ ^3\.(10|11|12|13)$ ]] || reject python-version
      [[ "$2" =~ ^[A-Za-z_][A-Za-z0-9_.]{0,127}$ ]] || reject python-entrypoint
      case "$3" in PIP_LOCKED|PIPENV_LOCKED|POETRY_LOCKED|UV_LOCKED) ;; *) reject python-build-tool ;; esac
      command="$root/current/source/.venv/bin/python -m $2"
      ;;
    static)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_relative_path "$1"
      [[ "$2" =~ ^[0-9]{1,5}$ ]] && [ "$2" -ge 1 ] && [ "$2" -le 65535 ] || reject static-port
      case "$3" in STATIC_SITE_BUILD|NPM|PNPM|YARN) ;; *) reject static-build-tool ;; esac
      command="/usr/bin/python3 -m http.server $2 --directory $root/current/source/$1"
      ;;
    go|rust|dotnet|kotlin|php|phpcli|ruby|rubycli|cmake)
      render_ecosystem_runtime_command "$kind" "$root" "$@"
      command="$ecosystem_runtime_command_result"
      ;;
    *) reject runtime-kind ;;
  esac
  config="$(configuration_path "$app" "$deployment_configuration_digest" systemd)"
  cat <<UNIT
[Unit]
Description=WindowsToLinux managed $app
After=network.target

[Service]
Type=simple
User=$deployer
WorkingDirectory=$root/current/source
EnvironmentFile=$config
UNIT
  index=0
  while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
    name="${deployment_secret_names[$index]}"
    secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
    printf 'LoadCredential=%s:%s\n' "$name" "$secret"
    printf 'Environment=%s=%%d/%s\n' "$name" "$name"
    index=$((index + 1))
  done
  cat <<UNIT
ExecStart=$command
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
UNIT
}
expected_deployment_unit_digest() {
  render_deployment_unit "$@" | sha256sum | awk '{print $1}'
}
save_deployment_parameters() {
  local target="$1"
  shift
  [ "$#" -ge 1 ] || reject runtime-arguments
  printf '%s\n' "$@" > "$target"
  chown root:root -- "$target"
  chmod 444 -- "$target"
}
load_deployment_parameters() {
  local source="$1"
  assert_root_owned_regular "$source"
  mapfile -t deployment_runtime_parameters < "$source"
  [ "${#deployment_runtime_parameters[@]}" -ge 1 ] || reject runtime-parameters
  render_deployment_unit "$current_application" "${deployment_runtime_parameters[@]}" >/dev/null
}
assert_deployment_current_or_empty() {
  local app="$1"
  local manifest="$2"
  local root unit releases current expected
  current_application="$app"
  root="$(app_root "$app")"
  unit="$(unit_path "$app")"
  releases="$root/releases"
  previous_present=0
  previous_path=
  previous_running=0
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then
    assert_root_owned_directory "$root"
    [ -L "$root/current" ] || reject current-not-link
    current="$(readlink -f -- "$root/current")"
    case "$current" in "$releases"/[0-9a-f]*) ;; *) reject current-path ;; esac
    local current_digest="${current##*/}"
    require_digest "$current_digest"
    [ "$current" = "$releases/$current_digest" ] || reject current-path
    assert_root_owned_directory "$current"
    assert_root_owned_regular "$current/.windowstolinux-owner"
    [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] || reject current-owner
    load_deployment_parameters "$current/.windowstolinux-deployment-parameters"
    expected="$(expected_deployment_unit_digest "$app" "${deployment_runtime_parameters[@]}")"
    [ "$(systemctl show --value --property FragmentPath "$(unit_name "$app")")" = "$unit" ] || reject unit-fragment
    [ -z "$(systemctl show --value --property DropInPaths "$(unit_name "$app")")" ] || reject unit-dropins
    assert_root_owned_regular "$unit"
    [ "$(sha256sum -- "$unit" | awk '{print $1}')" = "$expected" ] || reject unit-digest
    previous_present=1
    previous_path="$current"
    if systemctl is-active --quiet "$(unit_name "$app")"; then previous_running=1; fi
  else
    [ ! -e "$unit" ] && [ ! -L "$unit" ] || reject unmanaged-unit
    ! systemctl cat "$(unit_name "$app")" >/dev/null 2>&1 || reject unmanaged-unit
  fi
}

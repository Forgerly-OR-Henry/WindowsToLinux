# SQL clients receive one private credential and no host-management privilege.
run_mysql_client() {
  local client="$1" defaults="$2" unit
  unit="windowstolinux-database-$(cat /proc/sys/kernel/random/uuid).service"
  shift 2
  case "$client" in /usr/bin/mysql|/usr/bin/mariadb|/usr/bin/mysqldump|/usr/bin/mariadb-dump) ;; *) reject database-client-path ;; esac
  assert_root_owned_regular "$defaults"
  local -a flags
  flags=()
  case "$client" in
    /usr/bin/mysql|/usr/bin/mariadb) flags=(--batch --binary-mode --local-infile=0) ;;
  esac
  systemd-run --quiet --wait --pipe --collect --unit="$unit" \
    --property=DynamicUser=yes --property=NoNewPrivileges=yes --property=CapabilityBoundingSet= \
    --property=ProtectSystem=strict --property=ProtectHome=yes --property=PrivateTmp=yes \
    --property=PrivateDevices=yes --property=RestrictSUIDSGID=yes --property=RestrictNamespaces=yes \
    --property=ProtectControlGroups=yes --property=ProtectKernelTunables=yes \
    --property=KillMode=control-group --property=TimeoutStopSec=5s --property=RuntimeMaxSec=1800 \
    --property=MemoryMax=512M --property=TasksMax=64 --property=UMask=0077 \
    --property="LoadCredential=client:$defaults" --property=WorkingDirectory=/ \
    --property="InaccessiblePaths=-$base_root -/run/systemd -/run/dbus -/run/docker.sock -/run/podman" \
    "$client" "--defaults-file=/run/credentials/$unit/client" "${flags[@]}" "$@"
}
require_database_type() {
  case "$1" in sqlite|postgresql|mysql|mariadb) ;; *) reject database-type ;; esac
}
require_database_name() {
  [[ "$1" =~ ^[A-Za-z_][A-Za-z0-9_.-]{0,127}$ ]] || reject database-name
}
require_database_host() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9.-]{0,252}[a-z0-9]$ ]] || [[ "$1" =~ ^[a-z0-9]$ ]] || reject database-host
}
require_boolean() {
  [ "$1" = 0 ] || [ "$1" = 1 ] || reject boolean
}
require_artifact_id() {
  [[ "$1" =~ ^db-[0-9a-f]{32}$ ]] || reject database-artifact-id
}
require_artifact_size() {
  [[ "$1" =~ ^[1-9][0-9]{0,10}$ ]] || reject database-artifact-size
  [ "$1" -le 4294967296 ] || reject database-artifact-size
}
database_artifact_path() {
  printf '%s/%s' "$backups_root" "$1"
}
database_sqlite_arguments() {
  [ "$#" -eq 5 ] || reject database-sqlite-arguments
  local app="$1"; database_sqlite_binding="$2"; database_sqlite_location_type="$3"; database_sqlite_location_path="$4"; database_sqlite_file="$5"
  require_app "$app"; require_app "$database_sqlite_binding"
  [[ "$database_sqlite_file" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || reject database-sqlite-file
  database_sqlite_directory="$(managed_storage_directory "$app" DATABASE "$database_sqlite_binding" "$database_sqlite_location_type" "$database_sqlite_location_path")"
  assert_storage_parent "$database_sqlite_directory"
  database_sqlite_target="$database_sqlite_directory/$database_sqlite_file"
}
assert_sqlite_file() {
  local target="$1" companion
  [ -f "$target" ] && [ ! -L "$target" ] && [ "$(stat -c %h -- "$target")" = 1 ] || reject database-source-file
  for companion in "$target-wal" "$target-shm" "$target-journal"; do
    if [ -e "$companion" ] || [ -L "$companion" ]; then
      [ -f "$companion" ] && [ ! -L "$companion" ] && [ "$(stat -c %h -- "$companion")" = 1 ] || reject database-companion-file
    fi
  done
}
database_source_path() {
  local app="$1" root spec found=0 expected
  database_sqlite_arguments "$@"; expected="$database_sqlite_target"
  root="$(readlink -f -- "$(app_root "$app")/current")"
  case "$root" in "$(app_root "$app")/releases/"*) ;; *) reject database-application-current ;; esac
  current_application="$app"
  if [ -e "$root/.windowstolinux-container-parameters" ]; then load_container_parameters "$root/.windowstolinux-container-parameters"
  else load_deployment_parameters "$root/.windowstolinux-deployment-parameters"; fi
  for spec in "${managed_data_bindings[@]}"; do
    managed_binding_parts "$spec"
    if [ "$binding" = "$database_sqlite_binding" ] && [ "$storage_kind" = DATABASE ]; then
      [ "$(managed_storage_target)" = "$expected" ] || reject database-binding-differs; found=1
    fi
  done
  [ "$found" = 1 ] || reject database-binding-missing
  assert_sqlite_file "$expected"; printf '%s' "$expected"
}

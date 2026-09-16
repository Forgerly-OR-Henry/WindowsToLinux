render_systemd_manager_isolation() {
  local paths='-/run/dbus -/run/docker.sock -/run/podman -/run/user' path
  if [ -e /sys/fs/selinux/enforce ]; then
    # Hide manager sockets without denying systemd namespace setup access to the directory.
    printf 'TemporaryFileSystem=/run/systemd:ro\n'
    printf 'BindReadOnlyPaths=-/run/systemd/dynamic-uid -/run/systemd/userdb\n'
  else
    paths="-/run/systemd/private -/run/systemd/journal -/run/systemd/notify $paths"
  fi
  for path in "$@"; do paths+=" -$path"; done
  printf 'InaccessiblePaths=%s\n' "$paths"
}
# Persistent state is addressed by application/component, never a recycled UID.
dynamic_state_relative() {
  printf 'windowstolinux/data/%s/%s' "$managed_data_application" "$managed_data_component"
}
render_dynamic_runtime_identity() {
  local app="$1" relative user spec binding logical mode
  relative="${runtime_state_override:-$(dynamic_state_relative)}"
  user="wtlr-$(printf %s "${runtime_identity_override:-$app}" | sha256sum | cut -c1-16)"
  if getent passwd "$user" >/dev/null; then
    getent -s systemd passwd "$user" >/dev/null || reject runtime-identity-collision
  fi
  printf 'DynamicUser=yes\nUser=%s\nStateDirectory=%s\nStateDirectoryMode=0700\n' "$user" "$relative"
  printf 'NoNewPrivileges=yes\nCapabilityBoundingSet=\nProtectSystem=strict\nProtectHome=yes\nPrivateTmp=yes\n'
  printf 'PrivateDevices=yes\nProtectControlGroups=yes\nProtectKernelTunables=yes\nProtectKernelModules=yes\n'
  printf 'RestrictSUIDSGID=yes\nRestrictNamespaces=yes\nUMask=0077\nKillMode=control-group\nTimeoutStopSec=15s\n'
  render_systemd_manager_isolation
  for spec in "${managed_data_bindings[@]}"; do
    binding="${spec%%:*}"; mode="${spec##*:}"
    if [ "$mode" = ro ]; then printf 'ReadOnlyPaths=/var/lib/%s/files/%s\n' "$relative" "$binding"; fi
  done
}
state_link_matches() {
  local public="$1" private="$2" target
  target="$(readlink -- "$public")" || return 1
  [ "$target" = "$private" ] || [ "$target" = "$(realpath -sm --relative-to="${public%/*}" -- "$private")" ]
}
assert_managed_state_mapping() {
  local public="$data_root/$managed_data_application/$managed_data_component" private
  private="/var/lib/private/$(dynamic_state_relative)"
  if [ -L "$public" ]; then
    state_link_matches "$public" "$private" || reject managed-state-mapping
    [ "$(stat -c %u -- "$public")" = 0 ] || reject managed-state-link-owner
    [ -d "$private" ] && [ ! -L "$private" ] || reject managed-state-private-directory
    [ "$(readlink -f -- "$private")" = "$private" ] || reject managed-state-private-path
    [ "$(stat -c %u /var/lib/private)" = 0 ] && [ "$(stat -c %a /var/lib/private)" = 700 ] || reject managed-state-private-boundary
  else
    [ "$(readlink -f -- "$public")" = "$public" ] || reject managed-state-public-path
  fi
}
prepare_dynamic_state() {
  local app="$1" public private parent snapshot token
  public="$data_root/$managed_data_application/$managed_data_component"; private="/var/lib/private/$(dynamic_state_relative)"
  install -d -o root -g root -m 755 -- "$data_root" "$data_root/$managed_data_application"
  install -d -o root -g root -m 700 -- /var/lib/private
  parent="${private%/*}"
  install -d -o root -g root -m 755 -- "$parent"
  if [ -L "$public" ]; then
    assert_managed_state_mapping
    if [ "$(readlink -- "$public")" = "$private" ]; then ln -srfT -- "$private" "$public"; fi
    return
  fi
  if [ -e "$public" ]; then
    assert_managed_state_mapping
    [ "$previous_present" -eq 1 ] || reject managed-state-unowned
    token="$(cat -- "$(app_root "$app")/.migration-snapshot")"; require_snapshot_token "$token"
    snapshot="$(snapshot_root "$app" "$token")"; assert_root_owned_directory "$snapshot"
    [ ! -e "$snapshot/legacy-data" ] && [ ! -e "$private" ] || reject managed-state-migration-conflict
    stop_application_unit "$app"
    printf '%s\n' "$(dynamic_state_relative)" > "$snapshot/state-layout"
    chmod 400 -- "$snapshot/state-layout"
    mv -T -- "$public" "$snapshot/legacy-data"
    cp -a -- "$snapshot/legacy-data" "$private"
  else
    [ ! -e "$private" ] || reject managed-state-private-conflict
    install -d -o root -g root -m 700 -- "$private"
  fi
  ln -srT -- "$private" "$public"
  assert_managed_state_mapping
}
restore_dynamic_state_migration() {
  local snapshot="$1" relative public private
  [ -e "$snapshot/state-layout" ] || return 0
  assert_root_owned_regular "$snapshot/state-layout"
  relative="$(cat -- "$snapshot/state-layout")"
  [[ "$relative" =~ ^windowstolinux/data/[a-z0-9][a-z0-9-]{0,62}/[a-z0-9][a-z0-9-]{0,62}$ ]] || reject migration-state-layout
  public="/var/lib/$relative"; private="/var/lib/private/$relative"
  if [ ! -e "$snapshot/legacy-data" ]; then
    [ -d "$public" ] && [ ! -L "$public" ] && [ ! -e "$private" ] || reject migration-state-backup
    rm -f -- "$snapshot/state-layout"
    return 0
  fi
  [ -d "$snapshot/legacy-data" ] && [ ! -L "$snapshot/legacy-data" ] || reject migration-state-backup
  if [ -L "$public" ]; then
    state_link_matches "$public" "$private" || reject migration-state-link
    rm -f -- "$public"
  fi
  [ ! -e "$public" ] || reject migration-state-public-conflict
  if [ -e "$private" ]; then
    [ -d "$private" ] && [ ! -L "$private" ] && [ "$(readlink -f -- "$private")" = "$private" ] || reject migration-state-private-path
    rm -rf --one-file-system -- "$private"
  fi
  mv -T -- "$snapshot/legacy-data" "$public"
  rm -f -- "$snapshot/state-layout"
}

stop_application_unit() {
  local app="$1" unit state group processes
  unit="$(unit_name "$app")"
  state="$(systemctl show --value --property LoadState "$unit" 2>/dev/null)" || reject runtime-stop-failed
  [ -n "$state" ] || reject stop-incomplete
  [ "$state" != not-found ] || return 0
  systemctl stop "$unit" || reject runtime-stop-failed
  state="$(systemctl show --value --property ActiveState "$unit")"
  case "$state" in inactive|failed) ;; *) reject stop-incomplete ;; esac
  [ "$(systemctl show --value --property MainPID "$unit")" = 0 ] || reject stop-incomplete
  group="$(systemctl show --value --property ControlGroup "$unit")"
  if [ -n "$group" ] && [ -d "/sys/fs/cgroup$group" ]; then
    processes="$(find "/sys/fs/cgroup$group" -name cgroup.procs -exec cat {} +)" || reject stop-incomplete
    [ -z "$processes" ] || reject stop-incomplete
  fi
}

observe_application_unit() {
  local unit="$1" state
  if state="$(systemctl show --property=ActiveState,SubState,Result,ExecMainCode,ExecMainStatus,MainPID "$unit")"; then
    printf 'QUERY_OK=1\n%s\n' "$state"
  else
    printf 'QUERY_OK=0\n'
  fi
}

stop_requested_application_unit() {
  local app="$1" unit state
  unit="$(unit_name "$app")"
  state="$(observe_application_unit "$unit")"
  case "$state" in $'QUERY_OK=1\n'*) ;; *) reject runtime-observation-failed ;; esac
  printf '%s\n' "$state" | sed 's/^/STOP_BEFORE_/'
  stop_application_unit "$app"
  [ "$(systemctl show --value --property LoadState "$unit")" = loaded ] || reject stop-incomplete
  state="$(observe_application_unit "$unit")"
  case "$state" in $'QUERY_OK=1\n'*) ;; *) reject runtime-observation-failed ;; esac
  printf '%s\n' "$state" | sed 's/^/STOP_AFTER_/'
  state="$(systemctl show --value --property ActiveState "$unit")"
  if [ "$state" = failed ]; then
    systemctl reset-failed "$unit" || reject runtime-reset-failed
  fi
  [ "$(systemctl show --value --property ActiveState "$unit")" = inactive ] || reject stop-incomplete
  [ "$(systemctl show --value --property MainPID "$unit")" = 0 ] || reject stop-incomplete
}

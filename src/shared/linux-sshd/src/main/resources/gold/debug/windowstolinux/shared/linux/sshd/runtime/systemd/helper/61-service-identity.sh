# @compat:systemd-isolation@
service_identity_name() { printf 'wtlr-%s' "$(printf %s "$1" | sha256sum | cut -c1-16)"; }
prepare_service_identity() {
  local app="$1" user record shell uid gid line
  require_app "$app"; user="$(service_identity_name "$app")"; record="$base_root/identities/$app"
  assert_storage_parent "$record"; install -d -o root -g root -m 700 -- "$base_root/identities"
  shell="$(command -v nologin)"; [ -n "$shell" ] || reject runtime-nologin-missing
  if [ -e "$record" ]; then
    assert_root_owned_regular "$record"; line="$(cat -- "$record")"
    [ "$line" = "$user:$(id -u "$user"):$(id -g "$user")" ] || reject runtime-identity-changed
    [ "$(getent passwd "$user" | cut -d: -f6-7)" = "/nonexistent:$shell" ] || reject runtime-identity-changed
  else
    ! getent passwd "$user" >/dev/null && ! getent group "$user" >/dev/null || reject runtime-identity-collision
    useradd --system --no-create-home --home-dir /nonexistent --shell "$shell" --user-group "$user" || reject runtime-identity-create
    uid="$(id -u "$user")"; gid="$(id -g "$user")"
    [ "$uid" -ne 0 ] && [ "$gid" -ne 0 ] || reject runtime-root-identity
    printf '%s:%s:%s\n' "$user" "$uid" "$gid" > "$record"; chmod 400 -- "$record"
  fi
  [ "$(id -G "$user")" = "$(id -g "$user")" ] || reject runtime-supplementary-groups
}
render_service_runtime_identity() {
  local app="$1" user spec target
  user="$(service_identity_name "${runtime_identity_override:-$app}")"
  printf 'User=%s\nGroup=%s\n' "$user" "$user"
  printf 'NoNewPrivileges=yes\nCapabilityBoundingSet=\nProtectSystem=strict\nProtectHome=yes\nPrivateTmp=yes\n'
  printf 'PrivateDevices=yes\nProtectControlGroups=yes\nProtectKernelTunables=yes\nProtectKernelModules=yes\n'
  printf 'RestrictSUIDSGID=yes\nRestrictNamespaces=yes\nUMask=0077\nKillMode=control-group\nTimeoutStopSec=15s\n'
  render_systemd_manager_isolation "$base_root" "$secrets_root/$app/secrets"
  for spec in "${managed_data_bindings[@]}"; do
    managed_binding_parts "$spec"
    target="$(managed_data_binding_root "$binding")"
    if [ "$mode" = rw ]; then printf 'ReadWritePaths=%s\n' "$target"; else printf 'ReadOnlyPaths=%s\n' "$target"; fi
  done
}
stop_application_unit() {
  local app="$1" unit state group processes
  unit="$(unit_name "$app")"
  state="$(systemctl show --value --property LoadState "$unit" 2>/dev/null)" || reject runtime-stop-failed
  [ -n "$state" ] || reject stop-incomplete
  [ "$state" != not-found ] || return 0
  if ! systemctl stop "$unit"; then
    [ "$state" = bad-setting ] || reject runtime-stop-failed
  fi
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

# A root-owned controller launches project code only under a temporary identity.
build_unit_name() { printf 'windowstolinux-build-%s.service' "$1"; }
build_identity_name() { printf 'wtlb-%s' "$(printf %s "$1" | sha256sum | cut -c1-16)"; }
prepare_build_identity() {
  [ "$#" -eq 2 ] || reject build-identity-arguments
  require_app "$1"; require_candidate "$1" "$2"
  local candidate="$(candidate_root "$2")" user uid gid
  assert_workspace_volume "$candidate"
  user="$(build_identity_name "$2")"
  uid="$(id -u "$user")"; gid="$(id -g "$user")"
  [ "$uid" -gt 0 ] && [ "$gid" -gt 0 ] || reject build-identity-root
  mount -o remount,rw,nodev,nosuid -- "$candidate/mutable" || reject workspace-remount
  chown -hR "$uid:$gid" -- "$candidate/mutable"
  chmod 700 -- "$candidate/mutable"
  chown "$uid:$gid" -- "$candidate/.build-script"
  chmod 400 -- "$candidate/.build-script"
}
stop_candidate_build() {
  local app="$1" candidate_id="$2" unit state
  require_app "$app"; require_candidate "$app" "$candidate_id"
  unit="$(build_unit_name "$candidate_id")"
  state="$(systemctl show --value --property LoadState "$unit" 2>/dev/null || true)"
  [ "$state" != not-found ] && [ -n "$state" ] || return 0
  systemctl stop "$unit" || reject build-stop-failed
  state="$(systemctl show --value --property ActiveState "$unit" 2>/dev/null || true)"
  case "$state" in inactive|failed|'') ;; *) reject build-still-active ;; esac
  local group
  group="$(systemctl show --value --property ControlGroup "$unit" 2>/dev/null || true)"
  if [ -n "$group" ] && [ -d "/sys/fs/cgroup$group" ]; then
    [ -z "$(find "/sys/fs/cgroup$group" -name cgroup.procs -exec cat {} +)" ] || reject build-processes-remain
  fi
}
run_restricted_build() {
  [ "$#" -eq 7 ] || reject build-arguments
  local app="$1" candidate_id="$2" seconds="$3" processes="$4" memory="$5" output="$6" engine="$7"
  require_app "$app"; require_candidate "$app" "$candidate_id"
  [[ "$seconds" =~ ^[0-9]{1,4}$ ]] && [ "$seconds" -ge 1 ] && [ "$seconds" -le 7200 ] || reject build-time
  [[ "$processes" =~ ^[0-9]{1,4}$ ]] && [ "$processes" -ge 8 ] && [ "$processes" -le 4096 ] || reject build-processes
  [[ "$memory" =~ ^[0-9]{3,6}$ ]] && [ "$memory" -ge 256 ] && [ "$memory" -le 262144 ] || reject build-memory
  [[ "$output" =~ ^[0-9]{4,9}$ ]] && [ "$output" -ge 4096 ] && [ "$output" -le 134217728 ] || reject build-output
  case "$engine" in ordinary|docker|podman) ;; *) reject build-engine ;; esac
  local candidate="$(candidate_root "$candidate_id")" mutable unit user status=0
  mutable="$candidate/mutable"; unit="$(build_unit_name "$candidate_id")"; user="$(build_identity_name "$candidate_id")"
  assert_root_owned_regular "$helper_directory/build-entry"
  [ "$(sha256sum -- "$helper_directory/build-entry" | awk '{print $1}')" = "$build_entry_sha256" ] || reject build-entry-identity
  assert_candidate_for_deployer "$candidate"; assert_workspace_volume "$candidate"
  [ ! -e "$candidate/.sealed" ] || reject build-already-sealed
  [ "$(systemctl show --value --property LoadState "$unit" 2>/dev/null || true)" != loaded ] || reject build-already-running
  trap 'stop_candidate_build "$app" "$candidate_id"; freeze_candidate_build "$app" "$candidate_id"; cleanup_container_builder "$candidate"' EXIT
  trap 'exit 130' HUP INT TERM
  mount -o remount,rw,nodev,nosuid -- "$mutable" || reject workspace-remount
  # No project configuration is read while creating the execution identity.
  if [ "$engine" = ordinary ]; then
    ! getent passwd "$user" >/dev/null && ! getent group "$user" >/dev/null || reject build-identity-collision
  else
    rm -rf --one-file-system -- "$mutable/engine" "$mutable/home" "$mutable/tmp" "$mutable/run"
    prepare_container_builder "$candidate" "$user" "$engine"
  fi
  head -c 1048577 > "$candidate/.build-script"
  [ "$(stat -c %s "$candidate/.build-script")" -le 1048576 ] || reject build-script-limit
  chmod 400 -- "$candidate/.build-script"
  install -d -o root -g root -m 700 -- "$mutable/home" "$mutable/tmp" "$mutable/run"
  local -a properties
  properties=(--property="User=$user" --property="Group=$user" --property="RuntimeMaxSec=$seconds"
    --property="TimeoutStopSec=5s" --property=KillMode=control-group --property=SendSIGKILL=yes
    --property="MemoryMax=${memory}M" --property=MemorySwapMax=0 --property="TasksMax=$processes"
    --property=ProtectSystem=strict --property=ProtectHome=yes
    --property=ProtectProc=invisible --property=PrivateTmp=no --property=RemoveIPC=yes
    --property="ReadWritePaths=$mutable" --property="BindPaths=$mutable/tmp:/tmp $mutable/tmp:/var/tmp $mutable/tmp:/dev/shm"
    --property="ExecStartPre=+$helper_path build-identity $app $candidate_id"
    --property="ExecStopPost=+$helper_path build-freeze $app $candidate_id"
    --property="WorkingDirectory=$mutable" --property=UMask=0077)
  local isolation_property
  while IFS= read -r isolation_property; do properties+=(--property="$isolation_property"); done \
    < <(render_systemd_manager_isolation "$applications_root" "$secrets_root" "$configurations_root" "$data_root")
  if [ "$engine" = ordinary ]; then
    properties+=(--property=DynamicUser=yes --property=NoNewPrivileges=yes --property=CapabilityBoundingSet= --property=ProcSubset=pid
      --property=RestrictNamespaces=yes --property=RestrictSUIDSGID=yes --property=ProtectControlGroups=yes
      --property=PrivateDevices=yes --property=ProtectKernelTunables=yes --property=ProtectKernelModules=yes --property=ProtectKernelLogs=yes)
  else
    # Setuid mapping helpers must access hidden proc entries and the target namespace; the user has no effective capabilities.
    properties+=(--property=Delegate=yes --property=ProtectControlGroups=no --property=NoNewPrivileges=no
      --property="CapabilityBoundingSet=CAP_SETUID CAP_SETGID CAP_SYS_PTRACE CAP_DAC_OVERRIDE CAP_SYS_ADMIN" --property=DevicePolicy=closed
      --property="DeviceAllow=/dev/net/tun rw"
      --property="ReadOnlyPaths=/sys -/proc/sys -/proc/sysrq-trigger"
      --property="InaccessiblePaths=-/dev/kmsg -/dev/mem -/dev/kmem -/proc/kcore")
  fi
  monitor_build_output "$candidate" "$output" systemd-run --quiet --wait --pipe --collect --unit="$unit" "${properties[@]}"     /usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin HOME="$mutable/home" TMPDIR="$mutable/tmp" XDG_RUNTIME_DIR="$mutable/run"     /bin/bash "$helper_directory/build-entry" "$candidate" "$engine" || status=$?
  trap - HUP INT TERM
  stop_candidate_build "$app" "$candidate_id"
  freeze_candidate_build "$app" "$candidate_id"
  cleanup_container_builder "$candidate"
  if [ "$status" -eq 0 ]; then
    printf 'sealed\n' > "$candidate/.sealed"; chmod 400 -- "$candidate/.sealed"
  fi
  trap - EXIT
  return "$status"
}

freeze_candidate_build() {
  [ "$#" -eq 2 ] || reject build-freeze-arguments
  require_app "$1"; require_candidate "$1" "$2"
  local candidate="$(candidate_root "$2")"
  assert_candidate_for_deployer "$candidate"
  # ExecStopPost runs after cgroup termination and before DynamicUser release.
  if [[ ",$(findmnt -rn -M "$candidate/mutable" -o OPTIONS)," = *,ro,* ]]; then
    assert_root_owned_directory "$candidate/mutable"
    return 0
  fi
  chown root:root -- "$candidate/mutable"
  chmod 700 -- "$candidate/mutable"
  if [ -f "$candidate/.build-script" ]; then
    chown root:root -- "$candidate/.build-script"; chmod 400 -- "$candidate/.build-script"
  fi
  assert_workspace_volume "$candidate"
  mount -o remount,ro,nodev,nosuid -- "$candidate/mutable" || reject workspace-freeze-failed
}
assert_sealed_build() {
  local app="$1" candidate_id="$2" candidate="$(candidate_root "$2")"
  stop_candidate_build "$app" "$candidate_id"
  assert_root_owned_regular "$candidate/.sealed"
  [ "$(cat -- "$candidate/.sealed")" = sealed ] || reject build-not-sealed
  assert_workspace_volume "$candidate"
  [[ ",$(findmnt -rn -M "$candidate/mutable" -o OPTIONS)," = *,ro,* ]] || reject workspace-not-frozen
  [ ! -e "$candidate/.builder" ] || reject build-identity-not-released
}

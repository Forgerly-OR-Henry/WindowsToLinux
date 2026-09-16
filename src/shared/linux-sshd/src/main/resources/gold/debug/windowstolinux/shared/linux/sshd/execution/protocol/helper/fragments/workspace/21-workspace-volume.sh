preflight_build() {
  [ "$#" -eq 1 ] || reject build-preflight-arguments
  case "$1" in ordinary|docker|podman) ;; *) reject build-engine ;; esac
  for tool in fallocate losetup mkfs.ext4 mount umount findmnt systemd-run; do command -v "$tool" >/dev/null || reject workspace-tools; done
  [ "$(stat -fc %T /sys/fs/cgroup)" = cgroup2fs ] || reject workspace-cgroup-v2-required
  grep -qw memory /sys/fs/cgroup/cgroup.controllers && grep -qw pids /sys/fs/cgroup/cgroup.controllers || reject workspace-resource-controllers
  systemd-run --quiet --wait --pipe --collect --property=DynamicUser=yes --property=ProtectSystem=strict \
    --property=NoNewPrivileges=yes --property=CapabilityBoundingSet= --property=TasksMax=8 \
    --property=MemoryMax=64M --property=MemorySwapMax=0 --property=PrivateDevices=yes \
    --property=ProtectProc=invisible --property=ProcSubset=pid --property=RestrictNamespaces=yes \
    --property=RuntimeMaxSec=10 --property=KillMode=control-group /usr/bin/true || reject workspace-isolation-unavailable
  if [ "$1" != ordinary ]; then
    for tool in useradd usermod userdel newuidmap newgidmap setpriv skopeo; do command -v "$tool" >/dev/null || reject container-identity-tools; done
    if [ "$1" = docker ]; then
      command -v rootlesskit >/dev/null && command -v slirp4netns >/dev/null && command -v dockerd >/dev/null || reject docker-rootless-tools
    else command -v podman >/dev/null || reject podman-rootless-tools; fi
    local profile=podman
    local -a namespace_properties=()
    [ "$1" != docker ] || profile=rootlesskit
    # Probe under the engine's existing policy instead of an unrelated unshare label.
    if [ -r /sys/kernel/security/apparmor/profiles ] \
      && grep -q "^$profile (" /sys/kernel/security/apparmor/profiles; then
      namespace_properties+=(--property="AppArmorProfile=$profile")
    fi
    systemd-run --quiet --wait --pipe --collect --property=DynamicUser=yes --property=RuntimeMaxSec=10 "${namespace_properties[@]}" \
      /usr/bin/unshare --user --map-root-user /usr/bin/true || reject container-user-namespace-unavailable
  fi
}
# Fixed-capacity candidate storage; only root owns backing files.
require_workspace_limit() {
  [[ "$1" =~ ^[0-9]{1,12}$ ]] && [ "$1" -ge 67108864 ] && [ "$1" -le 137438953472 ] || reject workspace-limit
}
create_workspace_volume() {
  local candidate="$1" bytes="$2" image="$1/.workspace.ext4" device
  require_workspace_limit "$bytes"
  preflight_build ordinary
  [ ! -e "$image" ] && [ ! -L "$image" ] || reject workspace-image-exists
  (umask 077; set -C; : > "$image") || reject workspace-image-create
  fallocate -l "$bytes" -- "$image" || reject workspace-reservation
  chmod 600 -- "$image"
  # Formatting must not discard the reservation.
  mkfs.ext4 -q -F -m 0 -E nodiscard,lazy_itable_init=0,lazy_journal_init=0 "$image" || reject workspace-format
  exec 9> "$base_root/.loop-allocation.lock"
  flock -x 9
  device="$(losetup --find --show --nooverlap --sizelimit "$bytes" "$image")" || reject workspace-loop
  [[ "$device" =~ ^/dev/loop[0-9]+$ ]] || reject workspace-loop-name
  printf '%s\n' "$device" > "$candidate/.workspace-device"
  chmod 400 -- "$candidate/.workspace-device"
  flock -u 9; exec 9>&-
  printf '%s\n' "$bytes" > "$candidate/.workspace-limit"
  printf 'build\n' > "$candidate/.purpose"
  chmod 400 -- "$candidate/.workspace-limit" "$candidate/.purpose"
  mount -t ext4 -o nodev,nosuid "$device" "$candidate/mutable" || reject workspace-mount
}
assert_workspace_volume() {
  local candidate="$1" device
  assert_root_owned_regular "$candidate/.workspace-device"
  assert_root_owned_regular "$candidate/.workspace.ext4"
  device="$(cat -- "$candidate/.workspace-device")"
  [[ "$device" =~ ^/dev/loop[0-9]+$ ]] || reject workspace-loop-name
  [ "$(losetup --noheadings --raw --output BACK-FILE "$device")" = "$candidate/.workspace.ext4" ] || reject workspace-loop-owner
  [ "$(findmnt -rn -M "$candidate/mutable" -o SOURCE)" = "$device" ] || reject workspace-mount-owner
  [ "$(findmnt -rn -M "$candidate/mutable" -o FSTYPE)" = ext4 ] || reject workspace-filesystem
  [ "$(cat -- "$candidate/.purpose")" = build ] || reject workspace-purpose
}
cleanup_workspace_volume() {
  local candidate="$1" device attempt
  if [ -f "$candidate/.workspace-device" ]; then
    assert_root_owned_regular "$candidate/.workspace-device"
    device="$(cat -- "$candidate/.workspace-device")"
    [[ "$device" =~ ^/dev/loop[0-9]+$ ]] || reject workspace-loop-name
    if mountpoint -q "$candidate/mutable"; then
      assert_workspace_volume "$candidate"
      umount -- "$candidate/mutable" || reject workspace-unmount-busy
    fi
    if [ -n "$(losetup -j "$candidate/.workspace.ext4")" ]; then
      [ "$(losetup --noheadings --raw --output BACK-FILE "$device")" = "$candidate/.workspace.ext4" ] || reject workspace-loop-owner
      [ -z "$(findmnt -rn -S "$device")" ] || reject workspace-still-mounted
      losetup --detach "$device" || reject workspace-detach
      for attempt in {1..40}; do
        [ -z "$(losetup -j "$candidate/.workspace.ext4")" ] && break
        sleep 0.25
      done
      [ -z "$(losetup -j "$candidate/.workspace.ext4")" ] || reject workspace-detach-pending
    fi
  elif mountpoint -q "$candidate/mutable"; then
    reject workspace-untracked-mount
  elif [ -e "$candidate/.workspace.ext4" ] && [ -n "$(losetup -j "$candidate/.workspace.ext4")" ]; then
    reject workspace-untracked-loop
  fi
}

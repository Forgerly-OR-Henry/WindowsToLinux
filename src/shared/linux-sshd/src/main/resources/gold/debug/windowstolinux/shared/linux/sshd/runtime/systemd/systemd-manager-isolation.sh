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

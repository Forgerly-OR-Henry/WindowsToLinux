podman_quadlet_path() { printf '/etc/containers/systemd/windowstolinux-%s.container' "$1"; }
podman_quadlet_autostart_path() { printf '%s.d/10-windowstolinux-autostart.conf' "$(podman_quadlet_path "$1")"; }
podman_quadlet_autostart_enabled() {
  local autostart
  autostart="$(podman_quadlet_autostart_path "$1")"
  [ -f "$autostart" ] && [ ! -L "$autostart" ]
}
set_podman_quadlet_autostart() {
  local app="$1" enabled="$2" quadlet directory autostart tmp
  [ "$enabled" = 0 ] || [ "$enabled" = 1 ] || reject container-autostart
  quadlet="$(podman_quadlet_path "$app")"; directory="$quadlet.d"; autostart="$(podman_quadlet_autostart_path "$app")"
  if [ "$enabled" = 1 ]; then
    install -d -o root -g root -m 755 -- "$directory"
    tmp="$(mktemp "$directory/.windowstolinux-autostart.XXXXXX")"
    trap 'rm -f -- "$tmp"' EXIT
    printf '[Install]\nWantedBy=multi-user.target\n' > "$tmp"
    install -o root -g root -m 644 -- "$tmp" "$autostart"
    rm -f -- "$tmp"; trap - EXIT
  else
    rm -f -- "$autostart"
    rmdir -- "$directory" 2>/dev/null || true
  fi
  systemctl daemon-reload
}

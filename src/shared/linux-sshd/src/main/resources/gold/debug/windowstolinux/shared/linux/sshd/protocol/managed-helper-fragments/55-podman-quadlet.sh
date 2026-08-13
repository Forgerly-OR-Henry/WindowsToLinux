podman_quadlet_path() { printf '/etc/containers/systemd/windowstolinux-%s.container' "$1"; }
podman_quadlet_autostart_path() { printf '%s.d/10-windowstolinux-autostart.conf' "$(podman_quadlet_path "$1")"; }
podman_quadlet_autostart_enabled() {
  local autostart
  autostart="$(podman_quadlet_autostart_path "$1")"
  [ -f "$autostart" ] && [ ! -L "$autostart" ]
}
podman_cni_marker_path() { printf '%s/.windowstolinux-podman-cni-ports' "$(app_root "$1")"; }
podman_cni_comment() { printf 'windowstolinux-podman-%s' "$1"; }
podman_cni_clear_rules() {
  local app="$1" marker rule ip port extra comment
  marker="$(podman_cni_marker_path "$app")"; comment="$(podman_cni_comment "$app")"
  if [ ! -e "$marker" ] && [ ! -L "$marker" ]; then return; fi
  assert_root_owned_regular "$marker"
  while IFS= read -r rule; do
    IFS=: read -r ip port extra <<< "$rule"
    [ -n "$ip" ] && [ -n "$port" ] && [ -z "$extra" ] || reject podman-cni-marker
    [[ "$ip" =~ ^([0-9]{1,3}[.]){3}[0-9]{1,3}$ ]] || reject podman-cni-marker
    [[ "$port" =~ ^[0-9]{1,5}$ ]] && [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || reject podman-cni-marker
    iptables -w -D CNI-ADMIN -d "$ip/32" -p tcp --dport "$port" -m comment --comment "$comment" -j ACCEPT 2>/dev/null || true
  done < "$marker"
  rm -f -- "$marker"
}
podman_cni_forward() {
  [ "$#" -eq 2 ] || reject podman-cni-forward-arguments
  local app="$1" manifest="$2" backend image ip root marker tmp spec port comment
  require_app "$app"; require_digest "$manifest"
  container_current_release "$app" "$manifest"
  [ "$previous_present" -eq 1 ] && [ "$container_engine" = podman ] || reject podman-cni-current
  backend="$(podman info --format '{{.Host.NetworkBackend}}')"
  case "$backend" in netavark) return ;; cni) ;; *) reject podman-network-backend ;; esac
  command -v iptables >/dev/null 2>&1 || reject podman-cni-iptables
  iptables -w -nL CNI-ADMIN >/dev/null 2>&1 || reject podman-cni-chain
  podman_cni_clear_rules "$app"
  image="$(container_name "$app")"
  ip="$(podman inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$image")"
  [[ "$ip" =~ ^([0-9]{1,3}[.]){3}[0-9]{1,3}$ ]] || reject podman-cni-address
  root="$(app_root "$app")"; marker="$(podman_cni_marker_path "$app")"; comment="$(podman_cni_comment "$app")"
  tmp="$(mktemp "$root/.windowstolinux-podman-cni.XXXXXX")"
  trap 'rm -f -- "$tmp"' EXIT
  for spec in "${container_ports[@]}"; do
    port="${spec#*:}"
    iptables -w -C CNI-ADMIN -d "$ip/32" -p tcp --dport "$port" -m comment --comment "$comment" -j ACCEPT 2>/dev/null \
      || iptables -w -I CNI-ADMIN 1 -d "$ip/32" -p tcp --dport "$port" -m comment --comment "$comment" -j ACCEPT
    printf '%s:%s\n' "$ip" "$port" >> "$tmp"
  done
  install -o root -g root -m 600 -- "$tmp" "$marker"
  rm -f -- "$tmp"; trap - EXIT
}
podman_cni_clear() {
  [ "$#" -eq 2 ] || reject podman-cni-clear-arguments
  require_app "$1"; require_digest "$2"
  podman_cni_clear_rules "$1"
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

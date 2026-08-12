parse_container_parameters() {
  [ "$#" -ge 3 ] || reject container-arguments
  container_engine="$1"
  shift
  case "$container_engine" in docker|podman) ;; *) reject container-engine ;; esac
  require_count "$1"
  local port_count="$1"
  shift
  container_ports=()
  while [ "$port_count" -gt 0 ]; do
    [ "$#" -ge 2 ] || reject container-ports
    [[ "$1" =~ ^[0-9]{1,5}$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ] || reject container-port
    [[ "$2" =~ ^[0-9]{1,5}$ ]] && [ "$2" -ge 1 ] && [ "$2" -le 65535 ] || reject container-port
    container_ports+=("$1:$2")
    shift 2
    port_count=$((port_count - 1))
  done
  require_count "$1"
  local volume_count="$1"
  shift
  container_volumes=()
  while [ "$volume_count" -gt 0 ]; do
    [ "$#" -ge 3 ] || reject container-volumes
    [[ "$1" =~ ^windowstolinux-[a-z0-9][a-z0-9-]{0,62}$ ]] || reject container-volume
    [[ "$2" =~ ^/[A-Za-z0-9._/-]{1,255}$ ]] && [ "$2" != / ] && [[ "$2" != *..* && "$2" != *//* ]] || reject container-volume-path
    [ "$3" = 0 ] || [ "$3" = 1 ] || reject container-volume-mode
    container_volumes+=("$1:$2:$3")
    shift 3
    volume_count=$((volume_count - 1))
  done
  [ "$#" -eq 0 ] || reject container-arguments
}
save_container_parameters() {
  local target="$1" spec source destination mode
  {
    printf '%s\n' "$deployment_configuration_digest"
    printf '%s\n' "${#deployment_secret_identifiers[@]}"
    local index=0
    while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
      printf '%s\n' "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}" "${deployment_secret_digests[$index]}"
      index=$((index + 1))
    done
    printf '%s\n' "$container_engine"
    printf '%s\n' "${#container_ports[@]}"
    for spec in "${container_ports[@]}"; do printf '%s\n' "${spec%%:*}" "${spec#*:}"; done
    printf '%s\n' "${#container_volumes[@]}"
    for spec in "${container_volumes[@]}"; do
      source="${spec%%:*}"; destination="${spec#*:}"; destination="${destination%:*}"; mode="${spec##*:}"
      printf '%s\n' "$source" "$destination" "$mode"
    done
  } > "$target"
  chown root:root -- "$target"
  chmod 444 -- "$target"
}
load_container_parameters() {
  local source="$1"
  local -a saved
  assert_root_owned_regular "$source"
  mapfile -t saved < "$source"
  parse_deployment_inputs "${saved[@]}"
  parse_container_parameters "${deployment_remaining_arguments[@]}"
}
container_name() { printf 'windowstolinux-%s' "$1"; }
container_image() { printf 'windowstolinux-%s:%s' "$1" "$2"; }
container_current_release() {
  local app="$1" manifest="$2"
  local root releases current current_digest
  root="$(app_root "$app")"; releases="$root/releases"
  previous_present=0; previous_path=; previous_running=0
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then
    [ -L "$root/current" ] || reject current-not-link
    current="$(readlink -f -- "$root/current")"
    case "$current" in "$releases"/[0-9a-f]*) ;; *) reject current-path ;; esac
    current_digest="${current##*/}"; require_digest "$current_digest"
    assert_root_owned_directory "$current"; assert_root_owned_regular "$current/.windowstolinux-owner"
    [ "$(cat -- "$current/.windowstolinux-owner")" = "$manifest" ] || reject current-owner
    assert_root_owned_regular "$current/.windowstolinux-container-engine"
    assert_root_owned_regular "$current/.windowstolinux-container-parameters"
    load_container_parameters "$current/.windowstolinux-container-parameters"
    assert_deployment_inputs "$app" container
    [ "$(cat -- "$current/.windowstolinux-container-engine")" = "$container_engine" ] || reject container-engine-change
    previous_present=1; previous_path="$current"
    if "$container_engine" inspect --format '{{.State.Running}}' "$(container_name "$app")" 2>/dev/null | grep -qx true; then previous_running=1; fi
  fi
}
start_container_release() {
  local app="$1" release_digest="$2"
  local image name volume spec source destination mode config index secret secret_name secret_destination
  local -a args
  image="$(container_image "$app" "$release_digest")"; name="$(container_name "$app")"
  assert_deployment_inputs "$app" container
  config="$(configuration_path "$app" "$deployment_configuration_digest" container)"
  if [ "$container_engine" = docker ]; then
    "$container_engine" rm -f -- "$name" >/dev/null 2>&1 || true
    args=("$container_engine" run -d --name "$name" --restart unless-stopped --env-file "$config")
    for spec in "${container_ports[@]}"; do args+=(--publish "$spec"); done
    for spec in "${container_volumes[@]}"; do
      source="${spec%%:*}"; destination="${spec#*:}"; destination="${destination%:*}"; mode="${spec##*:}"
      if [ "$mode" = 1 ]; then args+=(--mount "type=volume,source=$source,destination=$destination,readonly"); else args+=(--mount "type=volume,source=$source,destination=$destination"); fi
    done
    index=0
    while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
      secret_name="${deployment_secret_names[$index]}"; secret_destination="/run/secrets/$secret_name"
      secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
      args+=(--mount "type=bind,source=$secret,destination=$secret_destination,readonly" --env "$secret_name=$secret_destination")
      index=$((index + 1))
    done
    args+=("$image")
    "${args[@]}" >/dev/null
  else
    local quadlet tmp unit
    quadlet="/etc/containers/systemd/windowstolinux-$app.container"; unit="windowstolinux-$app.service"
    install -d -o root -g root -m 755 -- /etc/containers/systemd
    tmp="$(mktemp /etc/containers/systemd/.windowstolinux-managed.XXXXXX)"
    trap 'rm -f -- "$tmp"' EXIT
    {
      printf '[Container]\nImage=%s\nContainerName=%s\n' "$image" "$name"
      printf 'EnvironmentFile=%s\n' "$config"
      for spec in "${container_ports[@]}"; do printf 'PublishPort=%s\n' "$spec"; done
      for spec in "${container_volumes[@]}"; do
        source="${spec%%:*}"; destination="${spec#*:}"; destination="${destination%:*}"; mode="${spec##*:}"
        if [ "$mode" = 1 ]; then printf 'Volume=%s:%s:ro\n' "$source" "$destination"; else printf 'Volume=%s:%s\n' "$source" "$destination"; fi
      done
      index=0
      while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
        secret_name="${deployment_secret_names[$index]}"; secret_destination="/run/secrets/$secret_name"
        secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
        printf 'Volume=%s:%s:ro\nEnvironment=%s=%s\n' "$secret" "$secret_destination" "$secret_name" "$secret_destination"
        index=$((index + 1))
      done
      printf '\n[Install]\nWantedBy=default.target\n'
    } > "$tmp"
    install -o root -g root -m 644 -- "$tmp" "$quadlet"
    rm -f -- "$tmp"; trap - EXIT
    systemctl daemon-reload
    systemctl enable "$unit"
    systemctl start "$unit"
  fi
}
publish_container() {
  [ "$#" -ge 7 ] || reject publish-container-arguments
  local app="$1" candidate_id="$2" release_digest="$3" manifest="$4"
  shift 4
  require_app "$app"; require_candidate "$app" "$candidate_id"; require_digest "$release_digest"; require_digest "$manifest"
  initialise_controlled_roots
  local root releases release candidate source candidate_image image
  root="$(app_root "$app")"; releases="$root/releases"; release="$releases/$release_digest"; candidate="$(candidate_root "$candidate_id")"
  container_current_release "$app" "$manifest"
  parse_deployment_inputs "$@"
  parse_container_parameters "${deployment_remaining_arguments[@]}"
  [ ! -e "$release" ] && [ ! -L "$release" ] || reject release-exists
  assert_candidate_for_deployer "$candidate"
  source="$candidate/mutable/source"; [ -d "$source" ] && [ ! -L "$source" ] || reject source-directory
  candidate_image="windowstolinux-candidate:$candidate_id"; image="$(container_image "$app" "$release_digest")"
  "$container_engine" image inspect "$candidate_image" >/dev/null
  "$container_engine" tag "$candidate_image" "$image"
  install -d -o root -g root -m 755 -- "$root" "$releases" "$release"
  printf '%s\n' "$manifest" > "$release/.windowstolinux-owner"
  printf '%s\n' "$container_engine" > "$release/.windowstolinux-container-engine"
  chown root:root -- "$release/.windowstolinux-owner" "$release/.windowstolinux-container-engine"
  chmod 444 -- "$release/.windowstolinux-owner" "$release/.windowstolinux-container-engine"
  save_container_parameters "$release/.windowstolinux-container-parameters"
  ln -sfnT -- "$release" "$root/current"
  start_container_release "$app" "$release_digest"
  printf 'PUBLISHED=1\n'
}
snapshot_container() {
  [ "$#" -eq 2 ] || reject snapshot-container-arguments
  local app="$1" manifest="$2"
  require_app "$app"; require_digest "$manifest"
  initialise_controlled_roots
  container_current_release "$app" "$manifest"
  if [ "$previous_present" -eq 0 ]; then printf 'PREVIOUS=0\n'; return; fi
  local token snapshot autostart
  token="$(cat /proc/sys/kernel/random/uuid)"; require_snapshot_token "$token"; snapshot="$(snapshot_root "$app" "$token")"
  install -d -o root -g root -m 700 -- "$snapshot"
  printf '%s\n' "$previous_path" > "$snapshot/current-path"
  printf '%s\n' "$previous_running" > "$snapshot/runtime"
  install -o root -g root -m 600 -- "$previous_path/.windowstolinux-container-parameters" "$snapshot/container-parameters"
  if [ "$container_engine" = docker ]; then
    autostart="$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$(container_name "$app")" 2>/dev/null || true)"
  else
    autostart="$(systemctl is-enabled "windowstolinux-$app.service" 2>/dev/null || true)"
  fi
  printf '%s\n' "$autostart" > "$snapshot/autostart"
  chown root:root -- "$snapshot/current-path" "$snapshot/runtime" "$snapshot/container-parameters" "$snapshot/autostart"
  chmod 600 -- "$snapshot/current-path" "$snapshot/runtime" "$snapshot/container-parameters" "$snapshot/autostart"
  printf 'SNAPSHOT_TOKEN=%s\nPREVIOUS=1\nPREVIOUS_RUNNING=%s\n' "$token" "$previous_running"
}
stop_container_runtime() {
  local app="$1"
  if [ "$container_engine" = podman ]; then
    systemctl stop "windowstolinux-$app.service" || true
  else
    "$container_engine" stop "$(container_name "$app")" >/dev/null 2>&1 || true
  fi
}
rollback_container() {
  [ "$#" -eq 4 ] || reject rollback-container-arguments
  local app="$1" candidate_digest="$2" manifest="$3" token="$4"
  require_app "$app"; require_digest "$candidate_digest"; require_digest "$manifest"; require_snapshot_token "$token"
  local root releases candidate snapshot previous previous_digest previous_runtime
  root="$(app_root "$app")"; releases="$root/releases"; candidate="$releases/$candidate_digest"; snapshot="$(snapshot_root "$app" "$token")"
  assert_root_owned_directory "$root"; assert_root_owned_directory "$releases"; assert_root_owned_directory "$snapshot"
  assert_root_owned_regular "$snapshot/current-path"; assert_root_owned_regular "$snapshot/runtime"
  assert_root_owned_regular "$snapshot/container-parameters"; assert_root_owned_regular "$snapshot/autostart"
  load_container_parameters "$snapshot/container-parameters"
  previous="$(cat -- "$snapshot/current-path")"; previous_digest="${previous##*/}"; require_digest "$previous_digest"
  [ "$previous" = "$releases/$previous_digest" ] || reject snapshot-current
  assert_root_owned_directory "$previous"; assert_root_owned_regular "$previous/.windowstolinux-owner"
  [ "$(cat -- "$previous/.windowstolinux-owner")" = "$manifest" ] || reject previous-owner
  previous_runtime="$(cat -- "$snapshot/runtime")"; [ "$previous_runtime" = 0 ] || [ "$previous_runtime" = 1 ] || reject snapshot-runtime
  stop_container_runtime "$app"
  ln -sfnT -- "$previous" "$root/current"
  start_container_release "$app" "$previous_digest"
  if [ "$(cat -- "$snapshot/autostart")" = no ]; then
    if [ "$container_engine" = docker ]; then "$container_engine" update --restart no "$(container_name "$app")" >/dev/null; else systemctl disable "windowstolinux-$app.service"; fi
  fi
  if [ "$previous_runtime" = 0 ]; then stop_container_runtime "$app"; fi
  if [ -e "$candidate" ] || [ -L "$candidate" ]; then
    assert_root_owned_directory "$candidate"; assert_root_owned_regular "$candidate/.windowstolinux-owner"
    [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
    rm -rf --one-file-system -- "$candidate"
  fi
  rm -rf --one-file-system -- "$snapshot"
  printf 'ROLLED_BACK=1\n'
}
rollback_container_first() {
  [ "$#" -eq 3 ] || reject rollback-container-first-arguments
  local app="$1" candidate_digest="$2" manifest="$3"
  require_app "$app"; require_digest "$candidate_digest"; require_digest "$manifest"
  local root releases candidate
  root="$(app_root "$app")"; releases="$root/releases"; candidate="$releases/$candidate_digest"
  assert_root_owned_directory "$root"; assert_root_owned_directory "$releases"
  if [ ! -e "$candidate" ] && [ ! -L "$candidate" ]; then
    [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject rollback-current
    ! docker inspect "$(container_name "$app")" >/dev/null 2>&1 || reject current-container
    [ ! -e "/etc/containers/systemd/windowstolinux-$app.container" ] \
      && [ ! -L "/etc/containers/systemd/windowstolinux-$app.container" ] || reject current-container-unit
    rmdir -- "$releases" "$root" 2>/dev/null || true
    printf 'ROLLED_BACK=1\n'
    return
  fi
  assert_root_owned_directory "$candidate"; assert_root_owned_regular "$candidate/.windowstolinux-container-parameters"
  load_container_parameters "$candidate/.windowstolinux-container-parameters"
  stop_container_runtime "$app"
  if [ "$container_engine" = docker ]; then "$container_engine" rm -f "$(container_name "$app")" >/dev/null 2>&1 || true; fi
  if [ "$container_engine" = podman ]; then
    rm -f -- "/etc/containers/systemd/windowstolinux-$app.container"
    systemctl daemon-reload
  fi
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then rm -f -- "$root/current"; fi
  if [ -e "$candidate" ] || [ -L "$candidate" ]; then
    assert_root_owned_directory "$candidate"; assert_root_owned_regular "$candidate/.windowstolinux-owner"
    [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
    rm -rf --one-file-system -- "$candidate"
  fi
  printf 'ROLLED_BACK=1\n'
}
lifecycle_container() {
  [ "$#" -eq 3 ] || reject lifecycle-container-arguments
  local app="$1" action="$2" manifest="$3"
  require_app "$app"; require_digest "$manifest"
  container_current_release "$app" "$manifest"
  [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
  case "$action" in
    start)
      if [ "$container_engine" = docker ]; then docker start "$(container_name "$app")" >/dev/null; else systemctl start "windowstolinux-$app.service"; fi
      ;;
    stop) stop_container_runtime "$app" ;;
    restart)
      if [ "$container_engine" = docker ]; then docker restart "$(container_name "$app")" >/dev/null; else systemctl restart "windowstolinux-$app.service"; fi
      ;;
    enable)
      if [ "$container_engine" = docker ]; then "$container_engine" update --restart unless-stopped "$(container_name "$app")" >/dev/null; else systemctl enable "windowstolinux-$app.service"; fi
      ;;
    disable)
      if [ "$container_engine" = docker ]; then "$container_engine" update --restart no "$(container_name "$app")" >/dev/null; else systemctl disable "windowstolinux-$app.service"; fi
      ;;
    *) reject lifecycle-action ;;
  esac
  printf 'LIFECYCLE=%s\n' "$action"
}

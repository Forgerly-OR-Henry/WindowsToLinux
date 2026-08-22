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
    printf '%s\n' "$managed_data_application" "$managed_data_component" "${#managed_data_bindings[@]}"
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
  parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
  [ "${#managed_data_bindings[@]}" -eq 0 ] || reject container-managed-file-binding
  parse_container_parameters "${managed_data_remaining_arguments[@]}"
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
    assert_root_owned_regular "$current/.windowstolinux-container-image-id"
    load_container_parameters "$current/.windowstolinux-container-parameters"
    assert_deployment_inputs "$app" container
    [ "$(cat -- "$current/.windowstolinux-container-engine")" = "$container_engine" ] || reject container-engine-change
    [ "$("$container_engine" image inspect --format '{{.Id}}' "$(container_image "$app" "$current_digest")")" \
      = "$(cat -- "$current/.windowstolinux-container-image-id")" ] || reject container-image-identity
    previous_present=1; previous_path="$current"
    if "$container_engine" inspect --format '{{.State.Running}}' "$(container_name "$app")" 2>/dev/null | grep -qx true; then previous_running=1; fi
  fi
}
start_container_release() {
  local app="$1" release_digest="$2" manifest="$3"
  local image name volume spec source destination mode config index secret secret_name secret_destination
  local -a args
  image="$(container_image "$app" "$release_digest")"; name="$(container_name "$app")"
  assert_deployment_inputs "$app" container
  [ "${#managed_data_bindings[@]}" -eq 0 ] || reject container-managed-file-binding
  for spec in "${container_volumes[@]}"; do
    source="${spec%%:*}"
    if "$container_engine" volume inspect "$source" >/dev/null 2>&1; then
      [ "$("$container_engine" volume inspect --format '{{ index .Labels \"io.windowstolinux.owner\" }}' "$source")" = "$manifest" ] \
        && [ "$("$container_engine" volume inspect --format '{{ index .Labels \"io.windowstolinux.application\" }}' "$source")" = "$managed_data_application" ] \
        && [ "$("$container_engine" volume inspect --format '{{ index .Labels \"io.windowstolinux.component\" }}' "$source")" = "$managed_data_component" ] \
        || reject container-volume-owner
    else
      "$container_engine" volume create --label "io.windowstolinux.owner=$manifest" \
        --label "io.windowstolinux.application=$managed_data_application" \
        --label "io.windowstolinux.component=$managed_data_component" "$source" >/dev/null
    fi
  done
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
    quadlet="$(podman_quadlet_path "$app")"; unit="windowstolinux-$app.service"
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
      printf '\n[Service]\nExecStartPost=/usr/local/lib/windowstolinux/managed-helper podman-cni-forward %s %s\n' "$app" "$manifest"
      printf 'ExecStopPost=/usr/local/lib/windowstolinux/managed-helper podman-cni-clear %s %s\n' "$app" "$manifest"
    } > "$tmp"
    install -o root -g root -m 644 -- "$tmp" "$quadlet"
    rm -f -- "$tmp"; trap - EXIT
    set_podman_quadlet_autostart "$app" 1
    systemctl restart "$unit"
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
  parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
  [ "${#managed_data_bindings[@]}" -eq 0 ] || reject container-managed-file-binding
  parse_container_parameters "${managed_data_remaining_arguments[@]}"
  [ ! -e "$release" ] && [ ! -L "$release" ] || reject release-exists
  assert_candidate_for_deployer "$candidate"
  source="$candidate/mutable/source"; [ -d "$source" ] && [ ! -L "$source" ] || reject source-directory
  [ -z "$(find -P "$source" -xdev -type l -print -quit)" ] || reject source-symlink
  [ -z "$(find -P "$source" -xdev ! -type f ! -type d -print -quit)" ] || reject source-special-file
  [ -z "$(find -P "$source" -xdev -type f -links +1 -print -quit)" ] || reject source-hardlink
  candidate_image="windowstolinux-candidate:$candidate_id"; image="$(container_image "$app" "$release_digest")"
  "$container_engine" image inspect "$candidate_image" >/dev/null
  [ "$("$container_engine" image inspect --format '{{ index .Config.Labels \"io.windowstolinux.application\" }}' "$candidate_image")" = "$app" ] \
    && [ "$("$container_engine" image inspect --format '{{ index .Config.Labels \"io.windowstolinux.candidate\" }}' "$candidate_image")" = "$candidate_id" ] \
    || reject container-image-owner
  "$container_engine" tag "$candidate_image" "$image"
  install -d -o root -g root -m 755 -- "$root" "$releases" "$release"
  cp -a --no-preserve=ownership -- "$source" "$release/source"
  chown -R root:root -- "$release/source"
  printf '%s\n' "$manifest" > "$release/.windowstolinux-owner"
  printf '%s\n' "$container_engine" > "$release/.windowstolinux-container-engine"
  "$container_engine" image inspect --format '{{.Id}}' "$image" > "$release/.windowstolinux-container-image-id"
  chown root:root -- "$release/.windowstolinux-owner" "$release/.windowstolinux-container-engine" \
    "$release/.windowstolinux-container-image-id"
  chmod 444 -- "$release/.windowstolinux-owner" "$release/.windowstolinux-container-engine" \
    "$release/.windowstolinux-container-image-id"
  save_container_parameters "$release/.windowstolinux-container-parameters"
  ln -sfnT -- "$release" "$root/current"
  start_container_release "$app" "$release_digest" "$manifest"
  printf 'PUBLISHED=1\n'
}

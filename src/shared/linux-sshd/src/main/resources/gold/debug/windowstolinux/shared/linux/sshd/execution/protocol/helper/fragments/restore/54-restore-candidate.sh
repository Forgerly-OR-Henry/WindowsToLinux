require_restore_token() { [[ "$1" =~ ^[0-9a-f]{32}$ ]] || reject restore-token; }
restore_activation_root() { printf '%s/mutable/restore-activation' "$(candidate_root "$1")"; }
restore_component_root() { printf '%s/%s' "$(restore_activation_root "$1")" "$2"; }
restore_unit_name() { printf 'windowstolinux-restore-%s-%s.service' "$1" "$2"; }
restore_container_name() { printf 'windowstolinux-restore-%s-%s' "$1" "$2"; }
require_restore_member() {
  [[ "$1" =~ ^[A-Za-z0-9._/-]{1,512}$ ]] && [[ "$1" != /* && "$1" != *..* && "$1" != *//* ]] \
    || reject restore-member
}
assert_restore_archive() {
  local archive="$1" invalid
  assert_root_owned_regular "$archive"
  invalid="$(tar -tvf "$archive" | awk 'substr($1,1,1)!="-" && substr($1,1,1)!="d" {print; exit}')"
  [ -z "$invalid" ] || reject restore-archive-type
  ! tar -tf "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$)|(^|/)\.(/|$))' || reject restore-archive-path
}
restore_preflight() {
  [ "$#" -eq 2 ] || reject restore-preflight-arguments
  local app="$1" required="$2" available ports
  require_app "$app"; [[ "$required" =~ ^[0-9]{1,12}$ ]] || reject restore-required-bytes
  available="$(df -PB1 "$base_root" 2>/dev/null | awk 'NR==2 {print $4}')"
  [[ "$available" =~ ^[0-9]+$ ]] || reject restore-space
  ports="$(ss -H -ltn 2>/dev/null | awk '{value=$4; sub(/^.*:/,"",value); if(value~/^[0-9]+$/) print value}' | sort -nu | paste -sd, -)"
  printf 'MANAGED_ROOT_WRITABLE=1\nFOREIGN_CONFLICT=0\nAVAILABLE_BYTES=%s\nOCCUPIED_PORTS=%s\n' "$available" "$ports"
}
restore_prepare_component() {
  [ "$#" -ge 10 ] || reject restore-prepare-arguments
  local candidate="$1" token="$2" component="$3" app="$4" owner="$5" release="$6" release_member="$7"
  shift 7; require_candidate "${candidate%-*}" "$candidate" 2>/dev/null || [[ "$candidate" =~ ^[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}$ ]] || reject candidate-id
  require_restore_token "$token"; require_app "$component"; require_app "$app"; require_digest "$owner"; require_digest "$release"
  require_restore_member "$release_member"; require_count "$1"; local persistent_count="$1"; shift
  local -a persistent=(); local member
  while [ "$persistent_count" -gt 0 ]; do [ "$#" -ge 1 ] || reject restore-persistent; member="$1"; shift
    require_restore_member "$member"; persistent+=("$member"); persistent_count=$((persistent_count-1)); done
  [ "$#" -ge 2 ] || reject restore-prepare-arguments
  local oci="$1"; shift; [ "$oci" = - ] || require_restore_member "$oci"
  require_count "$1"; local port_count="$1"; shift; local -a ports=(); local official selected
  while [ "$port_count" -gt 0 ]; do [ "$#" -ge 2 ] || reject restore-ports
    official="$1"; selected="$2"; shift 2; require_service_port "$official"; require_service_port "$selected"
    [ "$selected" -ge 49152 ] && [ "$official" != "$selected" ] || reject restore-ports
    ports+=("$official:$selected"); port_count=$((port_count-1)); done
  [ "$#" -eq 0 ] || reject restore-prepare-arguments
  local candidate_path staged root archive release_root data_path base name
  candidate_path="$(candidate_root "$candidate")"; assert_candidate_for_deployer "$candidate_path"
  staged="$candidate_path/mutable/restore"; [ -d "$staged" ] && [ ! -L "$staged" ] || reject restore-staging
  [ "$(stat -c '%U' -- "$staged")" = "$deployer" ] || [ "$(stat -c '%u' -- "$staged")" = 0 ] || reject restore-staging-owner
  chown -R root:root -- "$staged"; find "$staged" -type d -exec chmod 700 {} +; find "$staged" -type f -exec chmod 400 {} +
  root="$(restore_component_root "$candidate" "$component")"; [ ! -e "$root" ] && [ ! -L "$root" ] || reject restore-component-exists
  install -d -o root -g root -m 700 -- "$root/release" "$root/data" "$root/rollback"
  printf '%s\n' "$token" > "$root/activation-token"; chmod 400 -- "$root/activation-token"
  archive="$staged/$release_member"; assert_restore_archive "$archive"
  tar --extract --file "$archive" --directory "$root/release" --no-same-owner --no-same-permissions
  chown -R root:root -- "$root/release"; assert_root_owned_regular "$root/release/.windowstolinux-owner"
  [ "$(cat -- "$root/release/.windowstolinux-owner")" = "$owner" ] || reject restore-owner
  if [ -e "$root/release/.windowstolinux-deployment-parameters" ]; then
    if [ -e "$root/release/.windowstolinux-toolchains" ]; then
      assert_root_owned_regular "$root/release/.windowstolinux-toolchains"
      [ "$(stat -c '%s' -- "$root/release/.windowstolinux-toolchains")" -le 65536 ] || reject restore-toolchain-size
      local previous_binding restored_binding toolchain_result
      previous_binding="$(sha256sum -- "$root/release/.windowstolinux-toolchains" | awk '{print $1}')"
      toolchain_result="$(prepare_official_toolchains restore "$(base64 -w0 -- "$root/release/.windowstolinux-toolchains")")" || reject restore-toolchain-preparation
      restored_binding="$(printf '%s\n' "$toolchain_result" | sed -n 's/^TOOLCHAIN_BINDING=//p')"; require_digest "$restored_binding"
      if [ "$previous_binding" != "$restored_binding" ]; then
        awk -v old="$previous_binding" -v new="$restored_binding" 'previous == "tools-v1" && $0 == old {$0=new} {print;previous=$0}' \
          "$root/release/.windowstolinux-deployment-parameters" > "$root/relocated-parameters"
        install -o root -g root -m 444 -- "$root/relocated-parameters" "$root/release/.windowstolinux-deployment-parameters"
        install -o root -g root -m 444 -- "/usr/local/lib/windowstolinux/toolchains/bindings/$restored_binding" "$root/release/.windowstolinux-toolchains"
        rm -f -- "$root/relocated-parameters"
      fi
      prepare_official_toolchains relocate-venv "$root/release" "$restored_binding" || reject restore-python-relocation
    fi
    current_application="$app"; load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
    parse_deployment_inputs "${deployment_runtime_parameters[@]}"; assert_deployment_inputs "$app" systemd
    [ "$runtime_identity_policy" = SYSTEMD_DYNAMIC ] || reject restore-runtime-identity-policy
    printf 'deployment\n' > "$root/kind"
  else
    current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"
    [ "$runtime_identity_policy" = CONTAINER_NON_ROOT ] || reject restore-runtime-identity-policy
    assert_deployment_inputs "$app" container; printf 'container\n' > "$root/kind"
  fi
  for member in "${persistent[@]}"; do
    archive="$staged/$member"; assert_restore_archive "$archive"; base="${member##*/}"; name="${base%.pax}"
    data_path="$root/data/$name"; install -d -o root -g root -m 700 -- "$data_path"
    tar --extract --file "$archive" --directory "$data_path" --no-same-owner --no-same-permissions
    chown -R "$deployer:$deployer_group" -- "$data_path"
  done
  printf '%s\n' "$app" "$owner" "$release" "$oci" > "$root/identity"
  printf '%s\n' "${ports[@]}" > "$root/ports"
  chown root:root -- "$root/kind" "$root/identity" "$root/ports"; chmod 400 -- "$root/kind" "$root/identity" "$root/ports"
  printf 'PREPARED=1\nCOMPONENT=%s\n' "$component"
}
restore_start_deployment_candidate() {
  local candidate="$1" token="$2" component="$3" root app port fake unit tmp spec binding logical mode link target relative private public
  root="$(restore_component_root "$candidate" "$component")"; assert_root_owned_directory "$root"
  mapfile -t identity < "$root/identity"; app="${identity[0]}"; current_application="$app"
  load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
  [ "$runtime_identity_policy" = SYSTEMD_DYNAMIC ] || reject restore-runtime-identity-policy
  parse_deployment_inputs "${deployment_runtime_parameters[@]}"; parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
  relative="windowstolinux/restore/$token/$component"; private="/var/lib/private/$relative"; public="/var/lib/$relative"
  [ ! -e "$private" ] && [ ! -e "$public" ] && [ ! -L "$public" ] || reject restore-state-exists
  printf '%s\n' "$relative" > "$root/candidate-state"; chmod 400 -- "$root/candidate-state"
  install -d -o root -g root -m 700 -- /var/lib/private "$private/files"
  install -d -o root -g root -m 755 -- "${public%/*}"
  ln -srT -- "$private" "$public"
  for spec in "${managed_data_bindings[@]}"; do
    binding="${spec%%:*}"; logical="${spec#*:}"; logical="${logical%:*}"; target="$public/files/$binding"; link="$root/release/source/$logical"
    [ -d "$root/data/$binding" ] && [ ! -L "$root/data/$binding" ] || reject restore-data-missing
    cp -a -- "$root/data/$binding" "$target"
    install -d -o root -g root -m 755 -- "$(dirname -- "$link")"
    rm -rf --one-file-system -- "$link"
    ln -sT -- "$target" "$link"
  done
  fake="/run/windowstolinux-restore-$token-$component"
  port="$(awk -F: 'NR==1 {print $2}' "$root/ports")"; require_service_port "$port"
  deployment_root_override="$fake"; deployment_service_port_override="$port"; deployment_bind_address_override=127.0.0.1
  runtime_state_override="$relative"; runtime_identity_override="restore-$token-$component"
  unit="$(restore_unit_name "$token" "$component")"; tmp="$(mktemp /run/systemd/system/.windowstolinux-restore.XXXXXX)"
  render_deployment_unit "$app" "${deployment_runtime_parameters[@]}" > "$tmp"
  printf '\n[Service]\nRuntimeDirectory=%s\nBindReadOnlyPaths=%s:%s/current\n' "${fake#/run/}" "$root/release" "$fake" >> "$tmp"
  find -P "$root/release" -type d -exec chmod 755 {} +
  find -P "$root/release" -type f -exec chmod a+r {} +
  install -o root -g root -m 600 -- "$tmp" "/run/systemd/system/$unit"; rm -f -- "$tmp"
  unset deployment_root_override deployment_service_port_override deployment_bind_address_override runtime_state_override runtime_identity_override
  systemctl daemon-reload; systemctl start "$unit"; printf 'STARTED=1\nUNIT=%s\n' "$unit"
}
restore_start_container_candidate() {
  local candidate="$1" token="$2" component="$3" root app owner release oci name image spec source destination mode index config secret secret_name secret_destination
  local -a args mappings
  root="$(restore_component_root "$candidate" "$component")"; mapfile -t identity < "$root/identity"
  app="${identity[0]}"; owner="${identity[1]}"; release="${identity[2]}"; oci="${identity[3]}"; current_application="$app"
  load_container_parameters "$root/release/.windowstolinux-container-parameters"; mapfile -t mappings < "$root/ports"
  image="windowstolinux-restore-$token-$component:stage"; name="$(restore_container_name "$token" "$component")"
  [ "$oci" != - ] || reject restore-image-required
  ! "$container_engine" image inspect "$image" >/dev/null 2>&1 || reject restore-image-collision
  ! "$container_engine" inspect "$name" >/dev/null 2>&1 || reject restore-container-collision
  printf '%s\n' "$image" > "$root/candidate-image"; chmod 400 -- "$root/candidate-image"
  import_restore_image "$container_engine" "$(candidate_root "$candidate")/mutable/restore/$oci" "$image"
  local runtime_user
  runtime_user="$(container_nonroot_user "$container_engine" "$image")"
  config="$(configuration_path "$app" "$deployment_configuration_digest" container)"
  args=("$container_engine" run -d --name "$name" --restart no --user "$runtime_user" --security-opt no-new-privileges --cap-drop ALL --label "io.windowstolinux.restore=$token" --env-file "$config")
  index=0; for spec in "${container_ports[@]}"; do
    [ "$index" -lt "${#mappings[@]}" ] || reject restore-port-count
    args+=(--publish "127.0.0.1:${mappings[$index]#*:}:${spec#*:}"); index=$((index+1)); done
  [ "$index" -eq "${#mappings[@]}" ] || reject restore-port-count
  for spec in "${container_volumes[@]}"; do
    source="${spec%%:*}"; destination="${spec#*:}"; destination="${destination%:*}"; mode="${spec##*:}"
    local candidate_volume="windowstolinux-restore-${token:0:12}-${source#windowstolinux-}"
    ! "$container_engine" volume inspect "$candidate_volume" >/dev/null 2>&1 || reject restore-volume-collision
    "$container_engine" volume create --label "io.windowstolinux.restore=$token" "$candidate_volume" >/dev/null
    local mountpoint="$($container_engine volume inspect --format '{{.Mountpoint}}' "$candidate_volume")"
    prepare_container_volume_access "$container_engine" "$candidate_volume" "$runtime_user"
    [ -d "$root/data/$source" ] || reject restore-volume-missing; cp -a --no-preserve=ownership -- "$root/data/$source/." "$mountpoint/"
    chown -hR "${runtime_user%%:*}:${runtime_user#*:}" -- "$mountpoint"
    if [ "$mode" = 1 ]; then args+=(--mount "type=volume,source=$candidate_volume,destination=$destination,readonly"); else args+=(--mount "type=volume,source=$candidate_volume,destination=$destination"); fi
  done
  index=0; while [ "$index" -lt "${#deployment_secret_identifiers[@]}" ]; do
    secret_name="${deployment_secret_names[$index]}"; secret_destination="/run/secrets/$secret_name"
    secret="$(secret_revision_path "$app" "${deployment_secret_identifiers[$index]}" "${deployment_secret_revisions[$index]}")"
    secret="$(container_secret_delivery "$app" "$release" "$runtime_user" "$secret" "$secret_name")"
    args+=(--mount "type=bind,source=$secret,destination=$secret_destination,readonly" --env "$secret_name=$secret_destination"); index=$((index+1)); done
  args+=("$image"); "${args[@]}" >/dev/null; printf 'STARTED=1\nCONTAINER=%s\n' "$name"
}
restore_start_candidate() {
  [ "$#" -eq 3 ] || reject restore-start-arguments
  local candidate="$1" token="$2" component="$3" root kind
  require_restore_token "$token"; require_app "$component"; root="$(restore_component_root "$candidate" "$component")"
  assert_root_owned_regular "$root/kind"; kind="$(cat -- "$root/kind")"
  if [ "$kind" = deployment ]; then restore_start_deployment_candidate "$@"; else restore_start_container_candidate "$@"; fi
}
restore_stop_candidate() {
  [ "$#" -eq 3 ] || reject restore-stop-arguments
  local candidate="$1" token="$2" component="$3" root kind name state path image volume
  require_restore_token "$token"; require_app "$component"
  root="$(restore_component_root "$candidate" "$component")"; kind="$(cat -- "$root/kind")"
  if [ "$kind" = deployment ]; then
    name="$(restore_unit_name "$token" "$component")"
    state="$(systemctl show --value --property LoadState "$name" 2>/dev/null || true)"
    if [ "$state" = loaded ]; then systemctl stop "$name" || reject restore-stop-failed; fi
    state="$(systemctl show --value --property ActiveState "$name" 2>/dev/null || true)"
    case "$state" in inactive|failed|'') ;; *) reject restore-runtime-active ;; esac
    rm -f -- "/run/systemd/system/$name"; systemctl daemon-reload
    if [ -e "$root/candidate-state" ]; then
      assert_root_owned_regular "$root/candidate-state"
      path="$(cat -- "$root/candidate-state")"
      [ "$path" = "windowstolinux/restore/$token/$component" ] || reject restore-state-record
      if [ -L "/var/lib/$path" ]; then
        [ "$(readlink -- "/var/lib/$path")" = "/var/lib/private/$path" ] || reject restore-state-mapping
        rm -f -- "/var/lib/$path"
      fi
      if [ -d "/var/lib/private/$path" ]; then
        [ ! -L "/var/lib/private/$path" ] && [ "$(readlink -f -- "/var/lib/private/$path")" = "/var/lib/private/$path" ] || reject restore-state-path
        rm -rf --one-file-system -- "/var/lib/private/$path"
      fi
      rmdir -- "/var/lib/windowstolinux/restore/$token" "/var/lib/private/windowstolinux/restore/$token" 2>/dev/null || true
      rm -f -- "$root/candidate-state"
    fi
  else
    mapfile -t identity < "$root/identity"; current_application="${identity[0]}"
    load_container_parameters "$root/release/.windowstolinux-container-parameters"
    name="$(restore_container_name "$token" "$component")"
    if "$container_engine" inspect "$name" >/dev/null 2>&1; then
      [ "$("$container_engine" inspect --format '{{ index .Config.Labels "io.windowstolinux.restore" }}' "$name")" = "$token" ] || reject restore-container-owner
      "$container_engine" rm -f "$name" >/dev/null || reject restore-container-stop
    fi
    for spec in "${container_volumes[@]}"; do
      source="${spec%%:*}"; volume="windowstolinux-restore-${token:0:12}-${source#windowstolinux-}"
      if "$container_engine" volume inspect "$volume" >/dev/null 2>&1; then
        [ "$("$container_engine" volume inspect --format '{{ index .Labels "io.windowstolinux.restore" }}' "$volume")" = "$token" ] || reject restore-volume-owner
        "$container_engine" volume rm "$volume" >/dev/null || reject restore-volume-cleanup
      fi
    done
    image="windowstolinux-restore-$token-$component:stage"
    if [ -e "$root/candidate-image" ]; then
      assert_root_owned_regular "$root/candidate-image"
      [ "$(cat -- "$root/candidate-image")" = "$image" ] || reject restore-image-record
      if "$container_engine" image inspect "$image" >/dev/null 2>&1; then "$container_engine" image rm "$image" >/dev/null || reject restore-image-cleanup; fi
      rm -f -- "$root/candidate-image"
    fi
  fi
  printf 'STOPPED=1\n'
}
cleanup_restore_candidates() {
  local candidate="$1" root component token
  [ -d "$candidate/mutable/restore-activation" ] || return 0
  for root in "$candidate/mutable/restore-activation"/*; do
    [ -d "$root" ] && [ ! -L "$root" ] || continue
    component="${root##*/}"; require_app "$component"
    if [ -e "$root/kind" ] && [ -e "$root/activation-token" ]; then
      assert_root_owned_regular "$root/activation-token"; token="$(cat -- "$root/activation-token")"
      restore_stop_candidate "${candidate##*/}" "$token" "$component"
    fi
  done
}

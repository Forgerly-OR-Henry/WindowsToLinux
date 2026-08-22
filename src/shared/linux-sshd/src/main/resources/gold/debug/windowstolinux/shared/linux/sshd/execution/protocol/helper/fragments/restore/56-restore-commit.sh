restore_snapshot_current() {
  [ "$#" -eq 3 ] || reject restore-snapshot-arguments
  local candidate="$1" token="$2" component="$3" root kind output app owner release previous snapshot_token
  root="$(restore_component_root "$candidate" "$component")"; kind="$(cat -- "$root/kind")"; mapfile -t identity < "$root/identity"
  app="${identity[0]}"; owner="${identity[1]}"; release="${identity[2]}"
  if [ "$kind" = deployment ]; then output="$(snapshot_deployment "$app" "$owner")"
  else current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"; output="$(snapshot_container "$app" "$owner")"; fi
  previous="$(printf '%s\n' "$output" | awk -F= '$1=="PREVIOUS"{print $2}')"; [ "$previous" = 0 ] || [ "$previous" = 1 ] || reject restore-snapshot-evidence
  snapshot_token="$(printf '%s\n' "$output" | awk -F= '$1=="SNAPSHOT_TOKEN"{print $2}')"
  if [ "$previous" = 1 ]; then require_snapshot_token "$snapshot_token"; else snapshot_token=-; fi
  printf '%s\n%s\n' "$previous" "$snapshot_token" > "$root/previous"; chown root:root -- "$root/previous"; chmod 400 -- "$root/previous"
  if [ "$previous" = 1 ]; then
    if [ "$kind" = deployment ]; then systemctl stop "$(unit_name "$app")"
    else current_application="$app"; container_current_release "$app" "$owner"; stop_container_runtime "$app"; fi
  fi
  printf 'SNAPSHOT=1\nPREVIOUS=%s\n' "$previous"
}
restore_data_snapshot_root() {
  local root="$1" app="$2"; mapfile -t previous < "$root/previous"
  if [ "${previous[0]}" = 1 ]; then printf '%s/restore-data' "$(snapshot_root "$app" "${previous[1]}")"
  else printf '%s/new-data' "$root/rollback"; fi
}
restore_mark_quiesced() {
  [ "$#" -ge 4 ] || reject restore-quiesced-arguments
  local candidate="$1" token="$2" count="$3" component root kind app previous marker
  shift 3; require_restore_token "$token"; require_count "$count"; [ "$#" -eq "$count" ] || reject restore-quiesced-count
  for component in "$@"; do
    require_app "$component"; root="$(restore_component_root "$candidate" "$component")"
    assert_root_owned_regular "$root/previous"; kind="$(cat -- "$root/kind")"; mapfile -t identity < "$root/identity"; app="${identity[0]}"
    previous="$(head -n 1 -- "$root/previous")"; [ "$previous" = 0 ] || [ "$previous" = 1 ] || reject restore-previous
    if [ "$previous" = 1 ]; then
      if [ "$kind" = deployment ]; then ! systemctl is-active --quiet "$(unit_name "$app")" || reject restore-writes-active
      else current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"
        ! "$container_engine" inspect --format '{{.State.Running}}' "$(container_name "$app")" 2>/dev/null | grep -qx true || reject restore-writes-active; fi
    fi
  done
  marker="$(restore_activation_root "$candidate")/.application-quiesced"
  printf '%s\n' "$token" > "$marker"; chown root:root -- "$marker"; chmod 400 -- "$marker"
  printf 'QUIESCED=1\n'
}
restore_install_managed_files() {
  local root="$1" app="$2" spec binding logical mode target source rollback status
  parse_deployment_inputs "${deployment_runtime_parameters[@]}"; parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
  rollback="$(restore_data_snapshot_root "$root" "$app")"; install -d -o root -g root -m 700 -- "$rollback"
  for spec in "${managed_data_bindings[@]}"; do
    binding="${spec%%:*}"; logical="${spec#*:}"; logical="${logical%:*}"; mode="${spec##*:}"
    target="$(managed_data_binding_root "$binding")"; source="$root/data/$binding"; [ -d "$source" ] && [ ! -L "$source" ] || reject restore-data-missing
    status="$rollback/$binding.present"; if [ -e "$target" ] || [ -L "$target" ]; then
      [ -d "$target" ] && [ ! -L "$target" ] || reject restore-data-target
      install -d -o root -g root -m 700 -- "$rollback/$binding"; cp -a --no-preserve=ownership -- "$target/." "$rollback/$binding/"; printf '1\n' > "$status"
      rm -rf --one-file-system -- "$target"
    else printf '0\n' > "$status"; fi
    install -d -o "$deployer" -g "$deployer_group" -m 700 -- "$target"; cp -a --no-preserve=ownership -- "$source/." "$target/"
    chown -R "$deployer:$deployer_group" -- "$target"
    local link="$root/release/source/$logical"; install -d -o root -g root -m 755 -- "$(dirname -- "$link")"; rm -rf --one-file-system -- "$link" 2>/dev/null || true
    ln -sT -- "$target" "$link"; if [ "$mode" = ro ]; then chmod -R a-w,u+rX,go-rwx -- "$target"; else chmod -R u+rwX,go-rwx -- "$target"; fi
  done
  chown -R root:root -- "$rollback"; find "$rollback" -type f -exec chmod 400 {} +
}
restore_install_release_tree() {
  local root="$1" app="$2" owner="$3" release="$4" target app_root_path releases
  app_root_path="$(app_root "$app")"; releases="$app_root_path/releases"; target="$releases/$release"
  install -d -o root -g root -m 755 -- "$app_root_path" "$releases"
  if [ -e "$target" ] || [ -L "$target" ]; then
    assert_root_owned_directory "$target"; assert_root_owned_regular "$target/.windowstolinux-owner"
    [ "$(cat -- "$target/.windowstolinux-owner")" = "$owner" ] || reject restore-release-collision
  else cp -a --no-preserve=ownership -- "$root/release" "$target"; chown -R root:root -- "$target"; fi
  ln -sfnT -- "$target" "$app_root_path/current"
}
restore_install_container_volumes() {
  local root="$1" app="$2" owner="$3" spec source target mountpoint rollback status
  rollback="$(restore_data_snapshot_root "$root" "$app")"; install -d -o root -g root -m 700 -- "$rollback"
  for spec in "${container_volumes[@]}"; do
    source="${spec%%:*}"; [ -d "$root/data/$source" ] || reject restore-volume-missing
    status="$rollback/$source.present"
    if "$container_engine" volume inspect "$source" >/dev/null 2>&1; then
      [ "$($container_engine volume inspect --format '{{ index .Labels "io.windowstolinux.owner" }}' "$source")" = "$owner" ] || reject restore-volume-owner
      mountpoint="$($container_engine volume inspect --format '{{.Mountpoint}}' "$source")"; install -d -o root -g root -m 700 -- "$rollback/$source"
      cp -a --no-preserve=ownership -- "$mountpoint/." "$rollback/$source/"; printf '1\n' > "$status"; find "$mountpoint" -mindepth 1 -delete
    else "$container_engine" volume create --label "io.windowstolinux.owner=$owner" \
      --label "io.windowstolinux.application=$managed_data_application" --label "io.windowstolinux.component=$managed_data_component" "$source" >/dev/null
      mountpoint="$($container_engine volume inspect --format '{{.Mountpoint}}' "$source")"; printf '0\n' > "$status"; fi
    cp -a --no-preserve=ownership -- "$root/data/$source/." "$mountpoint/"
  done
  chown -R root:root -- "$rollback"; find "$rollback" -type f -exec chmod 400 {} +
}
restore_start_formal() {
  [ "$#" -eq 3 ] || reject restore-formal-arguments
  local candidate="$1" token="$2" component="$3" root kind app owner release oci unit tmp image
  root="$(restore_component_root "$candidate" "$component")"; kind="$(cat -- "$root/kind")"; mapfile -t identity < "$root/identity"
  assert_root_owned_regular "$(restore_activation_root "$candidate")/.application-quiesced"
  [ "$(cat -- "$(restore_activation_root "$candidate")/.application-quiesced")" = "$token" ] || reject restore-quiesced-token
  app="${identity[0]}"; owner="${identity[1]}"; release="${identity[2]}"; oci="${identity[3]}"; [ -f "$root/previous" ] || reject restore-snapshot-missing
  if [ "$kind" = deployment ]; then
    current_application="$app"; load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
    restore_install_managed_files "$root" "$app"; restore_install_release_tree "$root" "$app" "$owner" "$release"
    unit="$(unit_path "$app")"; tmp="$(mktemp /etc/systemd/system/.windowstolinux-restore.XXXXXX)"
    render_deployment_unit "$app" "${deployment_runtime_parameters[@]}" > "$tmp"; install -o root -g root -m 644 -- "$tmp" "$unit"; rm -f -- "$tmp"
    systemctl daemon-reload; systemctl start "$(unit_name "$app")"
  else
    current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"
    restore_install_container_volumes "$root" "$app" "$owner"
    if [ "$container_engine" = podman ]; then
      [ "$oci" != - ] || reject restore-oci-required; podman load -i "$(candidate_root "$candidate")/mutable/restore/$oci" >/dev/null
    else docker build --label "io.windowstolinux.application=$app" --tag "$(container_image "$app" "$release")" "$root/release/source" >/dev/null; fi
    image="$(container_image "$app" "$release")"; "$container_engine" image inspect "$image" >/dev/null
    "$container_engine" image inspect --format '{{.Id}}' "$image" > "$root/release/.windowstolinux-container-image-id"
    chown root:root -- "$root/release/.windowstolinux-container-image-id"; chmod 444 -- "$root/release/.windowstolinux-container-image-id"
    restore_install_release_tree "$root" "$app" "$owner" "$release"; start_container_release "$app" "$release" "$owner"
  fi
  printf 'FORMAL_STARTED=1\nAPP=%s\n' "$app"
}
restore_quiesce_recovery() {
  [ "$#" -eq 3 ] || reject restore-recovery-quiesce-arguments
  local candidate="$1" token="$2" component="$3" root kind app owner
  restore_stop_candidate "$candidate" "$token" "$component" >/dev/null || true
  root="$(restore_component_root "$candidate" "$component")"; [ -e "$root" ] || { printf 'QUIESCED=1\n'; return; }
  kind="$(cat -- "$root/kind")"; mapfile -t identity < "$root/identity"; app="${identity[0]}"; owner="${identity[1]}"
  if [ -f "$root/previous" ]; then
    if [ "$kind" = deployment ]; then systemctl stop "$(unit_name "$app")" 2>/dev/null || true
    else current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"
      if [ -L "$(app_root "$app")/current" ]; then container_current_release "$app" "$owner"; stop_container_runtime "$app"; fi; fi
  fi
  printf 'QUIESCED=1\n'
}
restore_restore_managed_data() {
  local root="$1" app="$2" kind="$3" rollback spec binding target status source
  rollback="$(restore_data_snapshot_root "$root" "$app")"; [ -d "$rollback" ] || return 0
  if [ "$kind" = deployment ]; then
    current_application="$app"; load_deployment_parameters "$root/release/.windowstolinux-deployment-parameters"
    parse_deployment_inputs "${deployment_runtime_parameters[@]}"; parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
    for spec in "${managed_data_bindings[@]}"; do binding="${spec%%:*}"; target="$(managed_data_binding_root "$binding")"; status="$(cat -- "$rollback/$binding.present")"
      rm -rf --one-file-system -- "$target" 2>/dev/null || true; if [ "$status" = 1 ]; then install -d -o "$deployer" -g "$deployer_group" -m 700 -- "$target"; cp -a --no-preserve=ownership -- "$rollback/$binding/." "$target/"; chown -R "$deployer:$deployer_group" -- "$target"; fi; done
  else
    current_application="$app"; load_container_parameters "$root/release/.windowstolinux-container-parameters"
    for spec in "${container_volumes[@]}"; do source="${spec%%:*}"; status="$(cat -- "$rollback/$source.present")"
      if "$container_engine" volume inspect "$source" >/dev/null 2>&1; then target="$($container_engine volume inspect --format '{{.Mountpoint}}' "$source")"; find "$target" -mindepth 1 -delete
        if [ "$status" = 1 ]; then cp -a --no-preserve=ownership -- "$rollback/$source/." "$target/"; else "$container_engine" volume rm "$source" >/dev/null; fi; fi; done
  fi
}
restore_recover_component() {
  [ "$#" -eq 3 ] || reject restore-recover-arguments
  local candidate="$1" token="$2" component="$3" root kind app owner release previous snapshot_token
  root="$(restore_component_root "$candidate" "$component")"; [ -e "$root" ] || { printf 'RECOVERED=1\nPREVIOUS=0\n'; return; }
  kind="$(cat -- "$root/kind")"; mapfile -t identity < "$root/identity"; app="${identity[0]}"; owner="${identity[1]}"; release="${identity[2]}"
  restore_stop_candidate "$candidate" "$token" "$component" >/dev/null || true
  if [ -f "$root/previous" ]; then mapfile -t saved < "$root/previous"; previous="${saved[0]}"; snapshot_token="${saved[1]}"
    restore_restore_managed_data "$root" "$app" "$kind"
    if [ "$kind" = deployment ]; then
      if [ "$previous" = 1 ]; then rollback_deployment "$app" "$release" "$owner" "$snapshot_token"; else rollback_deployment_first "$app" "$release" "$owner"; fi
    else if [ "$previous" = 1 ]; then rollback_container "$app" "$release" "$owner" "$snapshot_token"; else rollback_container_first "$app" "$release" "$owner"; fi; fi
  fi
  printf 'RECOVERED=1\nPREVIOUS=%s\nAPP=%s\n' "${previous:-0}" "$app"
}

assert_deployment_or_ordinary_current_or_empty() {
  local app="$1" manifest="$2" root current
  root="$(app_root "$app")"
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then
    [ -L "$root/current" ] || reject current-not-link
    current="$(readlink -f -- "$root/current")"
    if [ -e "$current/.windowstolinux-deployment-parameters" ] || [ -L "$current/.windowstolinux-deployment-parameters" ]; then
      assert_deployment_current_or_empty "$app" "$manifest"
      previous_kind=deployment
    else
      [ ! -e "$current/.windowstolinux-container-parameters" ] && [ ! -L "$current/.windowstolinux-container-parameters" ] \
        || reject ordinary-container-release
      assert_current_or_empty "$app" "$manifest"
      previous_kind=ordinary
    fi
  else
    assert_deployment_current_or_empty "$app" "$manifest"
    previous_kind=none
  fi
}
snapshot_deployment() {
  [ "$#" -eq 2 ] || reject snapshot-deployment-arguments
  local app="$1" manifest="$2"
  require_app "$app"; require_digest "$manifest"
  initialise_controlled_roots
  assert_application_root_or_absent "$app"
  assert_deployment_or_ordinary_current_or_empty "$app" "$manifest"
  if [ "$previous_present" -eq 0 ]; then
    printf 'PREVIOUS=0\n'
    return
  fi
  local token snapshot unit
  token="$(cat /proc/sys/kernel/random/uuid)"; require_snapshot_token "$token"
  snapshot="$(snapshot_root "$app" "$token")"; unit="$(unit_path "$app")"
  [ ! -e "$snapshot" ] && [ ! -L "$snapshot" ] || reject snapshot-exists
  install -d -o root -g root -m 700 -- "$snapshot"
  printf '%s\n' "$previous_path" > "$snapshot/current-path"
  printf '%s\n' "$previous_kind" > "$snapshot/kind"
  install -o root -g root -m 600 -- "$unit" "$snapshot/unit"
  if [ "$previous_kind" = deployment ]; then
    install -o root -g root -m 600 -- "$previous_path/.windowstolinux-deployment-parameters" "$snapshot/deployment-parameters"
  fi
  systemctl is-enabled "$(unit_name "$app")" > "$snapshot/enabled" 2>/dev/null || true
  if [ "$previous_running" -eq 1 ]; then printf 'active\n' > "$snapshot/runtime"; else printf 'inactive\n' > "$snapshot/runtime"; fi
  chown root:root -- "$snapshot/current-path" "$snapshot/kind" "$snapshot/enabled" "$snapshot/runtime"
  chmod 600 -- "$snapshot/current-path" "$snapshot/kind" "$snapshot/enabled" "$snapshot/runtime"
  if [ "$previous_kind" = deployment ]; then
    chown root:root -- "$snapshot/deployment-parameters"
    chmod 600 -- "$snapshot/deployment-parameters"
  fi
  printf '%s\n' "$token" > "$(app_root "$app")/.migration-snapshot"
  chmod 400 -- "$(app_root "$app")/.migration-snapshot"
  printf 'SNAPSHOT_TOKEN=%s\n' "$token"
  printf 'PREVIOUS=1\n'
  printf 'PREVIOUS_RUNNING=%s\n' "$previous_running"
}
rollback_deployment() {
  [ "$#" -eq 4 ] || reject rollback-deployment-arguments
  local app="$1" candidate_digest="$2" manifest="$3" token="$4"
  require_app "$app"; require_digest "$candidate_digest"; require_digest "$manifest"; require_snapshot_token "$token"
  local root releases candidate snapshot unit expected previous previous_digest previous_runtime previous_kind
  root="$(app_root "$app")"; releases="$root/releases"; candidate="$releases/$candidate_digest"
  snapshot="$(snapshot_root "$app" "$token")"; unit="$(unit_path "$app")"
  assert_root_owned_directory "$root"; assert_root_owned_directory "$releases"; assert_root_owned_directory "$snapshot"
  for file in current-path kind unit runtime enabled; do assert_root_owned_regular "$snapshot/$file"; done
  previous_kind="$(cat -- "$snapshot/kind")"
  [ "$previous_kind" = deployment ] || [ "$previous_kind" = ordinary ] || reject snapshot-kind
  previous="$(cat -- "$snapshot/current-path")"; previous_digest="${previous##*/}"; require_digest "$previous_digest"
  [ "$previous" = "$releases/$previous_digest" ] || reject snapshot-current
  previous_runtime="$(cat -- "$snapshot/runtime")"; [ "$previous_runtime" = active ] || [ "$previous_runtime" = inactive ] || reject snapshot-runtime
  assert_root_owned_directory "$previous"; assert_root_owned_regular "$previous/.windowstolinux-owner"
  [ "$(cat -- "$previous/.windowstolinux-owner")" = "$manifest" ] || reject previous-owner
  if [ "$previous_kind" = deployment ]; then
    assert_root_owned_regular "$snapshot/deployment-parameters"
    current_application="$app"; load_deployment_parameters "$snapshot/deployment-parameters"
    expected="$(expected_deployment_unit_digest "$app" "${deployment_runtime_parameters[@]}")"
  else
    expected="$(expected_unit_digest "$app" "$snapshot/unit")"
  fi
  [ "$(sha256sum -- "$snapshot/unit" | awk '{print $1}')" = "$expected" ] || reject snapshot-unit
  if [ -e "$candidate" ] || [ -L "$candidate" ]; then
    assert_root_owned_directory "$candidate"; assert_root_owned_regular "$candidate/.windowstolinux-owner"
    [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
    assert_root_owned_regular "$candidate/.windowstolinux-deployment-parameters"
  fi
  stop_application_unit "$app"
  if [ "$previous_kind" = deployment ]; then prepare_managed_data_bindings "$previous/source"; fi
  ln -sfnT -- "$previous" "$root/current"
  install -o root -g root -m 644 -- "$snapshot/unit" "$unit"
  systemctl daemon-reload
  if [ "$(cat -- "$snapshot/enabled")" = enabled ]; then systemctl enable "$(unit_name "$app")"; else systemctl disable "$(unit_name "$app")"; fi
  if [ "$previous_runtime" = active ]; then systemctl start "$(unit_name "$app")"; else stop_application_unit "$app"; fi
  if [ "$candidate" != "$previous" ] && { [ -e "$candidate" ] || [ -L "$candidate" ]; }; then rm -rf --one-file-system -- "$candidate"; fi
  rm -rf --one-file-system -- "$snapshot"
  printf 'ROLLED_BACK=1\n'
}
rollback_deployment_first() {
  [ "$#" -eq 3 ] || reject rollback-deployment-first-arguments
  local app="$1" candidate_digest="$2" manifest="$3"
  require_app "$app"; require_digest "$candidate_digest"; require_digest "$manifest"
  local root releases candidate unit expected
  root="$(app_root "$app")"; releases="$root/releases"; candidate="$releases/$candidate_digest"; unit="$(unit_path "$app")"
  assert_root_owned_directory "$root"; assert_root_owned_directory "$releases"
  if [ ! -e "$candidate" ] && [ ! -L "$candidate" ]; then
    [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject rollback-current
    [ ! -e "$unit" ] && [ ! -L "$unit" ] || reject current-unit
    rmdir -- "$releases" "$root" 2>/dev/null || true
    printf 'ROLLED_BACK=1\n'
    return
  fi
  if [ ! -e "$candidate/.windowstolinux-owner" ] && [ ! -L "$candidate/.windowstolinux-owner" ]; then
    [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject rollback-current
    [ ! -e "$unit" ] && [ ! -L "$unit" ] || reject current-unit
    assert_root_owned_directory "$candidate"
    rm -rf --one-file-system -- "$candidate"
    rmdir -- "$releases" "$root" 2>/dev/null || true
    printf 'ROLLED_BACK=1\n'
    return
  fi
  assert_root_owned_directory "$candidate"; assert_root_owned_regular "$candidate/.windowstolinux-owner"
  [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
  current_application="$app"; load_deployment_parameters "$candidate/.windowstolinux-deployment-parameters"
  expected="$(expected_deployment_unit_digest "$app" "${deployment_runtime_parameters[@]}")"
  if [ -e "$unit" ] || [ -L "$unit" ]; then
    assert_root_owned_regular "$unit"; [ "$(sha256sum -- "$unit" | awk '{print $1}')" = "$expected" ] || reject current-unit
    stop_application_unit "$app"; rm -f -- "$unit"
  fi
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then rm -f -- "$root/current"; fi
  rm -rf --one-file-system -- "$candidate"
  systemctl daemon-reload
  printf 'ROLLED_BACK=1\n'
}
lifecycle_deployment() {
  [ "$#" -eq 3 ] || reject lifecycle-deployment-arguments
  local app="$1" action="$2" manifest="$3"
  require_app "$app"; require_digest "$manifest"
  case "$action" in start|stop|restart|enable|disable) ;; *) reject lifecycle-action ;; esac
  assert_deployment_current_or_empty "$app" "$manifest"
  [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
  [ "$application_mode" = DAEMON ] || reject application-lifecycle-not-applicable
  if [ "$action" = stop ]; then stop_requested_application_unit "$app"
  else systemctl "$action" "$(unit_name "$app")"; fi
  printf 'LIFECYCLE=%s\n' "$action"
}
observe_deployment() {
  [ "$#" -eq 2 ] || reject observe-deployment-arguments
  local app="$1" manifest="$2" running enabled
  require_app "$app"; require_digest "$manifest"
  assert_deployment_current_or_empty "$app" "$manifest"
  [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
  if [ "$application_mode" = ON_DEMAND ]; then
    printf 'OWNER=1\nINSTALLED=1\nRUNNING=0\nENABLED=not-applicable\nQUERY_OK=1\nActiveState=inactive\nMainPID=0\n'; return
  fi
  if systemctl is-active --quiet "$(unit_name "$app")"; then running=1; else running=0; fi
  enabled="$(systemctl is-enabled "$(unit_name "$app")" 2>/dev/null || true)"
  printf 'OWNER=1\nRUNNING=%s\nENABLED=%s\n' "$running" "$enabled"
  observe_application_unit "$(unit_name "$app")"
}

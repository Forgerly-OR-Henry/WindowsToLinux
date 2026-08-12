retain_releases() {
  [ "$#" -eq 2 ] || reject retain-arguments
  local app="$1"
  local manifest="$2"
  require_app "$app"
  require_digest "$manifest"
  local root releases current current_digest
  root="$(app_root "$app")"
  releases="$root/releases"
  assert_root_owned_directory "$root"
  assert_root_owned_directory "$releases"
  [ -L "$root/current" ] || reject current-not-link
  current="$(readlink -f -- "$root/current")"
  current_digest="${current##*/}"
  require_digest "$current_digest"
  [ "$current" = "$releases/$current_digest" ] || reject current-path
  mapfile -t releases_by_age < <(find -P "$releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' | LC_ALL=C sort -nr | awk '{print $2}')
  retained_noncurrent=0
  for release_digest in "${releases_by_age[@]}"; do
    require_digest "$release_digest"
    release="$releases/$release_digest"
    assert_root_owned_directory "$release"
    assert_root_owned_regular "$release/.windowstolinux-owner"
    [ "$(cat -- "$release/.windowstolinux-owner")" = "$manifest" ] || reject release-owner
    if [ "$release" = "$current" ]; then
      continue
    elif [ "$retained_noncurrent" -lt 2 ]; then
      retained_noncurrent=$((retained_noncurrent + 1))
    else
      rm -rf --one-file-system -- "$release"
    fi
  done
  printf 'RETAINED=%s\n' "$((retained_noncurrent + 1))"
}
rollback_previous() {
  [ "$#" -eq 4 ] || reject rollback-arguments
  local app="$1"
  local candidate_digest="$2"
  local manifest="$3"
  local token="$4"
  require_app "$app"
  require_digest "$candidate_digest"
  require_digest "$manifest"
  require_snapshot_token "$token"
  local root releases candidate snapshot unit expected previous previous_digest previous_runtime
  root="$(app_root "$app")"
  releases="$root/releases"
  candidate="$releases/$candidate_digest"
  snapshot="$(snapshot_root "$app" "$token")"
  unit="$(unit_path "$app")"
  expected="$(expected_unit_digest "$app")"
  assert_root_owned_directory "$root"
  assert_root_owned_directory "$releases"
  assert_root_owned_directory "$snapshot"
  for file in current-path unit runtime enabled; do assert_root_owned_regular "$snapshot/$file"; done
  previous="$(cat -- "$snapshot/current-path")"
  previous_digest="${previous##*/}"
  require_digest "$previous_digest"
  [ "$previous" = "$releases/$previous_digest" ] || reject snapshot-current
  previous_runtime="$(cat -- "$snapshot/runtime")"
  [ "$previous_runtime" = active ] || [ "$previous_runtime" = inactive ] || reject snapshot-runtime
  assert_root_owned_directory "$previous"
  assert_root_owned_regular "$previous/.windowstolinux-owner"
  [ "$(cat -- "$previous/.windowstolinux-owner")" = "$manifest" ] || reject previous-owner
  [ "$(sha256sum -- "$snapshot/unit" | awk '{print $1}')" = "$expected" ] || reject snapshot-unit
  if [ -e "$candidate" ] || [ -L "$candidate" ]; then
    assert_root_owned_directory "$candidate"
    assert_root_owned_regular "$candidate/.windowstolinux-owner"
    [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
  fi
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then
    [ -L "$root/current" ] || reject current-not-link
    current="$(readlink -f -- "$root/current")"
    [ "$current" = "$candidate" ] || [ "$current" = "$previous" ] || reject rollback-current
  fi
  assert_root_owned_regular "$unit"
  [ "$(sha256sum -- "$unit" | awk '{print $1}')" = "$expected" ] || reject current-unit
  systemctl stop "$(unit_name "$app")" || true
  ln -sfnT -- "$previous" "$root/current"
  install -o root -g root -m 644 -- "$snapshot/unit" "$unit"
  systemctl daemon-reload
  if [ "$(cat -- "$snapshot/enabled")" = enabled ]; then
    systemctl enable "$(unit_name "$app")"
  else
    systemctl disable "$(unit_name "$app")"
  fi
  if [ "$previous_runtime" = active ]; then
    systemctl start "$(unit_name "$app")"
  else
    systemctl stop "$(unit_name "$app")" || true
  fi
  if [ -e "$candidate" ] || [ -L "$candidate" ]; then
    rm -rf --one-file-system -- "$candidate"
  fi
  rm -rf --one-file-system -- "$snapshot"
  printf 'ROLLED_BACK=1\n'
}
rollback_first() {
  [ "$#" -eq 3 ] || reject rollback-first-arguments
  local app="$1"
  local candidate_digest="$2"
  local manifest="$3"
  require_app "$app"
  require_digest "$candidate_digest"
  require_digest "$manifest"
  local root releases candidate unit expected
  root="$(app_root "$app")"
  releases="$root/releases"
  candidate="$releases/$candidate_digest"
  unit="$(unit_path "$app")"
  expected="$(expected_unit_digest "$app")"
  assert_root_owned_directory "$root"
  assert_root_owned_directory "$releases"
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then
    [ -L "$root/current" ] || reject current-not-link
    current="$(readlink -f -- "$root/current")"
    [ "$current" = "$candidate" ] || reject rollback-current
    assert_root_owned_regular "$root/current/.windowstolinux-owner"
    [ "$(cat -- "$root/current/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
  fi
  if [ -e "$unit" ] || [ -L "$unit" ]; then
    assert_root_owned_regular "$unit"
    [ "$(sha256sum -- "$unit" | awk '{print $1}')" = "$expected" ] || reject current-unit
    systemctl stop "$(unit_name "$app")" || true
    rm -f -- "$unit"
  fi
  if [ -e "$root/current" ] || [ -L "$root/current" ]; then
    rm -f -- "$root/current"
  fi
  if [ -e "$candidate" ] || [ -L "$candidate" ]; then
    assert_root_owned_directory "$candidate"
    assert_root_owned_regular "$candidate/.windowstolinux-owner"
    [ "$(cat -- "$candidate/.windowstolinux-owner")" = "$manifest" ] || reject candidate-owner
    rm -rf --one-file-system -- "$candidate"
  fi
  systemctl daemon-reload
  printf 'ROLLED_BACK=1\n'
}
lifecycle() {
  [ "$#" -eq 3 ] || reject lifecycle-arguments
  local app="$1"
  local action="$2"
  local manifest="$3"
  require_app "$app"
  require_digest "$manifest"
  case "$action" in
    start|stop|restart|enable|disable) ;;
    *) reject lifecycle-action ;;
  esac
  assert_current_or_empty "$app" "$manifest"
  [ "$previous_present" -eq 1 ] || reject lifecycle-unmanaged
  systemctl "$action" "$(unit_name "$app")"
  printf 'LIFECYCLE=%s\n' "$action"
}

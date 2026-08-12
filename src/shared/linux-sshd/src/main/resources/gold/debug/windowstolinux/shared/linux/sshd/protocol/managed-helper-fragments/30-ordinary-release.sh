seal_candidate_artifact() {
  local app="$1"
  local candidate_id="$2"
  local expected_digest="$3"
  local candidate mutable source target
  candidate="$(candidate_root "$candidate_id")"
  mutable="$candidate/mutable"
  assert_candidate_for_deployer "$candidate"
  [ -d "$mutable" ] && [ ! -L "$mutable" ] || reject mutable-workspace
  # The candidate parent is root-owned, so the deployment account cannot replace
  # the mutable entry while ownership is being transferred.
  chown root:root -- "$mutable"
  chmod 700 -- "$mutable"
  [ -z "$(find -P "$mutable" -xdev -type l -print -quit)" ] || reject mutable-symlink
  [ -z "$(find -P "$mutable" -xdev ! -type f ! -type d -print -quit)" ] || reject mutable-special-file
  source="$mutable/source"
  target="$source/target"
  [ -d "$source" ] && [ ! -L "$source" ] || reject source-directory
  [ -d "$target" ] && [ ! -L "$target" ] || reject target-directory
  mapfile -d '' -t jars < <(find -P "$target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' -print0 | LC_ALL=C sort -z)
  [ "${#jars[@]}" -eq 1 ] || reject artifact-count
  artifact="${jars[0]}"
  [ ! -L "$artifact" ] || reject artifact-symlink
  [ "$(sha256sum -- "$artifact" | awk '{print $1}')" = "$expected_digest" ] || reject artifact-digest
  sealed_artifact="$artifact"
}
publish_release() {
  [ "$#" -eq 4 ] || reject publish-arguments
  local app="$1"
  local candidate_id="$2"
  local artifact_digest="$3"
  local manifest="$4"
  require_app "$app"
  require_candidate "$app" "$candidate_id"
  require_digest "$artifact_digest"
  require_digest "$manifest"
  initialise_controlled_roots
  assert_application_root_or_absent "$app"
  assert_current_or_empty "$app" "$manifest"
  seal_candidate_artifact "$app" "$candidate_id" "$artifact_digest"
  local root releases release unit tmp
  root="$(app_root "$app")"
  releases="$root/releases"
  release="$releases/$artifact_digest"
  unit="$(unit_path "$app")"
  install -d -o root -g root -m 755 -- "$root" "$releases"
  [ ! -e "$release" ] && [ ! -L "$release" ] || reject release-exists
  install -d -o root -g root -m 755 -- "$release"
  install -o root -g root -m 555 -- "$sealed_artifact" "$release/app.jar"
  [ "$(sha256sum -- "$release/app.jar" | awk '{print $1}')" = "$artifact_digest" ] || reject release-digest
  printf '%s\n' "$manifest" > "$release/.windowstolinux-owner"
  chown root:root -- "$release/.windowstolinux-owner"
  chmod 444 -- "$release/.windowstolinux-owner"
  if [ "$previous_present" -eq 1 ]; then
    systemctl stop "$(unit_name "$app")"
    stopped=0
    for attempt in {1..20}; do
      if ! systemctl is-active --quiet "$(unit_name "$app")" \
        && [ "$(systemctl show --value --property MainPID "$(unit_name "$app")")" = 0 ]; then
        stopped=1
        break
      fi
      sleep 0.25
    done
    [ "$stopped" -eq 1 ] || reject stop-incomplete
  fi
  ln -sfnT -- "$release" "$root/current"
  tmp="$(mktemp /etc/systemd/system/.windowstolinux-managed.XXXXXX)"
  trap 'rm -f -- "$tmp"' EXIT
  render_unit "$app" > "$tmp"
  install -o root -g root -m 644 -- "$tmp" "$unit"
  rm -f -- "$tmp"
  trap - EXIT
  systemctl daemon-reload
  systemctl start "$(unit_name "$app")"
  printf 'PUBLISHED=1\n'
}

seal_deployment_tree() {
  local app="$1"
  local candidate_id="$2"
  local release="$3"
  shift 3
  parse_deployment_inputs "$@"
  set -- "${deployment_remaining_arguments[@]}"
  [ "$#" -ge 1 ] || reject runtime-arguments
  local kind="$1"
  shift
  local candidate mutable source artifact
  candidate="$(candidate_root "$candidate_id")"
  mutable="$candidate/mutable"
  source="$mutable/source"
  assert_candidate_for_deployer "$candidate"
  [ -d "$mutable" ] && [ ! -L "$mutable" ] || reject mutable-workspace
  chown root:root -- "$mutable"
  chmod 700 -- "$mutable"
  [ -d "$source" ] && [ ! -L "$source" ] || reject source-directory
  [ -z "$(find -P "$source" -xdev -type l -print -quit)" ] || reject source-symlink
  [ -z "$(find -P "$source" -xdev ! -type f ! -type d -print -quit)" ] || reject source-special-file
  install -d -o root -g root -m 755 -- "$release"
  cp -a --no-preserve=ownership -- "$source" "$release/source"
  chown -R root:root -- "$release/source"
  [ -z "$(find -P "$release/source" -xdev -type l -print -quit)" ] || reject release-symlink
  case "$kind" in
    gradle)
      mapfile -d '' -t artifacts < <(find -P "$release/source/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' -print0 | LC_ALL=C sort -z)
      [ "${#artifacts[@]}" -eq 1 ] || reject artifact-count
      install -o root -g root -m 555 -- "${artifacts[0]}" "$release/app.jar"
      ;;
    java)
      [ "$#" -ge 1 ] || reject runtime-arguments
      require_relative_path "$1"
      artifact="$release/source/$1"
      [ -f "$artifact" ] && [ ! -L "$artifact" ] || reject java-artifact
      install -o root -g root -m 555 -- "$artifact" "$release/app.jar"
      ;;
    node)
      [ -f "$release/source/package.json" ] || reject node-package
      ;;
    python)
      [ -x "$release/source/.venv/bin/python" ] || reject python-venv
      ;;
    static)
      [ "$#" -ge 1 ] || reject runtime-arguments
      require_relative_path "$1"
      [ -d "$release/source/$1" ] && [ ! -L "$release/source/$1" ] || reject static-output
      ;;
    *) reject runtime-kind ;;
  esac
}
publish_deployment() {
  [ "$#" -ge 7 ] || reject publish-deployment-arguments
  local app="$1" candidate_id="$2" release_digest="$3" manifest="$4"
  shift 4
  require_app "$app"; require_candidate "$app" "$candidate_id"; require_digest "$release_digest"; require_digest "$manifest"
  initialise_controlled_roots
  assert_application_root_or_absent "$app"
  render_deployment_unit "$app" "$@" >/dev/null
  assert_deployment_current_or_empty "$app" "$manifest"
  local root releases release unit tmp
  root="$(app_root "$app")"; releases="$root/releases"; release="$releases/$release_digest"; unit="$(unit_path "$app")"
  install -d -o root -g root -m 755 -- "$root" "$releases"
  [ ! -e "$release" ] && [ ! -L "$release" ] || reject release-exists
  seal_deployment_tree "$app" "$candidate_id" "$release" "$@"
  printf '%s\n' "$manifest" > "$release/.windowstolinux-owner"
  chown root:root -- "$release/.windowstolinux-owner"; chmod 444 -- "$release/.windowstolinux-owner"
  save_deployment_parameters "$release/.windowstolinux-deployment-parameters" "$@"
  if [ "$previous_present" -eq 1 ]; then systemctl stop "$(unit_name "$app")"; fi
  ln -sfnT -- "$release" "$root/current"
  tmp="$(mktemp /etc/systemd/system/.windowstolinux-managed.XXXXXX)"
  trap 'rm -f -- "$tmp"' EXIT
  render_deployment_unit "$app" "$@" > "$tmp"
  install -o root -g root -m 644 -- "$tmp" "$unit"
  rm -f -- "$tmp"; trap - EXIT
  systemctl daemon-reload
  systemctl start "$(unit_name "$app")"
  printf 'PUBLISHED=1\n'
}

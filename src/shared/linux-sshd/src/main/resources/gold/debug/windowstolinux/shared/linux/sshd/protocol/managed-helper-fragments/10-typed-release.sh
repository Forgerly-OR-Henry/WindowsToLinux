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
  [ "$kind" != gradle ] || reject legacy-gradle-write
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
    springboot)
      [ "$#" -eq 1 ] || reject runtime-arguments
      local artifact_root manifest
      case "$1" in
        GRADLE_WRAPPER) artifact_root="$release/source/build/libs" ;;
        MAVEN_WRAPPER|MAVEN) artifact_root="$release/source/target" ;;
        *) reject springboot-build-tool ;;
      esac
      mapfile -d '' -t artifacts < <(find -P "$artifact_root" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print0 | LC_ALL=C sort -z)
      [ "${#artifacts[@]}" -eq 1 ] || reject artifact-count
      manifest="$(mktemp -d "$mutable/.manifest.XXXXXX")"
      (cd "$manifest" && jar xf "${artifacts[0]}" META-INF/MANIFEST.MF)
      tr -d '\r' < "$manifest/META-INF/MANIFEST.MF" | grep -Eq '^Main-Class: org\.springframework\.boot\.loader\.(launch\.)?JarLauncher$' \
        || reject springboot-launcher
      rm -rf --one-file-system -- "$manifest"
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
    go|rust)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_safe_name "$2"
      artifact="$release/source/.w2l/bin/$2"
      [ -x "$artifact" ] && [ ! -L "$artifact" ] || reject advanced-binary
      ;;
    dotnet)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_safe_name "$2"
      artifact="$release/source/.w2l/dotnet/$2.dll"
      [ -f "$artifact" ] && [ ! -L "$artifact" ] || reject dotnet-artifact
      ;;
    kotlin)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_java_main "$3"
      [ -d "$release/source/.w2l/kotlin/lib" ] || reject kotlin-distribution
      [ -n "$(find "$release/source/.w2l/kotlin/lib" -maxdepth 1 -type f -name '*.jar' -print -quit)" ] \
        || reject kotlin-distribution
      ;;
    php)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [ "$2" = public ] && [ "$3" = public/index.php ] || reject php-runtime
      [ -f "$release/source/public/index.php" ] && [ -f "$release/source/vendor/autoload.php" ] || reject php-artifact
      ;;
    ruby)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [ "$2" = bundle ] && [ "$3" = config.ru ] || reject ruby-runtime
      [ -f "$release/source/config.ru" ] && [ -d "$release/source/vendor/bundle" ] || reject ruby-artifact
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
  assert_deployment_or_ordinary_current_or_empty "$app" "$manifest"
  local root releases release unit tmp
  root="$(app_root "$app")"; releases="$root/releases"; release="$releases/$release_digest"; unit="$(unit_path "$app")"
  if [ "$previous_present" -eq 1 ] && [ "$previous_kind" = deployment ] \
      && [ "$previous_path" = "$release" ] && [ "$previous_running" -eq 1 ]; then
    printf 'PUBLISHED=1\n'
    return
  fi
  install -d -o root -g root -m 755 -- "$root" "$releases"
  if [ -e "$release" ] || [ -L "$release" ]; then
    [ ! -e "$root/current" ] && [ ! -L "$root/current" ] || reject release-exists
    [ ! -e "$unit" ] && [ ! -L "$unit" ] || reject release-exists
    assert_root_owned_directory "$release"
    [ ! -e "$release/.windowstolinux-owner" ] && [ ! -L "$release/.windowstolinux-owner" ] || reject release-exists
    rm -rf --one-file-system -- "$release"
  fi
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

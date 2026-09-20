copy_sealed_source() {
  local source="$1" target="$2" file relative
  cp -R --no-preserve=all -- "$source" "$target"
  chown -hR root:root -- "$target"
  setfacl -R -b -- "$target"
  find -P "$target" -xdev -type d -exec setfacl -k -- {} +
  find -P "$target" -xdev -type d -exec chmod 755 -- {} +
  find -P "$target" -xdev -type f -exec chmod 644 -- {} +
  while IFS= read -r -d '' file; do
    relative="${file#"$source"/}"
    chmod 755 -- "$target/$relative"
  done < <(find -P "$source" -xdev -type f -perm /111 -print0)
}
seal_deployment_tree() {
  local app="$1"
  local candidate_id="$2"
  local release="$3"
  shift 3
  current_application="$app"
  parse_deployment_inputs "$@"
  [ "$runtime_identity_policy" = SYSTEMD_STATIC ] || reject runtime-identity-policy
  parse_managed_data_bindings "${deployment_remaining_arguments[@]}"
  parse_toolchain_binding "${managed_data_remaining_arguments[@]}"
  set -- "${toolchain_remaining_arguments[@]}"
  [ "$#" -ge 1 ] || reject runtime-arguments
  local kind="$1"
  shift
  [ "$kind" != gradle ] || reject legacy-gradle-write
  local candidate mutable source artifact
  candidate="$(candidate_root "$candidate_id")"
  mutable="$candidate/mutable"
  source="$mutable/source"
  assert_candidate_for_deployer "$candidate"
  assert_sealed_build "$app" "$candidate_id"
  [ -d "$mutable" ] && [ ! -L "$mutable" ] || reject mutable-workspace
  assert_root_owned_directory "$mutable"
  [ -d "$source" ] && [ ! -L "$source" ] || reject source-directory
  [ -z "$(find -P "$source" -xdev -type l -print -quit)" ] || reject source-symlink
  [ -z "$(find -P "$source" -xdev ! -type f ! -type d -print -quit)" ] || reject source-special-file
  install -d -o root -g root -m 755 -- "$release"
  copy_sealed_source "$source" "$release/source"
  [ -z "$(find -P "$release/source" -xdev -type l -print -quit)" ] || reject release-symlink
  local build_source="$release/source${application_builddir:+/$application_builddir}"
  assert_root_owned_directory "$build_source"
  case "$kind" in
    springboot)
      [ "$#" -eq 1 ] || reject runtime-arguments
      local artifact_root manifest
      case "$1" in
        GRADLE_WRAPPER) artifact_root="$build_source/build/libs" ;;
        MAVEN_WRAPPER|MAVEN) artifact_root="$build_source/target" ;;
        *) reject springboot-build-tool ;;
      esac
      mapfile -d '' -t artifacts < <(find -P "$artifact_root" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print0 | LC_ALL=C sort -z)
      [ "${#artifacts[@]}" -eq 1 ] || reject artifact-count
      manifest="$(mktemp -d "$release/.manifest.XXXXXX")"
      (cd "$manifest" && PATH="$toolchain_path" jar xf "${artifacts[0]}" META-INF/MANIFEST.MF)
      tr -d '\r' < "$manifest/META-INF/MANIFEST.MF" | grep -Eq '^Main-Class: org\.springframework\.boot\.loader\.(launch\.)?JarLauncher$' \
        || reject springboot-launcher
      rm -rf --one-file-system -- "$manifest"
      install -o root -g root -m 555 -- "${artifacts[0]}" "$release/app.jar"
      ;;
    java|javasource)
      [ "$#" -ge 1 ] || reject runtime-arguments
      require_relative_path "$1"
      if [ "$kind" = javasource ]; then
        [ "$1" = .w2l/java/app.jar ] || reject java-source-artifact
        [ -f "$build_source/windowstolinux-java.properties" ] || reject java-source-metadata
      fi
      artifact="$build_source/$1"
      [ -f "$artifact" ] && [ ! -L "$artifact" ] || reject java-artifact
      install -o root -g root -m 555 -- "$artifact" "$release/app.jar"
      ;;
    node)
      [ "$#" -eq 2 ] || reject runtime-arguments
      [ -f "$build_source/package.json" ] || reject node-package
      case "$2" in
        NPM) [ -f "$build_source/package-lock.json" ] || reject node-lock ;;
        PNPM) [ -f "$build_source/pnpm-lock.yaml" ] || reject node-lock ;;
        YARN) [ -f "$build_source/yarn.lock" ] || reject node-lock ;;
        *) reject node-package-manager ;;
      esac
      ;;
    python)
      [ "$#" -eq 3 ] || reject runtime-arguments
      [ -x "$build_source/.venv/bin/python" ] || reject python-venv
      case "$3" in
        PYTHON_STDLIB) [ -f "$build_source/pyproject.toml" ] || reject python-project ;;
        PIP_LOCKED) [ -f "$build_source/requirements.lock" ] || reject python-lock ;;
        PIPENV_LOCKED) [ -f "$build_source/Pipfile.lock" ] || reject python-lock ;;
        POETRY_LOCKED) [ -f "$build_source/poetry.lock" ] || reject python-lock ;;
        UV_LOCKED) [ -f "$build_source/uv.lock" ] || reject python-lock ;;
        *) reject python-build-tool ;;
      esac
      ;;
    static)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_relative_path "$1"
      [ -d "$build_source/$1" ] && [ ! -L "$build_source/$1" ] || reject static-output
      case "$3" in
        STATIC_SITE_BUILD) ;;
        NPM) [ -f "$build_source/package-lock.json" ] || reject static-lock ;;
        PNPM) [ -f "$build_source/pnpm-lock.yaml" ] || reject static-lock ;;
        YARN) [ -f "$build_source/yarn.lock" ] || reject static-lock ;;
        *) reject static-build-tool ;;
      esac
      ;;
    go|rust)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_safe_name "$2"
      artifact="$build_source/.w2l/bin/$2"
      [ -x "$artifact" ] && [ ! -L "$artifact" ] || reject ecosystem-binary
      ;;
    dotnet)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_safe_name "$2"
      artifact="$build_source/.w2l/dotnet/$2.dll"
      [ -f "$artifact" ] && [ ! -L "$artifact" ] || reject dotnet-artifact
      ;;
    kotlin)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_java_main "$3"
      case "$4" in
        GRADLE_KOTLIN_WRAPPER) [ -f "$build_source/build.gradle.kts" ] || reject kotlin-gradle-metadata ;;
        KOTLINC) [ -f "$build_source/windowstolinux-kotlin.properties" ] || reject kotlin-compiler-metadata ;;
        *) reject kotlin-build-tool ;;
      esac
      [ -d "$build_source/.w2l/kotlin/lib" ] || reject kotlin-distribution
      [ -n "$(find "$build_source/.w2l/kotlin/lib" -maxdepth 1 -type f -name '*.jar' -print -quit)" ] \
        || reject kotlin-distribution
      ;;
    php)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_relative_path "$3"; [[ "$3" = *.php ]] || reject php-runtime
      [ -f "$build_source/$3" ] && [ -f "$build_source/vendor/autoload.php" ] || reject php-artifact
      ;;
    phpcli)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_relative_path "$3"; [[ "$3" = *.php ]] || reject php-runtime
      [ -f "$build_source/$3" ] || reject php-artifact
      [ ! -e "$build_source/composer.json" ] && [ ! -e "$build_source/composer.lock" ] || reject php-cli-dependency
      ;;
    ruby)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [ "$2" = bundle ] || reject ruby-runtime; require_relative_path "$3"
      [ -f "$build_source/$3" ] && [ -d "$build_source/vendor/bundle" ] || reject ruby-artifact
      ;;
    rubycli)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [ "$2" = source ] || reject ruby-artifact
      require_relative_path "$3"
      [[ "$3" = *.rb ]] || reject ruby-entrypoint
      [ -f "$build_source/$3" ] || reject ruby-artifact
      [ ! -e "$build_source/Gemfile" ] && [ ! -e "$build_source/Gemfile.lock" ] || reject ruby-cli-dependency
      ;;
    cmake)
      [ "$#" -eq 3 ] || reject runtime-arguments
      [ "$1" = w2l-release ] || reject cmake-preset
      require_safe_name "$2"
      [ "$3" = "$2" ] || reject cmake-artifact
      artifact="$build_source/.w2l/bin/$2"
      [ -x "$artifact" ] && [ ! -L "$artifact" ] || reject cmake-artifact
      [ -f "$artifact.ldd" ] && [ ! -L "$artifact.ldd" ] || reject cmake-dependencies
      ! grep -F 'not found' "$artifact.ldd" || reject cmake-dependencies
      ;;
    *) reject runtime-kind ;;
  esac
  if [ -n "$toolchain_binding" ]; then
    install -o root -g root -m 444 -- "/usr/local/lib/windowstolinux/toolchains/bindings/$toolchain_binding" "$release/.windowstolinux-toolchains"
  fi
  prepare_managed_data_bindings "$release/source"
  application_assert_inputs "$app"
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
      && [ "$previous_path" = "$release" ]; then
    [ "$(printf '%s\n' "$@" | sha256sum | awk '{print $1}')" = "$(sha256sum -- "$release/.windowstolinux-deployment-parameters" | awk '{print $1}')" ] || reject release-parameters-changed
    if [ "$application_mode" = DAEMON ] && [ "$previous_running" = 0 ]; then systemctl start "$(unit_name "$app")"; fi
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
  if [ "$previous_present" -eq 1 ]; then stop_application_unit "$app"; fi
  seal_deployment_tree "$app" "$candidate_id" "$release" "$@"
  printf '%s\n' "$manifest" > "$release/.windowstolinux-owner"
  chown root:root -- "$release/.windowstolinux-owner"; chmod 444 -- "$release/.windowstolinux-owner"
  save_deployment_parameters "$release/.windowstolinux-deployment-parameters" "$@"
  ln -sfnT -- "$release" "$root/current"
  tmp="$(mktemp /etc/systemd/system/.windowstolinux-managed.XXXXXX)"
  trap 'rm -f -- "$tmp"' EXIT
  render_deployment_unit "$app" "$@" > "$tmp"
  install -o root -g root -m 644 -- "$tmp" "$unit"
  rm -f -- "$tmp"; trap - EXIT
  systemctl daemon-reload
  if [ "$application_mode" = DAEMON ]; then systemctl start "$(unit_name "$app")"; else systemctl disable "$(unit_name "$app")"; fi
  printf 'PUBLISHED=1\n'
}

parse_toolchain_binding() {
  toolchain_binding=''
  toolchain_path=/usr/local/bin:/usr/bin:/bin
  toolchain_java=/usr/local/lib/windowstolinux/java-21
  toolchain_python=/usr/bin/python3
  toolchain_dotnet=/usr/bin/dotnet
  toolchain_php=php
  toolchain_ruby=ruby
  if [ "${1:-}" = tools-v1 ]; then
    [ "$#" -ge 3 ] || reject toolchain-arguments
    toolchain_binding="$2"; shift 2; require_digest "$toolchain_binding"
    local registry=/usr/local/lib/windowstolinux/toolchains record header eco purpose declaration source constraint requested version directory origin evidence digest extra
    record="$registry/bindings/$toolchain_binding"
    assert_root_owned_directory "$registry"; assert_root_owned_directory "$registry/bindings"
    assert_root_owned_regular "$record"
    [ "$(stat -c '%s' -- "$record")" -le 65536 ] || reject toolchain-binding-size
    [ "$(sha256sum -- "$record" | awk '{print $1}')" = "$toolchain_binding" ] || reject toolchain-binding-digest
    IFS= read -r header < "$record"
    [[ "$header" =~ ^WTL-TOOLS-1$'\t'[A-Za-z0-9._-]{1,96}$ ]] || reject toolchain-binding-format
    toolchain_path=''
    while IFS=$'\t' read -r eco purpose declaration source constraint requested version directory origin evidence digest extra; do
      [ -z "$extra" ] || reject toolchain-binding-fields
      case "$origin" in MANAGED|SYSTEM) ;; *) reject toolchain-origin ;; esac
      [[ "$directory" =~ ^/usr/local/lib/windowstolinux/toolchains/versions/[a-z]+-[0-9a-f]{64}$ ]] || reject toolchain-directory
      [[ "$version" =~ ^[0-9][A-Za-z0-9.+_-]{0,95}$ ]] || reject toolchain-version
      require_digest "$digest"
      assert_root_owned_directory "$directory"
      assert_root_owned_regular "$directory/.w2l-toolchain.json"
      case "$eco" in
        JAVA) toolchain_java="$directory/bin/java" ;;
        NODE|GO|RUST|KOTLIN) ;;
        PYTHON) toolchain_python="$directory/bin/python3" ;;
        DOTNET) toolchain_dotnet="$directory/dotnet" ;;
        PHP) toolchain_php="$directory/bin/php" ;;
        RUBY) toolchain_ruby="$directory/bin/ruby" ;;
        *) reject toolchain-ecosystem ;;
      esac
      if [ "$eco" = DOTNET ]; then toolchain_path+="$directory:"; else toolchain_path+="$directory/bin:"; fi
    done < <(tail -n +2 -- "$record")
    [ -n "$toolchain_path" ] || reject toolchain-binding-empty
    toolchain_path+=/usr/local/bin:/usr/bin:/bin
    prepare_official_toolchains verify "$toolchain_binding" || reject toolchain-binding-changed
  fi
  toolchain_remaining_arguments=("$@")
}
require_bound_version() {
  local ecosystem="$1" version="$2" legacy_pattern="$3"
  [[ "$version" =~ ^[0-9][A-Za-z0-9.+_-]{0,95}$ ]] || reject runtime-version
  if [ -n "${toolchain_binding:-}" ]; then
    grep -q "^${ecosystem}"$'\t' "/usr/local/lib/windowstolinux/toolchains/bindings/$toolchain_binding" || reject runtime-toolchain-missing
  else [[ "$version" =~ $legacy_pattern ]] || reject legacy-runtime-version; fi
}
render_ecosystem_runtime_command() {
  local kind="$1"
  local root="$2"
  shift 2
  case "$kind" in
    go)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_bound_version GO "$1" '^1\.(22|23|24)$'
      require_safe_name "$2"
      [ "$3" = main.go ] || reject go-entrypoint
      ecosystem_runtime_command_result="$root/current/source/.w2l/bin/$2"
      ;;
    rust)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_bound_version RUST "$1" '^1\.(7[5-9]|8[0-9]|9[0-9])([.][0-9]+)?$'
      require_safe_name "$2"
      [ "$3" = src/main.rs ] || reject rust-entrypoint
      ecosystem_runtime_command_result="$root/current/source/.w2l/bin/$2"
      ;;
    dotnet)
      [ "$#" -eq 3 ] || reject runtime-arguments
      require_bound_version DOTNET "$1" '^(8|9)\.0([.][0-9]+)?$'
      require_safe_name "$2"
      [ "$3" = "$2.dll" ] || reject dotnet-entrypoint
      ecosystem_runtime_command_result="$toolchain_dotnet $root/current/source/.w2l/dotnet/$2.dll"
      ;;
    kotlin)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_bound_version JAVA "$1" '^21$'
      require_safe_name "$2"
      require_java_main "$3"
      case "$4" in GRADLE_KOTLIN_WRAPPER|KOTLINC) ;; *) reject kotlin-build-tool ;; esac
      ecosystem_runtime_command_result="$toolchain_java -cp $root/current/source/.w2l/kotlin/lib/* $3"
      ;;
    php)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_bound_version PHP "$1" '^8\.(2|3|4)$'
      [ "$2" = public ] || reject php-document-root
      [ "$3" = public/index.php ] || reject php-router
      local php_port="${deployment_service_port_override:-$4}" php_bind="${deployment_bind_address_override:-0.0.0.0}"
      require_service_port "$php_port"; [ "$php_bind" = 0.0.0.0 ] || [ "$php_bind" = 127.0.0.1 ] || reject runtime-bind-address
      ecosystem_runtime_command_result="/usr/bin/env PATH=$toolchain_path $toolchain_php -S $php_bind:$php_port -t $root/current/source/public $root/current/source/public/index.php"
      ;;
    phpcli)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_bound_version PHP "$1" '^8\.(2|3|4)$'
      [ "$2" = public ] || reject php-document-root
      [ "$3" = public/index.php ] || reject php-router
      local phpcli_port="${deployment_service_port_override:-$4}" phpcli_bind="${deployment_bind_address_override:-0.0.0.0}"
      require_service_port "$phpcli_port"; [ "$phpcli_bind" = 0.0.0.0 ] || [ "$phpcli_bind" = 127.0.0.1 ] || reject runtime-bind-address
      ecosystem_runtime_command_result="/usr/bin/env PATH=$toolchain_path $toolchain_php -n -S $phpcli_bind:$phpcli_port -t $root/current/source/public $root/current/source/public/index.php"
      ;;
    ruby)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_bound_version RUBY "$1" '^3\.(2|3|4)([.][0-9]+)?$'
      [ "$2" = bundle ] || reject ruby-artifact
      [ "$3" = config.ru ] || reject ruby-entrypoint
      local ruby_port="${deployment_service_port_override:-$4}" ruby_bind="${deployment_bind_address_override:-0.0.0.0}"
      require_service_port "$ruby_port"; [ "$ruby_bind" = 0.0.0.0 ] || [ "$ruby_bind" = 127.0.0.1 ] || reject runtime-bind-address
      ecosystem_runtime_command_result="/usr/bin/env PATH=$toolchain_path bundle exec rackup --server webrick --host $ruby_bind --port $ruby_port $root/current/source/config.ru"
      if [ -n "${toolchain_binding:-}" ]; then
        ecosystem_runtime_command_result="/usr/bin/env PATH=$root/current/source/.w2l/bundler/bin:$toolchain_path GEM_HOME=$root/current/source/.w2l/bundler GEM_PATH=$root/current/source/.w2l/bundler: bundle exec rackup --server webrick --host $ruby_bind --port $ruby_port $root/current/source/config.ru"
      fi
      ;;
    rubycli)
      [ "$#" -eq 4 ] || reject runtime-arguments
      require_bound_version RUBY "$1" '^3\.(2|3|4)([.][0-9]+)?$'
      [ "$2" = source ] || reject ruby-artifact
      require_relative_path "$3"
      [[ "$3" = *.rb ]] || reject ruby-entrypoint
      local rubycli_port="${deployment_service_port_override:-$4}"
      require_service_port "$rubycli_port"
      ecosystem_runtime_command_result="/usr/bin/env PATH=$toolchain_path PORT=$rubycli_port $toolchain_ruby $root/current/source/$3"
      ;;
    cmake)
      [ "$#" -eq 3 ] || reject runtime-arguments
      [ "$1" = w2l-release ] || reject cmake-preset
      require_safe_name "$2"
      [ "$3" = "$2" ] || reject cmake-artifact
      ecosystem_runtime_command_result="$root/current/source/.w2l/bin/$2"
      ;;
    *) reject runtime-kind ;;
  esac
}
expected_deployment_unit_digest() {
  render_deployment_unit "$@" | sha256sum | awk '{print $1}'
}
save_deployment_parameters() {
  local target="$1"
  shift
  [ "$#" -ge 1 ] || reject runtime-arguments
  printf '%s\n' "$@" > "$target"
  chown root:root -- "$target"
  chmod 444 -- "$target"
}
load_deployment_parameters() {
  local source="$1"
  assert_root_owned_regular "$source"
  legacy_runtime_user=
  local saved_unit="${source%/*}/unit"
  if [ ! -f "$saved_unit" ]; then saved_unit="$(unit_path "$current_application")"; fi
  if [ -f "$saved_unit" ]; then
    assert_root_owned_regular "$saved_unit"
    legacy_runtime_user="$(sed -n 's/^User=//p' "$saved_unit")"
    [[ "$legacy_runtime_user" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || reject legacy-runtime-user
  fi
  mapfile -t deployment_runtime_parameters < "$source"
  [ "${#deployment_runtime_parameters[@]}" -ge 1 ] || reject runtime-parameters
  render_deployment_unit "$current_application" "${deployment_runtime_parameters[@]}" >/dev/null
}

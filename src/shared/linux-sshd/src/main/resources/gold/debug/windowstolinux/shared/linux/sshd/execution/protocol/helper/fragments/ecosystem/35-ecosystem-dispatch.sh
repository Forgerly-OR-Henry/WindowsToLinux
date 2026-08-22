render_ecosystem_runtime_command() {
  local kind="$1"
  local root="$2"
  shift 2
  case "$kind" in
    go)
      [ "$#" -eq 3 ] || reject runtime-arguments
      [[ "$1" =~ ^1\.(22|23|24)$ ]] || reject go-version
      require_safe_name "$2"
      [ "$3" = main.go ] || reject go-entrypoint
      ecosystem_runtime_command_result="$root/current/source/.w2l/bin/$2"
      ;;
    rust)
      [ "$#" -eq 3 ] || reject runtime-arguments
      [[ "$1" =~ ^1\.(7[5-9]|8[0-9]|9[0-9])([.][0-9]+)?$ ]] || reject rust-version
      require_safe_name "$2"
      [ "$3" = src/main.rs ] || reject rust-entrypoint
      ecosystem_runtime_command_result="$root/current/source/.w2l/bin/$2"
      ;;
    dotnet)
      [ "$#" -eq 3 ] || reject runtime-arguments
      [[ "$1" =~ ^(8|9)\.0([.][0-9]+)?$ ]] || reject dotnet-version
      require_safe_name "$2"
      [ "$3" = "$2.dll" ] || reject dotnet-entrypoint
      ecosystem_runtime_command_result="/usr/bin/dotnet $root/current/source/.w2l/dotnet/$2.dll"
      ;;
    kotlin)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [ "$1" = 21 ] || reject kotlin-java-version
      require_safe_name "$2"
      require_java_main "$3"
      case "$4" in GRADLE_KOTLIN_WRAPPER|KOTLINC) ;; *) reject kotlin-build-tool ;; esac
      ecosystem_runtime_command_result="/usr/local/lib/windowstolinux/java-21 -cp $root/current/source/.w2l/kotlin/lib/* $3"
      ;;
    php)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [[ "$1" =~ ^8\.(2|3|4)$ ]] || reject php-version
      [ "$2" = public ] || reject php-document-root
      [ "$3" = public/index.php ] || reject php-router
      local php_port="${deployment_service_port_override:-$4}" php_bind="${deployment_bind_address_override:-0.0.0.0}"
      require_service_port "$php_port"; [ "$php_bind" = 0.0.0.0 ] || [ "$php_bind" = 127.0.0.1 ] || reject runtime-bind-address
      ecosystem_runtime_command_result="/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin php -S $php_bind:$php_port -t $root/current/source/public $root/current/source/public/index.php"
      ;;
    phpcli)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [[ "$1" =~ ^8\.(2|3|4)$ ]] || reject php-version
      [ "$2" = public ] || reject php-document-root
      [ "$3" = public/index.php ] || reject php-router
      local phpcli_port="${deployment_service_port_override:-$4}" phpcli_bind="${deployment_bind_address_override:-0.0.0.0}"
      require_service_port "$phpcli_port"; [ "$phpcli_bind" = 0.0.0.0 ] || [ "$phpcli_bind" = 127.0.0.1 ] || reject runtime-bind-address
      ecosystem_runtime_command_result="/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin php -n -S $phpcli_bind:$phpcli_port -t $root/current/source/public $root/current/source/public/index.php"
      ;;
    ruby)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [[ "$1" =~ ^3\.(2|3|4)([.][0-9]+)?$ ]] || reject ruby-version
      [ "$2" = bundle ] || reject ruby-artifact
      [ "$3" = config.ru ] || reject ruby-entrypoint
      local ruby_port="${deployment_service_port_override:-$4}" ruby_bind="${deployment_bind_address_override:-0.0.0.0}"
      require_service_port "$ruby_port"; [ "$ruby_bind" = 0.0.0.0 ] || [ "$ruby_bind" = 127.0.0.1 ] || reject runtime-bind-address
      ecosystem_runtime_command_result="/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin bundle exec rackup --server webrick --host $ruby_bind --port $ruby_port $root/current/source/config.ru"
      ;;
    rubycli)
      [ "$#" -eq 4 ] || reject runtime-arguments
      [[ "$1" =~ ^3\.(2|3|4)([.][0-9]+)?$ ]] || reject ruby-version
      [ "$2" = source ] || reject ruby-artifact
      require_relative_path "$3"
      [[ "$3" = *.rb ]] || reject ruby-entrypoint
      local rubycli_port="${deployment_service_port_override:-$4}"
      require_service_port "$rubycli_port"
      ecosystem_runtime_command_result="/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin PORT=$rubycli_port ruby $root/current/source/$3"
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

package gold.debug.windowstolinux.mod.deployment.linux.probe;

/**
 * Renders read-only language and build-tool capability probes. / 渲染只读语言与构建工具能力探测。
 */
public final class DeploymentEcosystemCapabilityProbe {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentEcosystemCapabilityProbe() {
    }

    /**
     * Renders the complete language and toolchain portion of the platform probe. / 渲染平台探测中的完整语言与工具链部分。
     *
     * @return platform tool checks text / 平台工具检查集合文本
     */
    public static String platformToolChecks() {
        return """
            kotlin_compiler="$(probe_command kotlinc || true)"
            if probe_command java >/dev/null 2>&1; then
              printf 'JAVA_MAJORS='
              probe_run java -XshowSettings:properties -version 2>&1 | /usr/bin/awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}'
            else
              printf 'JAVA_MAJORS=\\n'
            fi
            if probe_command node >/dev/null 2>&1; then
              printf 'NODE_MAJORS='; probe_run node --version | /usr/bin/sed -E 's/^v([0-9]+).*/\\1/'
            else
              printf 'NODE_MAJORS=\\n'
            fi
            if probe_command npm >/dev/null 2>&1; then printf 'NPM=1\\n'; else printf 'NPM=0\\n'; fi
            if probe_command mvn >/dev/null 2>&1; then printf 'MAVEN=1\\n'; else printf 'MAVEN=0\\n'; fi
            if probe_command python3 >/dev/null 2>&1; then printf 'PYTHON3=1\\n'; else printf 'PYTHON3=0\\n'; fi
            printf 'PYTHON_VERSIONS='
            first_python=1
            for executable in $(compgen -c | LC_ALL=C /usr/bin/sort -u | /usr/bin/grep -E '^python[0-9]+[.][0-9]+$'); do
              version="${executable#python}"
              if probe_command "python$version" >/dev/null 2>&1 && probe_run "python$version" -I -S -B -m venv --help >/dev/null 2>&1; then
                if [ "$first_python" -eq 0 ]; then printf ','; fi
                printf '%s' "$version"
                first_python=0
              fi
            done
            printf '\\n'
            if probe_command go >/dev/null 2>&1; then
              printf 'SERVICE_GO='; probe_run go version | /usr/bin/sed -E 's/^go version go([^ ]+).*/\\1/'
            else printf 'SERVICE_GO=\\n'; fi
            if probe_command rustc >/dev/null 2>&1 && probe_command cargo >/dev/null 2>&1; then
              printf 'SERVICE_RUST='; probe_run rustc --version | /usr/bin/awk '{print $2}'
            else printf 'SERVICE_RUST=\\n'; fi
            # --list-sdks is handled by the native host without loading the CLI SDK or its first-run initialization.
            # https://github.com/dotnet/runtime/blob/v10.0.0/src/native/corehost/fxr/fx_muxer.cpp#L952
            if probe_command dotnet >/dev/null 2>&1; then
              printf 'SERVICE_DOTNET='; probe_run dotnet --list-sdks | /usr/bin/awk '{print $1}' | /usr/bin/tail -n 1
            else printf 'SERVICE_DOTNET=\\n'; fi
            if probe_command java >/dev/null 2>&1 && probe_run java -version 2>&1 | /usr/bin/grep -Eq 'version "21([.]|")'; then
              printf 'SERVICE_KOTLIN=21\\n'
            else printf 'SERVICE_KOTLIN=\\n'; fi
            if probe_command php >/dev/null 2>&1; then
              printf 'SERVICE_PHP='; probe_run php -n -r 'printf("%d.%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);'; printf '\\n'
            else printf 'SERVICE_PHP=\\n'; fi
            if probe_command ruby >/dev/null 2>&1; then
              printf 'SERVICE_RUBY='; probe_run ruby -e 'print RUBY_VERSION'; printf '\\n'
            else printf 'SERVICE_RUBY=\\n'; fi
            if probe_command java >/dev/null 2>&1; then
              printf 'TOOL_JAVA='; probe_run java -XshowSettings:properties -version 2>&1 | /usr/bin/awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}'
            else printf 'TOOL_JAVA=\\n'; fi
            if probe_command javac >/dev/null 2>&1; then printf 'TOOL_JAVAC='; probe_run javac -version 2>&1 | /usr/bin/awk '{print $2}'; else printf 'TOOL_JAVAC=\\n'; fi
            if probe_command jar >/dev/null 2>&1; then
              printf 'TOOL_JAR='
              { probe_run jar --version 2>/dev/null || { if probe_command javac >/dev/null 2>&1; then probe_run javac -version 2>&1; fi; }; } | /usr/bin/awk '{print $2} END {if (NR == 0) print ""}'
            else printf 'TOOL_JAR=\\n'; fi
            if probe_command node >/dev/null 2>&1; then printf 'TOOL_NODE='; probe_run node --version | /usr/bin/sed -E 's/^v//'; else printf 'TOOL_NODE=\\n'; fi
            # Root-level project configuration is not executed merely to observe a package manager version.
            package_probe_safe=1
            for config in /.mvn /.npmrc /.yarnrc /.yarnrc.yml /.pnpmfile.cjs /pnpm-workspace.yaml /package.json; do
              if [ -e "$config" ] || [ -L "$config" ]; then package_probe_safe=0; break; fi
            done
            for tool in npm pnpm yarn mvn; do
              case "$tool" in npm) key=NPM ;; pnpm) key=PNPM ;; yarn) key=YARN ;; mvn) key=MAVEN ;; esac
              version=
              if [ "$package_probe_safe" -eq 1 ] && probe_command "$tool" >/dev/null 2>&1; then
                if [ "$tool" = mvn ]; then version="$(probe_run mvn -version 2>/dev/null | /usr/bin/awk 'NR==1 {print $3}')" || version=
                else version="$(probe_run "$tool" --version 2>/dev/null)" || version=; fi
              fi
              printf 'TOOL_%s=%s\\n' "$key" "$version"
            done
            printf 'TOOL_PYTHON='
            first_python=1
            for executable in $(compgen -c | LC_ALL=C /usr/bin/sort -u | /usr/bin/grep -E '^python[0-9]+[.][0-9]+$'); do
              version="${executable#python}"
              if probe_command "python$version" >/dev/null 2>&1; then
                if [ "$first_python" -eq 0 ]; then printf ','; fi
                printf '%s' "$version"; first_python=0
              fi
            done
            printf '\\n'
            printf 'TOOL_PIP='
            if probe_command python3 >/dev/null 2>&1; then
              pip_version="$(probe_run python3 -I -S -B - <<'WTL_PIP_METADATA'
            from importlib.metadata import Distribution
            import re
            import site
            try:
                versions = {distribution.version for distribution in Distribution.discover(name="pip", path=site.getsitepackages())}
                if len(versions) == 1:
                    version = versions.pop()
                    if isinstance(version, str) and re.fullmatch(r"[0-9][0-9A-Za-z.+_-]{0,127}", version):
                        print(version)
            except (OSError, ValueError):
                pass
            WTL_PIP_METADATA
              )" || pip_version=
              printf '%s\\n' "$pip_version"
            else printf '\\n'; fi
            if probe_command pipenv >/dev/null 2>&1; then printf 'TOOL_PIPENV='; probe_run pipenv --version | /usr/bin/awk '{print $3}'; else printf 'TOOL_PIPENV=\\n'; fi
            if probe_command poetry >/dev/null 2>&1; then printf 'TOOL_POETRY='; probe_run poetry --version | /usr/bin/sed -E 's/.* ([0-9][0-9A-Za-z.+_-]*).*/\\1/'; else printf 'TOOL_POETRY=\\n'; fi
            if probe_command uv >/dev/null 2>&1; then printf 'TOOL_UV='; probe_run uv --version | /usr/bin/awk '{print $2}'; else printf 'TOOL_UV=\\n'; fi
            if probe_command go >/dev/null 2>&1; then printf 'TOOL_GO='; probe_run go version | /usr/bin/sed -E 's/^go version go([^ ]+).*/\\1/'; else printf 'TOOL_GO=\\n'; fi
            if probe_command rustc >/dev/null 2>&1; then printf 'TOOL_RUSTC='; probe_run rustc --version | /usr/bin/awk '{print $2}'; else printf 'TOOL_RUSTC=\\n'; fi
            if probe_command cargo >/dev/null 2>&1; then printf 'TOOL_CARGO='; probe_run cargo --version | /usr/bin/awk '{print $2}'; else printf 'TOOL_CARGO=\\n'; fi
            if probe_command dotnet >/dev/null 2>&1; then printf 'TOOL_DOTNET='; probe_run dotnet --list-sdks | /usr/bin/awk '{print $1}' | /usr/bin/tail -n 1; else printf 'TOOL_DOTNET=\\n'; fi
            if [ -n "$kotlin_compiler" ]; then printf 'TOOL_KOTLINC='; "$kotlin_compiler" -version 2>&1 | /usr/bin/sed -nE 's/.*kotlinc-jvm ([0-9][0-9A-Za-z.+_-]*).*/\\1/p'; else printf 'TOOL_KOTLINC=\\n'; fi
            if probe_command php >/dev/null 2>&1; then printf 'TOOL_PHP='; probe_run php -n -r 'printf("%d.%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);'; printf '\\n'; else printf 'TOOL_PHP=\\n'; fi
            if probe_command composer >/dev/null 2>&1; then printf 'TOOL_COMPOSER='; probe_run composer --no-plugins --no-scripts --no-cache --no-interaction --version 2>/dev/null | /usr/bin/awk '{for(i=1;i<=NF;i++) if ($i ~ /^[0-9]+[.][0-9]+/) {print $i; exit}}'; else printf 'TOOL_COMPOSER=\\n'; fi
            if probe_command ruby >/dev/null 2>&1; then printf 'TOOL_RUBY='; probe_run ruby -e 'print RUBY_VERSION'; printf '\\n'; else printf 'TOOL_RUBY=\\n'; fi
            if probe_command bundle >/dev/null 2>&1; then printf 'TOOL_BUNDLER='; probe_run bundle --version | /usr/bin/awk '{print $NF}'; else printf 'TOOL_BUNDLER=\\n'; fi
            if probe_command cmake >/dev/null 2>&1; then printf 'TOOL_CMAKE='; probe_run cmake --version | /usr/bin/awk 'NR==1 {print $3}'; else printf 'TOOL_CMAKE=\\n'; fi
            if probe_command ninja >/dev/null 2>&1; then printf 'TOOL_NINJA='; probe_run ninja --version; else printf 'TOOL_NINJA=\\n'; fi
            if probe_command cc >/dev/null 2>&1; then printf 'TOOL_C_COMPILER='; probe_run cc -dumpfullversion -dumpversion | /usr/bin/head -n 1; else printf 'TOOL_C_COMPILER=\\n'; fi
            if probe_command c++ >/dev/null 2>&1; then printf 'TOOL_CPP_COMPILER='; probe_run c++ -dumpfullversion -dumpversion | /usr/bin/head -n 1; else printf 'TOOL_CPP_COMPILER=\\n'; fi
            """;
    }

}

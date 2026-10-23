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
            kotlin_compiler="$(command -v kotlinc || true)"
            if command -v java >/dev/null 2>&1; then
              printf 'JAVA_MAJORS='
              java -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}'
            else
              printf 'JAVA_MAJORS=\\n'
            fi
            if command -v node >/dev/null 2>&1; then
              printf 'NODE_MAJORS='; node --version | sed -E 's/^v([0-9]+).*/\\1/'
            else
              printf 'NODE_MAJORS=\\n'
            fi
            if command -v npm >/dev/null 2>&1; then printf 'NPM=1\\n'; else printf 'NPM=0\\n'; fi
            if command -v mvn >/dev/null 2>&1; then printf 'MAVEN=1\\n'; else printf 'MAVEN=0\\n'; fi
            if command -v python3 >/dev/null 2>&1; then printf 'PYTHON3=1\\n'; else printf 'PYTHON3=0\\n'; fi
            printf 'PYTHON_VERSIONS='
            first_python=1
            for executable in $(compgen -c | LC_ALL=C sort -u | grep -E '^python[0-9]+[.][0-9]+$'); do
              version="${executable#python}"
              if command -v "python$version" >/dev/null 2>&1 && "python$version" -I -S -B -m venv --help >/dev/null 2>&1; then
                if [ "$first_python" -eq 0 ]; then printf ','; fi
                printf '%s' "$version"
                first_python=0
              fi
            done
            printf '\\n'
            if command -v go >/dev/null 2>&1; then
              printf 'SERVICE_GO='; go version | sed -E 's/^go version go([^ ]+).*/\\1/'
            else printf 'SERVICE_GO=\\n'; fi
            if command -v rustc >/dev/null 2>&1 && command -v cargo >/dev/null 2>&1; then
              printf 'SERVICE_RUST='; rustc --version | awk '{print $2}'
            else printf 'SERVICE_RUST=\\n'; fi
            # --list-sdks is handled by the native host without loading the CLI SDK or its first-run initialization.
            # https://github.com/dotnet/runtime/blob/v10.0.0/src/native/corehost/fxr/fx_muxer.cpp#L952
            if command -v dotnet >/dev/null 2>&1; then
              printf 'SERVICE_DOTNET='; dotnet --list-sdks | awk '{print $1}' | tail -n 1
            else printf 'SERVICE_DOTNET=\\n'; fi
            if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq 'version "21([.]|")'; then
              printf 'SERVICE_KOTLIN=21\\n'
            else printf 'SERVICE_KOTLIN=\\n'; fi
            if command -v php >/dev/null 2>&1; then
              printf 'SERVICE_PHP='; php -n -r 'printf("%d.%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);'; printf '\\n'
            else printf 'SERVICE_PHP=\\n'; fi
            if command -v ruby >/dev/null 2>&1; then
              printf 'SERVICE_RUBY='; ruby -e 'print RUBY_VERSION'; printf '\\n'
            else printf 'SERVICE_RUBY=\\n'; fi
            if command -v java >/dev/null 2>&1; then
              printf 'TOOL_JAVA='; java -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}'
            else printf 'TOOL_JAVA=\\n'; fi
            if command -v javac >/dev/null 2>&1; then printf 'TOOL_JAVAC='; javac -version 2>&1 | awk '{print $2}'; else printf 'TOOL_JAVAC=\\n'; fi
            if command -v jar >/dev/null 2>&1; then
              printf 'TOOL_JAR='
              { jar --version 2>/dev/null || { if command -v javac >/dev/null 2>&1; then javac -version 2>&1; fi; }; } | awk '{print $2} END {if (NR == 0) print ""}'
            else printf 'TOOL_JAR=\\n'; fi
            if command -v node >/dev/null 2>&1; then printf 'TOOL_NODE='; node --version | sed -E 's/^v//'; else printf 'TOOL_NODE=\\n'; fi
            # Root-level project configuration is not executed merely to observe a package manager version.
            package_probe_safe=1
            for config in /.mvn /.npmrc /.yarnrc /.yarnrc.yml /.pnpmfile.cjs /pnpm-workspace.yaml /package.json; do
              if [ -e "$config" ] || [ -L "$config" ]; then package_probe_safe=0; break; fi
            done
            for tool in npm pnpm yarn mvn; do
              case "$tool" in npm) key=NPM ;; pnpm) key=PNPM ;; yarn) key=YARN ;; mvn) key=MAVEN ;; esac
              version=
              if [ "$package_probe_safe" -eq 1 ] && command -v "$tool" >/dev/null 2>&1; then
                if [ "$tool" = mvn ]; then version="$(mvn -version 2>/dev/null | awk 'NR==1 {print $3}')" || version=
                else version="$("$tool" --version 2>/dev/null)" || version=; fi
              fi
              printf 'TOOL_%s=%s\\n' "$key" "$version"
            done
            printf 'TOOL_PYTHON='
            first_python=1
            for executable in $(compgen -c | LC_ALL=C sort -u | grep -E '^python[0-9]+[.][0-9]+$'); do
              version="${executable#python}"
              if command -v "python$version" >/dev/null 2>&1; then
                if [ "$first_python" -eq 0 ]; then printf ','; fi
                printf '%s' "$version"; first_python=0
              fi
            done
            printf '\\n'
            printf 'TOOL_PIP='
            if command -v python3 >/dev/null 2>&1; then
              pip_version="$(python3 -I -S -B - <<'WTL_PIP_METADATA'
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
            if command -v pipenv >/dev/null 2>&1; then printf 'TOOL_PIPENV='; pipenv --version | awk '{print $3}'; else printf 'TOOL_PIPENV=\\n'; fi
            if command -v poetry >/dev/null 2>&1; then printf 'TOOL_POETRY='; poetry --version | sed -E 's/.* ([0-9][0-9A-Za-z.+_-]*).*/\\1/'; else printf 'TOOL_POETRY=\\n'; fi
            if command -v uv >/dev/null 2>&1; then printf 'TOOL_UV='; uv --version | awk '{print $2}'; else printf 'TOOL_UV=\\n'; fi
            if command -v go >/dev/null 2>&1; then printf 'TOOL_GO='; go version | sed -E 's/^go version go([^ ]+).*/\\1/'; else printf 'TOOL_GO=\\n'; fi
            if command -v rustc >/dev/null 2>&1; then printf 'TOOL_RUSTC='; rustc --version | awk '{print $2}'; else printf 'TOOL_RUSTC=\\n'; fi
            if command -v cargo >/dev/null 2>&1; then printf 'TOOL_CARGO='; cargo --version | awk '{print $2}'; else printf 'TOOL_CARGO=\\n'; fi
            if command -v dotnet >/dev/null 2>&1; then printf 'TOOL_DOTNET='; dotnet --list-sdks | awk '{print $1}' | tail -n 1; else printf 'TOOL_DOTNET=\\n'; fi
            if [ -x "$kotlin_compiler" ]; then printf 'TOOL_KOTLINC='; "$kotlin_compiler" -version 2>&1 | sed -nE 's/.*kotlinc-jvm ([0-9][0-9A-Za-z.+_-]*).*/\\1/p'; else printf 'TOOL_KOTLINC=\\n'; fi
            if command -v php >/dev/null 2>&1; then printf 'TOOL_PHP='; php -n -r 'printf("%d.%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);'; printf '\\n'; else printf 'TOOL_PHP=\\n'; fi
            if command -v composer >/dev/null 2>&1; then printf 'TOOL_COMPOSER='; composer --no-plugins --no-scripts --no-cache --no-interaction --version 2>/dev/null | awk '{for(i=1;i<=NF;i++) if ($i ~ /^[0-9]+[.][0-9]+/) {print $i; exit}}'; else printf 'TOOL_COMPOSER=\\n'; fi
            if command -v ruby >/dev/null 2>&1; then printf 'TOOL_RUBY='; ruby -e 'print RUBY_VERSION'; printf '\\n'; else printf 'TOOL_RUBY=\\n'; fi
            if command -v bundle >/dev/null 2>&1; then printf 'TOOL_BUNDLER='; bundle --version | awk '{print $NF}'; else printf 'TOOL_BUNDLER=\\n'; fi
            if command -v cmake >/dev/null 2>&1; then printf 'TOOL_CMAKE='; cmake --version | awk 'NR==1 {print $3}'; else printf 'TOOL_CMAKE=\\n'; fi
            if command -v ninja >/dev/null 2>&1; then printf 'TOOL_NINJA='; ninja --version; else printf 'TOOL_NINJA=\\n'; fi
            if command -v cc >/dev/null 2>&1; then printf 'TOOL_C_COMPILER='; cc -dumpfullversion -dumpversion | head -n 1; else printf 'TOOL_C_COMPILER=\\n'; fi
            if command -v c++ >/dev/null 2>&1; then printf 'TOOL_CPP_COMPILER='; c++ -dumpfullversion -dumpversion | head -n 1; else printf 'TOOL_CPP_COMPILER=\\n'; fi
            """;
    }

}

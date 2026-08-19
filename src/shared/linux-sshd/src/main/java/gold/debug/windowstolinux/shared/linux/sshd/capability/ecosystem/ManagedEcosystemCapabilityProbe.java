package gold.debug.windowstolinux.shared.linux.sshd.capability.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.protocol.helper.ManagedHelperBundle;

/** Renders read-only language and build-tool capability probes. / 渲染只读语言与构建工具能力探测。 */
public final class ManagedEcosystemCapabilityProbe {
    private ManagedEcosystemCapabilityProbe() {
    }

    /** Renders the managed Java environment shared by capability probes. / 渲染能力探测共享的受管 Java 环境。 */
    public static String managedJavaEnvironment() {
        return "managed_java=" + quote(ManagedHelperBundle.JAVA_RUNTIME_PATH) + "\n";
    }

    /** Renders the Java and Maven checks used by the compact host probe. / 渲染简要主机探测使用的 Java 与 Maven 检查。 */
    public static String hostToolChecks() {
        return """
                if [ -x "$managed_java" ] && "$managed_java" -version 2>&1 | grep -Eq '(^|[^0-9])21[.]'; then printf 'JAVA21=1\\n'; else printf 'JAVA21=0\\n'; fi
                if command -v mvn >/dev/null 2>&1; then printf 'MAVEN=1\\n'; else printf 'MAVEN=0\\n'; fi
                """;
    }

    /** Renders the complete language and toolchain portion of the platform probe. / 渲染平台探测中的完整语言与工具链部分。 */
    public static String platformToolChecks() {
        return """
                if [ -x "$managed_java" ]; then
                  printf 'JAVA_MAJORS='
                  "$managed_java" -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}'
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
                for version in 3.10 3.11 3.12 3.13; do
                  if command -v "python$version" >/dev/null 2>&1 && "python$version" -m venv --help >/dev/null 2>&1; then
                    if [ "$first_python" -eq 0 ]; then printf ','; fi
                    printf '%s' "$version"
                    first_python=0
                  fi
                done
                printf '\\n'
                if command -v go >/dev/null 2>&1; then
                  printf 'SERVICE_GO='; go version | sed -E 's/^go version go(1[.][0-9]+).*/\\1/'
                else printf 'SERVICE_GO=\\n'; fi
                if command -v rustc >/dev/null 2>&1 && command -v cargo >/dev/null 2>&1; then
                  printf 'SERVICE_RUST='; rustc --version | awk '{print $2}'
                else printf 'SERVICE_RUST=\\n'; fi
                if command -v dotnet >/dev/null 2>&1; then
                  printf 'SERVICE_DOTNET='; dotnet --version
                else printf 'SERVICE_DOTNET=\\n'; fi
                if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq 'version "21([.]|")'; then
                  printf 'SERVICE_KOTLIN=21\\n'
                else printf 'SERVICE_KOTLIN=\\n'; fi
                if command -v php >/dev/null 2>&1 && command -v composer >/dev/null 2>&1; then
                  printf 'SERVICE_PHP='; php -r 'printf("%d.%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);'; printf '\\n'
                else printf 'SERVICE_PHP=\\n'; fi
                if command -v ruby >/dev/null 2>&1 && command -v bundle >/dev/null 2>&1; then
                  printf 'SERVICE_RUBY='; ruby -e 'print RUBY_VERSION'; printf '\\n'
                else printf 'SERVICE_RUBY=\\n'; fi
                """;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

package gold.debug.windowstolinux.shared.linux.sshd.capability;

/**
 * Fixed read-only shell program for collecting typed deployment distribution, container, and CPU facts.
 *
 * <p>用于采集部署发行版、容器和 CPU 事实的固定只读 Shell 程序。
 */
public final class PlatformCapabilityProbeScript {
    private PlatformCapabilityProbeScript() {
    }

    /**
     * Renders the implementation-owned capability probe with no caller-provided shell fragment.
     *
     * <p>渲染由实现持有的能力探测，不接受调用方提供的 Shell 片段。
     *
     * @return fixed capability probe / 固定能力探测
     */
    public static String render() {
        return """
                set -eu
                export LC_ALL=C
                . /etc/os-release
                printf 'DISTRO_ID=%s\\n' "${ID:-unknown}"
                printf 'DISTRO_VARIANT=%s\\n' "${VARIANT_ID:-}"
                printf 'VERSION=%s\\n' "${VERSION_ID:-unknown}"
                printf 'ARCH='; uname -m
                if command -v apt-get >/dev/null 2>&1; then printf 'PACKAGE_MANAGER=apt\\n';
                elif command -v dnf >/dev/null 2>&1; then printf 'PACKAGE_MANAGER=dnf\\n';
                else printf 'PACKAGE_MANAGER=unknown\\n'; fi
                if command -v systemctl >/dev/null 2>&1; then printf 'SYSTEMD=1\\n'; else printf 'SYSTEMD=0\\n'; fi
                if command -v docker >/dev/null 2>&1; then printf 'DOCKER_CLIENT=1\\n'; else printf 'DOCKER_CLIENT=0\\n'; fi
                if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then printf 'DOCKER_OPERATIONAL=1\\n'; else printf 'DOCKER_OPERATIONAL=0\\n'; fi
                if command -v podman >/dev/null 2>&1; then printf 'PODMAN_CLIENT=1\\n'; else printf 'PODMAN_CLIENT=0\\n'; fi
                if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then printf 'PODMAN_OPERATIONAL=1\\n'; else printf 'PODMAN_OPERATIONAL=0\\n'; fi
                if command -v podman >/dev/null 2>&1 && podman quadlet --help >/dev/null 2>&1; then
                  printf 'PODMAN_QUADLET=1\\n'
                else
                  printf 'PODMAN_QUADLET=0\\n'
                fi
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
                for version in 3.10 3.11 3.12 3.13; do
                  if command -v "python$version" >/dev/null 2>&1 && "python$version" -m venv --help >/dev/null 2>&1; then
                    if [ "$first_python" -eq 0 ]; then printf ','; fi
                    printf '%s' "$version"
                    first_python=0
                  fi
                done
                printf '\\n'
                if command -v go >/dev/null 2>&1; then
                  printf 'ADVANCED_GO='; go version | sed -E 's/^go version go(1[.][0-9]+).*/\\1/'
                else printf 'ADVANCED_GO=\\n'; fi
                if command -v rustc >/dev/null 2>&1 && command -v cargo >/dev/null 2>&1; then
                  printf 'ADVANCED_RUST='; rustc --version | awk '{print $2}'
                else printf 'ADVANCED_RUST=\\n'; fi
                if command -v dotnet >/dev/null 2>&1; then
                  printf 'ADVANCED_DOTNET='; dotnet --version
                else printf 'ADVANCED_DOTNET=\\n'; fi
                if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq 'version "21([.]|")'; then
                  printf 'ADVANCED_KOTLIN=21\\n'
                else printf 'ADVANCED_KOTLIN=\\n'; fi
                if command -v php >/dev/null 2>&1 && command -v composer >/dev/null 2>&1; then
                  printf 'ADVANCED_PHP='; php -r 'printf("%%d.%%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);'; printf '\\n'
                else printf 'ADVANCED_PHP=\\n'; fi
                if command -v ruby >/dev/null 2>&1 && command -v bundle >/dev/null 2>&1; then
                  printf 'ADVANCED_RUBY='; ruby -e 'print RUBY_VERSION'; printf '\\n'
                else printf 'ADVANCED_RUBY=\\n'; fi
                if [ -x /lib64/ld-linux-x86-64.so.2 ] && /lib64/ld-linux-x86-64.so.2 --help 2>/dev/null | grep -Eq 'x86-64-v3.*supported'; then
                  printf 'X86_64_V3=1\\n'
                else
                  printf 'X86_64_V3=0\\n'
                fi
                if [ -r /proc/cpuinfo ]; then
                  printf 'CPU_FLAGS='
                  awk -F: '/^(flags|Features)[[:space:]]*:/ {print $2; exit}' /proc/cpuinfo | tr ' ' ',' | tr -s ',' | cut -c1-2048
                else
                  printf 'CPU_FLAGS=\\n'
                fi
                """;
    }
}

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
                if command -v podman >/dev/null 2>&1; then printf 'PODMAN_CLIENT=1\\n'; else printf 'PODMAN_CLIENT=0\\n'; fi
                if command -v podman >/dev/null 2>&1 && podman quadlet --help >/dev/null 2>&1; then
                  printf 'PODMAN_QUADLET=1\\n'
                else
                  printf 'PODMAN_QUADLET=0\\n'
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

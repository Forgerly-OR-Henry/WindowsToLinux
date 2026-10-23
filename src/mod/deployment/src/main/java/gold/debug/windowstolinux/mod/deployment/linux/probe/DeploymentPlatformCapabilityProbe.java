package gold.debug.windowstolinux.mod.deployment.linux.probe;

import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType;

/**
 * Fixed read-only shell program for collecting typed deployment distribution, container, and CPU facts.
 *
 *  <p>用于采集部署发行版、容器和 CPU 事实的固定只读 Shell 程序。
 */
public final class DeploymentPlatformCapabilityProbe {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentPlatformCapabilityProbe() {
    }

    /**
     * Renders the implementation-owned capability probe with no caller-provided shell fragment.
     *
     *  <p>渲染由实现持有的能力探测，不接受调用方提供的 Shell 片段。
     *
     * @return fixed capability probe / 固定能力探测
     */
    public static String render() {
        return render("printf 'DOCKER_OPERATIONAL=0\\nPODMAN_OPERATIONAL=0\\nPODMAN_QUADLET=0\\nCONTAINER_OPERATIONAL_PROBED=none\\n'\n");
    }

    /** Checks only the selected container engine; its native store may initialize under separate approval. / 单独审批后仅检查所选容器引擎，原生存储可能初始化。
     * @param engine reviewed container engine / 已审阅容器引擎
     * @return fixed runtime check plus host facts / 固定运行检查与主机事实 */
    public static String renderForContainer(ContainerEngineType engine) {
        return render(switch (engine) {
            case DOCKER ->
                "if probe_command docker >/dev/null 2>&1 && HOME=/root docker info >/dev/null 2>&1; then printf 'DOCKER_OPERATIONAL=1\\n'; else printf 'DOCKER_OPERATIONAL=0\\n'; fi\nprintf 'PODMAN_OPERATIONAL=0\\nPODMAN_QUADLET=0\\nCONTAINER_OPERATIONAL_PROBED=docker\\n'\n";
            case PODMAN ->
                "if probe_command podman >/dev/null 2>&1 && HOME=/root podman info >/dev/null 2>&1; then printf 'PODMAN_OPERATIONAL=1\\n'; else printf 'PODMAN_OPERATIONAL=0\\n'; fi\nif probe_command podman >/dev/null 2>&1 && HOME=/root podman quadlet --help >/dev/null 2>&1; then printf 'PODMAN_QUADLET=1\\n'; else printf 'PODMAN_QUADLET=0\\n'; fi\nprintf 'DOCKER_OPERATIONAL=0\\nCONTAINER_OPERATIONAL_PROBED=podman\\n'\n";
        });
    }

    /** Shares protected metadata and tool checks without inserting model-provided fragments. / 共享受保护元数据与工具检查，不插入模型片段。
     * @param containerChecks implementation-owned fixed checks / 实现所属固定检查
     * @return exact probe / 精确探测 */
    private static String render(String containerChecks) {
        return isolatedProbe(probeGuards() + osReleaseReader() + "probe_parents / || exit 64\n" + ("""
            set -eu
            export LC_ALL=C
            os_release=/etc/os-release
            [ -r "$os_release" ] || os_release=/usr/lib/os-release
            probe_file "$os_release" || exit 64
            [ "$(/usr/bin/stat -c '%s' -- "$os_release")" -le 16384 ] || exit 64
            ID="$(os_release_value ID "$os_release")"
            VARIANT_ID="$(os_release_value VARIANT_ID "$os_release")"
            VERSION_ID="$(os_release_value VERSION_ID "$os_release")"
            printf 'DISTRO_ID=%s\\n' "${ID:-unknown}"
            printf 'DISTRO_VARIANT=%s\\n' "${VARIANT_ID:-}"
            printf 'VERSION=%s\\n' "${VERSION_ID:-unknown}"
            printf 'ARCH='; uname -m
            if command -v apt-get >/dev/null 2>&1; then printf 'PACKAGE_MANAGER=apt\\n';
            elif command -v dnf >/dev/null 2>&1; then printf 'PACKAGE_MANAGER=dnf\\n';
            else printf 'PACKAGE_MANAGER=unknown\\n'; fi
            if command -v dpkg >/dev/null 2>&1; then printf 'PACKAGE_ARCH='; dpkg --print-architecture
            elif command -v rpm >/dev/null 2>&1; then printf 'PACKAGE_ARCH='; rpm --eval '%{_arch}'
            else printf 'PACKAGE_ARCH=unknown\\n'; fi
            if command -v systemctl >/dev/null 2>&1; then printf 'SYSTEMD=1\\n'; else printf 'SYSTEMD=0\\n'; fi
            if command -v docker >/dev/null 2>&1; then printf 'DOCKER_CLIENT=1\\n'; else printf 'DOCKER_CLIENT=0\\n'; fi
            if command -v podman >/dev/null 2>&1; then printf 'PODMAN_CLIENT=1\\n'; else printf 'PODMAN_CLIENT=0\\n'; fi
            """
            + containerChecks
            + DeploymentEcosystemCapabilityProbe.platformToolChecks()
            + """
            cpu_level=unknown
            if [ "$(uname -m)" = x86_64 ]; then
              cpu_level=x86-64-v1
              loader=
              for candidate in /lib64/ld-linux-x86-64.so.2 /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2; do
                if [ -x "$candidate" ] && probe_file "$candidate"; then loader="$candidate"; break; fi
              done
              if [ -n "$loader" ]; then
                loader_help="$($loader --help 2>/dev/null || :)"
                if printf '%s\\n' "$loader_help" | grep -Eq 'x86-64-v4.*supported'; then cpu_level=x86-64-v4
                elif printf '%s\\n' "$loader_help" | grep -Eq 'x86-64-v3.*supported'; then cpu_level=x86-64-v3
                elif printf '%s\\n' "$loader_help" | grep -Eq 'x86-64-v2.*supported'; then cpu_level=x86-64-v2
                fi
              fi
            fi
            printf 'CPU_LEVEL=%s\\n' "$cpu_level"
            if [ -r /proc/cpuinfo ]; then
              printf 'CPU_FLAGS='
              awk -F: '/^(flags|Features)[[:space:]]*:/ {print $2; exit}' /proc/cpuinfo | tr ' ' ',' | tr -s ',' | cut -c1-2048
            else
              printf 'CPU_FLAGS=\\n'
            fi
            if command -v getenforce >/dev/null 2>&1; then
              printf 'SECURITY_MODULE=selinux\\n'
              printf 'SECURITY_STATE='; getenforce | tr '[:upper:]' '[:lower:]'
            elif [ -r /sys/module/apparmor/parameters/enabled ]; then
              printf 'SECURITY_MODULE=apparmor\\n'
              if grep -Eq '^[Yy]' /sys/module/apparmor/parameters/enabled; then
                printf 'SECURITY_STATE=enabled\\n'
              else
                printf 'SECURITY_STATE=disabled\\n'
              fi
            else
              printf 'SECURITY_MODULE=none\\n'
              printf 'SECURITY_STATE=disabled\\n'
            fi
            """
            + firewallProbe()).replace("command -v ", "probe_command ")
            .replace("[ -x \"$kotlin_compiler\" ]", "[ -x \"$kotlin_compiler\" ] && probe_file \"$kotlin_compiler\""));
    }

    /** Runs fixed metadata checks without inherited startup hooks, project directories or user configuration. / 元数据探测不继承启动钩子、项目目录或用户配置。
     * @param body original executable body digest / 原可执行正文摘要
     * @return fixed isolated probe script / 固定隔离探测脚本 */
    static String isolatedProbe(String body) {
        if (body.lines().anyMatch("WTL_HOST_CAPABILITY_PROBE"::equals))
            throw new IllegalArgumentException("Capability probe delimiter collision");
        return """
            exec /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LC_ALL=C \\
              HOME=/dev/null XDG_CONFIG_HOME=/dev/null XDG_CACHE_HOME=/dev/null XDG_DATA_HOME=/dev/null \\
              MAVEN_SKIP_RC=1 MAVEN_BASEDIR=/ NPM_CONFIG_USERCONFIG=/dev/null NPM_CONFIG_GLOBALCONFIG=/dev/null \\
              YARN_IGNORE_PATH=1 COREPACK_ENABLE_NETWORK=0 COREPACK_ENABLE_PROJECT_SPEC=0 COREPACK_HOME=/dev/null \\
              COMPOSER_HOME=/dev/null /bin/bash --noprofile --norc -s <<'WTL_HOST_CAPABILITY_PROBE'
            """ + "cd -- /\n" + body + "\nWTL_HOST_CAPABILITY_PROBE\n";
    }

    /** Restricts probes to protected system paths and checks optional binaries before invoking them. / 限制探测使用受保护系统路径，并在调用可选程序前校验。
     * @return fixed guards / 固定校验 */
    public static String probeGuards() {
        return """
            set -eu
            export LC_ALL=C PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            probe_parents() {
              local parent resolved mode
              parent="${1%/*}"
              [ -n "$parent" ] || parent=/
              while true; do
                if [ -L "$parent" ]; then [ "$(/usr/bin/stat -c '%u' -- "$parent")" = 0 ] || return 1; fi
                resolved="$(/usr/bin/readlink -f -- "$parent")" || return 1
                [ -d "$resolved" ] && [ "$(/usr/bin/stat -c '%u' -- "$resolved")" = 0 ] || return 1
                mode="$(/usr/bin/stat -c '%a' -- "$resolved")" || return 1
                [[ "$mode" =~ ^[0-7]{3,4}$ ]] && [ $((8#$mode & 022)) -eq 0 ] || return 1
                [ "$parent" != / ] || break
                parent="${parent%/*}"
                [ -n "$parent" ] || parent=/
              done
            }
            probe_file() {
              local resolved mode
              [[ "$1" = /* ]] && probe_parents "$1" || return 1
              if [ -L "$1" ]; then [ "$(/usr/bin/stat -c '%u' -- "$1")" = 0 ] || return 1; fi
              resolved="$(/usr/bin/readlink -f -- "$1")" || return 1
              [ -f "$resolved" ] && [ "$(/usr/bin/stat -c '%u' -- "$resolved")" = 0 ] || return 1
              mode="$(/usr/bin/stat -c '%a' -- "$resolved")" || return 1
              [[ "$mode" =~ ^[0-7]{3,4}$ ]] && [ $((8#$mode & 022)) -eq 0 ] || return 1
              probe_parents "$resolved"
            }
            probe_command() {
              local executable
              executable="$(builtin command -v "$1")" || return 1
              [[ "$executable" = /* ]] && [ -x "$executable" ] && probe_file "$executable" || return 1
              printf '%s\\n' "$executable"
            }
            for probe_directory in /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin; do
              [ -e "$probe_directory" ] || continue
              probe_resolved="$(/usr/bin/readlink -f -- "$probe_directory")" || exit 64
              [ -d "$probe_resolved" ] && [ "$(/usr/bin/stat -c '%u' -- "$probe_resolved")" = 0 ] || exit 64
              probe_mode="$(/usr/bin/stat -c '%a' -- "$probe_resolved")" || exit 64
              [[ "$probe_mode" =~ ^[0-7]{3,4}$ ]] && [ $((8#$probe_mode & 022)) -eq 0 ] || exit 64
            done
            """;
    }

    /** Parses only fixed metadata keys as data, without sourcing or evaluating their values. / 仅将固定元数据键解析为数据，不source或求值。
     * @return fixed reader / 固定读取器 */
    public static String osReleaseReader() {
        return """
            os_release_value() {
              local key="$1" file="$2" line value result= found=0
              case "$key" in ID | VARIANT_ID | VERSION_ID) ;; *) return 64 ;; esac
              while IFS= read -r line || [ -n "$line" ]; do
                case "$line" in
                  "$key"=*)
                    [ "$found" -eq 0 ] || return 64
                    value="${line#*=}"
                    case "$value" in
                      \\"*\\") value="${value:1:${#value}-2}" ;;
                      \\'*\\') value="${value:1:${#value}-2}" ;;
                    esac
                    case "$value" in *[!A-Za-z0-9._-]*) return 64 ;; esac
                    result="$value"; found=1 ;;
                esac
              done <"$file"
              printf '%s' "$result"
            }
            """;
    }

    /**
     * Renders the fixed read-only firewall capability observation script.
     * <p>渲染固定的只读防火墙能力观测脚本。
     *
     * @return firewall probe text / 防火墙探测文本
     */
    static String firewallProbe() {
        return """
            if command -v firewall-cmd >/dev/null 2>&1; then
              printf 'FIREWALL=firewalld\\n'
              if ! command -v systemctl >/dev/null 2>&1; then
                printf 'FIREWALL_STATE=unknown\\n'
              elif systemctl is-active --quiet firewalld 2>/dev/null; then
                printf 'FIREWALL_STATE=active\\n'
              else printf 'FIREWALL_STATE=inactive\\n'; fi
            elif command -v ufw >/dev/null 2>&1; then
              printf 'FIREWALL=ufw\\n'
              if ufw_status="$(ufw status 2>/dev/null)"; then
                case "$ufw_status" in
                  'Status: active'*) printf 'FIREWALL_STATE=active\\n' ;;
                  'Status: inactive'*) printf 'FIREWALL_STATE=inactive\\n' ;;
                  *) printf 'FIREWALL_STATE=unknown\\n' ;;
                esac
              else
                printf 'FIREWALL_STATE=unknown\\n'
              fi
            elif command -v nft >/dev/null 2>&1; then
              printf 'FIREWALL=nftables\\n'
              if nft_rules="$(nft list ruleset 2>/dev/null)"; then
                if [ -n "$nft_rules" ]; then printf 'FIREWALL_STATE=active\\n'; else printf 'FIREWALL_STATE=inactive\\n'; fi
              else
                printf 'FIREWALL_STATE=unknown\\n'
              fi
            else
              printf 'FIREWALL=none\\n'
              printf 'FIREWALL_STATE=unknown\\n'
            fi
            """;
    }
}

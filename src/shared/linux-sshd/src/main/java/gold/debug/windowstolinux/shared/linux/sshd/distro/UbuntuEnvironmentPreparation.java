package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedHelperBundle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Provides the {@code UbuntuEnvironmentPreparation} implementation.
 *
 * <p>提供 {@code UbuntuEnvironmentPreparation} 实现。
 */
public final class UbuntuEnvironmentPreparation {
    /**
     * Exposes the {@code TIMEOUT} constant.
     *
     * <p>公开 {@code TIMEOUT} 常量。
     */
    public static final Duration TIMEOUT = Duration.ofMinutes(15);
    /**
     * Exposes the {@code APT_LOCK_TIMEOUT_SECONDS} constant.
     *
     * <p>公开 {@code APT_LOCK_TIMEOUT_SECONDS} 常量。
     */
    public static final int APT_LOCK_TIMEOUT_SECONDS = 300;
    /**
     * Exposes the {@code SUDOERS_PATH} constant.
     *
     * <p>公开 {@code SUDOERS_PATH} 常量。
     */
    public static final String SUDOERS_PATH = "/etc/sudoers.d/windowstolinux-managed";
    /**
     * Exposes the {@code PACKAGES} constant.
     *
     * <p>公开 {@code PACKAGES} 常量。
     */
    public static final List<String> PACKAGES = List.of(
            "openjdk-21-jdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute2", "coreutils",
            "util-linux", "findutils", "gawk", "nodejs", "npm", "python3", "python3-venv", "python3-pip", "docker.io"
    );

    private UbuntuEnvironmentPreparation() {
    }

    /**
     * Performs the {@code renderSudoers} operation.
     *
     * <p>执行 {@code renderSudoers} 操作。
     *
     * @param username the {@code username} value / {@code username} 值
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static String renderSudoers(String username) {
        username = Objects.requireNonNull(username, "username").trim();
        if (!username.matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("username is not a supported Ubuntu account name");
        }
        return "# Managed by WindowsToLinux managed deployment; only the constrained helper is granted.\n"
                + username + " ALL=(root) NOPASSWD: " + ManagedHelperBundle.PATH + "\n";
    }

    /**
     * Performs the {@code renderScript} operation.
     *
     * <p>执行 {@code renderScript} 操作。
     *
     * @param username the {@code username} value / {@code username} 值
     * @return the operation result / 操作结果
     */
    public static String renderScript(String username) {
        return renderScript(username, "24.04");
    }

    /**
     * Renders preparation for one supported Ubuntu LTS release. / 为一个受支持的 Ubuntu LTS 版本渲染环境准备。
     *
     * @param username the deployment account / 部署账户
     * @param version the observed Ubuntu release / 观察到的 Ubuntu 版本
     * @return the bounded preparation script / 有界环境准备脚本
     */
    public static String renderScript(String username, String version) {
        if (!("22.04".equals(version) || "24.04".equals(version))) {
            throw new IllegalArgumentException("Ubuntu preparation supports only 22.04 or 24.04");
        }
        String sudoers = renderSudoers(username);
        String packages = String.join(" ", PACKAGES);
        String helper = ManagedHelperBundle.renderScript();
        String nodeCheck = "24.04".equals(version)
                ? "node --version | grep -Eq '^v18\\.'" : "command -v node >/dev/null 2>&1";
        String pythonCheck = "24.04".equals(version)
                ? "command -v python3.12 >/dev/null 2>&1\npython3.12 -m venv --help >/dev/null 2>&1"
                : "command -v python3.10 >/dev/null 2>&1\npython3.10 -m venv --help >/dev/null 2>&1";
        return """
                set -euo pipefail
                test -r /etc/os-release
                . /etc/os-release
                test "${ID:-}" = ubuntu
                test "${VERSION_ID:-}" = %s
                test "$(uname -m)" = x86_64
                command -v systemctl >/dev/null 2>&1
                test -x /usr/bin/apt-get
                account="$(id -un)"
                test "$account" = %s
                if [ "$(id -u)" -eq 0 ]; then
                  elevation=root
                elif [ -x /usr/bin/sudo ] && /usr/bin/sudo -n true >/dev/null 2>&1; then
                  /usr/bin/sudo -n /usr/bin/apt-get --version >/dev/null
                  /usr/bin/sudo -n /usr/sbin/visudo -V >/dev/null
                  /usr/bin/sudo -n /usr/bin/install --version >/dev/null
                  elevation=sudo
                else
                  printf 'PREPARE_REJECT=non-root-requires-existing-noninteractive-sudo\\n'
                  exit 64
                fi
                if [ "$elevation" = root ]; then
                  /usr/bin/apt-get -o DPkg::Lock::Timeout=%d update
                  /usr/bin/apt-get -o DPkg::Lock::Timeout=%d install -y --no-install-recommends %s
                else
                  /usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=%d update
                  /usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=%d install -y --no-install-recommends %s
                fi
                /usr/bin/java -version 2>&1 | /usr/bin/grep -q '"21\\.'
                command -v mvn >/dev/null 2>&1
                command -v curl >/dev/null 2>&1
                command -v tar >/dev/null 2>&1
                command -v gzip >/dev/null 2>&1
                command -v ss >/dev/null 2>&1
                command -v setsid >/dev/null 2>&1
                command -v timeout >/dev/null 2>&1
                command -v du >/dev/null 2>&1
                %s
                command -v npm >/dev/null 2>&1
                %s
                command -v docker >/dev/null 2>&1
                docker info >/dev/null 2>&1
                tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-sudoers.XXXXXX)
                helper_tmp=$(/usr/bin/mktemp /tmp/windowstolinux-managed-helper.XXXXXX)
                trap '/usr/bin/rm -f -- "$tmp" "$helper_tmp"' EXIT
                sudoers=%s
                helper=%s
                printf '%%s' "$sudoers" > "$tmp"
                printf '%%s' "$helper" > "$helper_tmp"
                if [ "$elevation" = root ]; then
                  /usr/sbin/visudo -cf "$tmp"
                  /usr/bin/install -d -o root -g root -m 755 %s
                  /usr/bin/install -o root -g root -m 755 "$helper_tmp" %s
                  /usr/bin/install -o root -g root -m 440 "$tmp" %s
                else
                  /usr/bin/sudo -n /usr/sbin/visudo -cf "$tmp"
                  /usr/bin/sudo -n /usr/bin/install -d -o root -g root -m 755 %s
                  /usr/bin/sudo -n /usr/bin/install -o root -g root -m 755 "$helper_tmp" %s
                  /usr/bin/sudo -n /usr/bin/install -o root -g root -m 440 "$tmp" %s
                fi
                /usr/bin/sudo -n %s probe | /usr/bin/grep -qx 'HELPER=1'
                printf 'PREPARED_AS=%%s\\n' "$elevation"
                printf 'PACKAGES=%s\\n'
                printf 'SUDOERS=%s\\n'
                printf 'HELPER=%s\\n'
                """.formatted(
                quote(version), quote(username), APT_LOCK_TIMEOUT_SECONDS, APT_LOCK_TIMEOUT_SECONDS, packages,
                APT_LOCK_TIMEOUT_SECONDS, APT_LOCK_TIMEOUT_SECONDS, packages, nodeCheck, pythonCheck,
                quote(sudoers), quote(helper),
                quote(ManagedHelperBundle.DIRECTORY), quote(ManagedHelperBundle.PATH),
                quote(SUDOERS_PATH), quote(ManagedHelperBundle.DIRECTORY), quote(ManagedHelperBundle.PATH),
                quote(SUDOERS_PATH), quote(ManagedHelperBundle.PATH), packages, SUDOERS_PATH,
                ManagedHelperBundle.PATH
        );
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

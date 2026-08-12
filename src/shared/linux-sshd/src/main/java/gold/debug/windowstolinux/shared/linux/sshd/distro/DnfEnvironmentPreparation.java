package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedHelperBundle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Renders the fixed CentOS Stream environment preparation path.
 *
 * <p>渲染固定的 CentOS Stream 环境准备路径。
 */
public final class DnfEnvironmentPreparation {
    /** Maximum allowed preparation duration. / 允许的最长环境准备时长。 */
    public static final Duration TIMEOUT = Duration.ofMinutes(15);
    /** Fixed packages for the supported DNF targets. / 受支持 DNF 目标的固定软件包。 */
    public static final List<String> PACKAGES = List.of(
            "java-21-openjdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute", "coreutils",
            "util-linux", "findutils", "gawk"
    );

    private DnfEnvironmentPreparation() {
    }

    /**
     * Renders one CentOS Stream 9 or 10 preparation script. / 渲染一个 CentOS Stream 9 或 10 的环境准备脚本。
     *
     * @param username the deployment account / 部署账户
     * @param version the observed stream version / 观察到的 Stream 版本
     * @return the bounded preparation script / 有界环境准备脚本
     */
    public static String renderScript(String username, String version) {
        username = requireUsername(username);
        if (!("9".equals(version) || "10".equals(version))) {
            throw new IllegalArgumentException("DNF preparation supports only CentOS Stream 9 or 10");
        }
        String sudoers = UbuntuEnvironmentPreparation.renderSudoers(username);
        String packages = String.join(" ", PACKAGES);
        String helper = ManagedHelperBundle.renderScript();
        return """
                set -euo pipefail
                test -r /etc/os-release
                . /etc/os-release
                test "${ID:-}" = centos
                test "${VARIANT_ID:-}" = stream
                test "${VERSION_ID:-}" = %s
                test "$(uname -m)" = x86_64
                command -v systemctl >/dev/null 2>&1
                test -x /usr/bin/dnf
                account="$(id -un)"
                test "$account" = %s
                if [ "$(id -u)" -eq 0 ]; then
                  elevation=root
                elif [ -x /usr/bin/sudo ] && /usr/bin/sudo -n true >/dev/null 2>&1; then
                  /usr/bin/sudo -n /usr/bin/dnf --version >/dev/null
                  /usr/bin/sudo -n /usr/sbin/visudo -V >/dev/null
                  /usr/bin/sudo -n /usr/bin/install --version >/dev/null
                  elevation=sudo
                else
                  printf 'PREPARE_REJECT=non-root-requires-existing-noninteractive-sudo\\n'
                  exit 64
                fi
                if [ "$elevation" = root ]; then
                  /usr/bin/dnf -y install %s
                else
                  /usr/bin/sudo -n /usr/bin/dnf -y install %s
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
                quote(version), quote(username), packages, packages, quote(sudoers), quote(helper),
                quote(ManagedHelperBundle.DIRECTORY), quote(ManagedHelperBundle.PATH),
                quote(UbuntuEnvironmentPreparation.SUDOERS_PATH), quote(ManagedHelperBundle.DIRECTORY),
                quote(ManagedHelperBundle.PATH), quote(UbuntuEnvironmentPreparation.SUDOERS_PATH),
                quote(ManagedHelperBundle.PATH), packages, UbuntuEnvironmentPreparation.SUDOERS_PATH,
                ManagedHelperBundle.PATH
        );
    }

    private static String requireUsername(String username) {
        username = Objects.requireNonNull(username, "username").trim();
        if (!username.matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("username is not a supported Linux account name");
        }
        return username;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

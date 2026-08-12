package gold.debug.windowstolinux.shared.linux.sshd.capability;

import java.util.Objects;

/**
 * Provides the {@code CapabilityProbeScript} implementation.
 *
 * <p>提供 {@code CapabilityProbeScript} 实现。
 */
public final class CapabilityProbeScript {
    private CapabilityProbeScript() {
    }

    /**
     * Performs the {@code render} operation.
     *
     * <p>执行 {@code render} 操作。
     *
     * @param helperPath the {@code helperPath} value / {@code helperPath} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static String render(String helperPath) {
        return "managed_helper=" + quote(Objects.requireNonNull(helperPath, "helperPath")) + "\n" + """
                set -eu
                . /etc/os-release
                printf 'OS=%s %s\\n' "$NAME" "$VERSION_ID"
                printf 'ARCH='; uname -m
                if command -v systemctl >/dev/null 2>&1; then printf 'SYSTEMD=1\\n'; else printf 'SYSTEMD=0\\n'; fi
                if /usr/bin/java -version 2>&1 | grep -q '"21\\.'; then printf 'JAVA21=1\\n'; else printf 'JAVA21=0\\n'; fi
                if command -v mvn >/dev/null 2>&1; then printf 'MAVEN=1\\n'; else printf 'MAVEN=0\\n'; fi
                if command -v tar >/dev/null 2>&1 && command -v gzip >/dev/null 2>&1; then printf 'TAR=1\\n'; else printf 'TAR=0\\n'; fi
                if command -v curl >/dev/null 2>&1; then printf 'CURL=1\\n'; else printf 'CURL=0\\n'; fi
                if command -v ss >/dev/null 2>&1; then printf 'SS=1\\n'; else printf 'SS=0\\n'; fi
                if command -v setsid >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 && command -v du >/dev/null 2>&1; then printf 'LIMIT_TOOLS=1\\n'; else printf 'LIMIT_TOOLS=0\\n'; fi
                helper_probe="$(sudo -n "$managed_helper" probe 2>/dev/null || true)"
                if printf '%s\\n' "$helper_probe" | grep -qx 'HELPER=1'; then
                  printf 'SUDO=1\\n'
                else
                  printf 'SUDO=0\\n'
                fi
                printf 'HELPER_PROTOCOL=%s\\n' "$(printf '%s\\n' "$helper_probe" | awk -F= '/^PROTOCOL=[0-9]+$/ {print $2; exit}')"
                printf 'FREE='; df -B1 --output=avail /var/lib | tail -n 1 | tr -d ' '
                """;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

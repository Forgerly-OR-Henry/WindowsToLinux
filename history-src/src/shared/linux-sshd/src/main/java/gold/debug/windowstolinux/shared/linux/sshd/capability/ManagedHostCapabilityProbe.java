package gold.debug.windowstolinux.shared.linux.sshd.capability;

import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.sshd.capability.ecosystem.ManagedEcosystemCapabilityProbe;

/**
 * Renders bounded host probes for tools, privilege boundaries and the managed helper protocol.
 * <p>渲染用于工具、权限边界及受管 helper 协议的有界主机探测。
 */
public final class ManagedHostCapabilityProbe {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ManagedHostCapabilityProbe() {
    }

    /**
     * Renders managed host capability probe as text without executing the rendered command.
     * <p>渲染受管主机能力探测为文本，不执行所渲染命令。
     *
     * @param helperPath helper path / helper路径
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String render(String helperPath) {
        return "managed_helper=" + quote(Objects.requireNonNull(helperPath, "helperPath")) + "\n"
                + ManagedEcosystemCapabilityProbe.managedJavaEnvironment()
                + """
                        set -eu
                        . /etc/os-release
                        printf 'OS=%s %s\\n' "$NAME" "$VERSION_ID"
                        printf 'ARCH='; uname -m
                        if command -v systemctl >/dev/null 2>&1; then printf 'SYSTEMD=1\\n'; else printf 'SYSTEMD=0\\n'; fi
                        """
                + ManagedEcosystemCapabilityProbe.hostToolChecks()
                + """
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

    /**
     * Quotes a literal argument for the fixed command-rendering boundary.
     * <p>为固定命令渲染边界引用字面参数。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return quote text / 引用文本
     */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

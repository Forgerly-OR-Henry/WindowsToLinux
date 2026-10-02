package gold.debug.windowstolinux.app.linux.ssh.recover;

import java.util.Objects;

/** Identifies the admitted rescue endpoint without credentials or business configuration. / 标识已准入救援端点，不含凭据或业务配置。
 * @param id original server identity / 原服务器身份
 * @param host admitted host / 准入主机
 * @param sshPort admitted SSH port / 准入 SSH 端口 */
public record RecoveryTarget(String id, String host, int sshPort) {
    /** Rejects incomplete endpoint facts before the controller starts. / 控制器启动前拒绝不完整端点事实。
     * @param id original server identity / 原服务器身份
     * @param host admitted host / 准入主机
     * @param sshPort admitted SSH port / 准入 SSH 端口 */
    public RecoveryTarget {
        Objects.requireNonNull(id);
        Objects.requireNonNull(host);
        if (id.isBlank() || host.isBlank() || sshPort < 1 || sshPort > 65535)
            throw new IllegalArgumentException("Invalid rescue endpoint");
    }
}

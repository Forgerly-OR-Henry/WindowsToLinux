package gold.debug.windowstolinux.shared.linux.connection;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;

/**
 * Opens one verified SSH session for the full deployment or lifecycle action.
 *
 *  <p>为完整部署或生命周期动作打开单个已验证 SSH 会话。
 */
public interface LinuxGateway {
    /**
     * Opens an authenticated session after the supplied host-key verification.
     * <p>在所提供主机密钥验证完成后打开已认证会话。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    LinuxRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator hostKeyVerifier)
            throws LinuxOperationException;
}

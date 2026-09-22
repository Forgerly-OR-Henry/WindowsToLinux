package gold.debug.windowstolinux.shared.linux.connection;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;

/**
 * Opens one verified SSH session that supports the bounded typed deployment protocol.
 *
 *  <p>打开一个支持有界部署协议的已验证 SSH 会话。
 */
public interface DeploymentLinuxGateway extends LinuxGateway {
    /**
     * Opens the typed session after host-key verification. / 在主机密钥验证后打开类型化会话。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return constructed or resolved deployment remote session / 构造或解析得到的部署远端会话
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    DeploymentRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator hostKeyVerifier)
            throws LinuxOperationException;
}

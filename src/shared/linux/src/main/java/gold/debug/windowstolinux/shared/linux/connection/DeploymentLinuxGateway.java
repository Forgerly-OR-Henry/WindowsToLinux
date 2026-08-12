package gold.debug.windowstolinux.shared.linux.connection;

/**
 * Opens one verified SSH session that supports the bounded typed deployment protocol.
 *
 * <p>打开一个支持有界部署协议的已验证 SSH 会话。
 */
public interface DeploymentLinuxGateway extends LinuxGateway {
    /** Opens the typed session after host-key verification. / 在主机密钥验证后打开类型化会话。 */
    @Override
    DeploymentRemoteSession connect(SshEndpoint endpoint, SshCredential credential,
                                  HostKeyVerifier hostKeyVerifier) throws LinuxOperationException;
}

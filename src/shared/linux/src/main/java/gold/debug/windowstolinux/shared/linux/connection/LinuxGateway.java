package gold.debug.windowstolinux.shared.linux.connection;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;

/**
 * Opens one verified SSH session for the full deployment or lifecycle action.
 *
 * <p>为完整部署或生命周期动作打开单个已验证 SSH 会话。
 */
public interface LinuxGateway {
    /**
     * Performs the {@code connect} operation.
     *
     * <p>执行 {@code connect} 操作。
     *
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param credential the {@code credential} value / {@code credential} 值
     * @param hostKeyVerifier the {@code hostKeyVerifier} value / {@code hostKeyVerifier} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    LinuxRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyVerifier hostKeyVerifier)
            throws LinuxOperationException;
}

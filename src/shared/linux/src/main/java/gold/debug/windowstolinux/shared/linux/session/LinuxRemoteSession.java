package gold.debug.windowstolinux.shared.linux.session;

import gold.debug.windowstolinux.shared.linux.capability.LinuxCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.distro.LinuxEnvironmentPreparer;
import gold.debug.windowstolinux.shared.linux.runtime.LinuxRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.LinuxSourceTransport;

/**
 * One verified remote session composed only from bounded, typed Linux capabilities.
 *
 *  <p>仅由有界、类型化 Linux 能力组成的单个已验证远程会话。
 */
public interface LinuxRemoteSession extends AutoCloseable,
        LinuxCapabilityCollector,
        LinuxEnvironmentPreparer,
        LinuxSourceTransport,
        LinuxRuntimeExecutor {
    /**
     * Verifies authenticated read-only execution without a distribution allowlist. / 验证已认证的只读执行，不限制发行版。
     *
     * @throws gold.debug.windowstolinux.shared.linux.error.LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    default void verifyConnection() throws gold.debug.windowstolinux.shared.linux.error.LinuxOperationException {
        collectCapabilities();
    }
    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    void close();
}

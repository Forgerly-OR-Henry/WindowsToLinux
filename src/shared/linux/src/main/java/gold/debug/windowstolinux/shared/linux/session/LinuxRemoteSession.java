package gold.debug.windowstolinux.shared.linux.session;

import gold.debug.windowstolinux.shared.linux.capability.LinuxCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.distro.LinuxEnvironmentPreparer;
import gold.debug.windowstolinux.shared.linux.runtime.LinuxRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.LinuxSourceTransport;

/**
 * One verified remote session composed only from bounded, typed Linux capabilities.
 *
 * <p>仅由有界、类型化 Linux 能力组成的单个已验证远程会话。
 */
public interface LinuxRemoteSession extends AutoCloseable,
        LinuxCapabilityCollector,
        LinuxEnvironmentPreparer,
        LinuxSourceTransport,
        LinuxRuntimeExecutor {
    /** Closes this resource. / 关闭此资源。 */
    @Override
    void close();
}

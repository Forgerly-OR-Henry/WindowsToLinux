package gold.debug.windowstolinux.shared.linux.connection;

import gold.debug.windowstolinux.shared.linux.build.LinuxBuildOperations;
import gold.debug.windowstolinux.shared.linux.capability.LinuxCapabilityOperations;
import gold.debug.windowstolinux.shared.linux.distro.LinuxEnvironmentOperations;
import gold.debug.windowstolinux.shared.linux.protocol.LinuxReleaseOperations;
import gold.debug.windowstolinux.shared.linux.runtime.LinuxRuntimeOperations;
import gold.debug.windowstolinux.shared.linux.transfer.LinuxTransferOperations;

/**
 * One verified remote session composed only from bounded, typed Linux capabilities.
 *
 * <p>仅由有界、类型化 Linux 能力组成的单个已验证远程会话。
 */
public interface LinuxRemoteSession extends AutoCloseable,
        LinuxCapabilityOperations,
        LinuxEnvironmentOperations,
        LinuxTransferOperations,
        LinuxBuildOperations,
        LinuxReleaseOperations,
        LinuxRuntimeOperations {
    @Override
    void close();
}

package gold.debug.windowstolinux.shared.linux.capability;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.server.PhaseTwoLinuxCapabilities;

/**
 * Read-only capability collection for the Phase Two distribution and container matrix.
 *
 * <p>用于二期发行版和容器矩阵的只读能力采集。
 */
public interface LinuxPhaseTwoCapabilityOperations {
    /**
     * Collects current non-secret Phase Two host facts.
     *
     * <p>采集当前非秘密二期主机事实。
     *
     * @return current host facts / 当前主机事实
     * @throws LinuxOperationException if collection cannot complete / 无法完成采集时
     */
    PhaseTwoLinuxCapabilities collectPhaseTwoCapabilities() throws LinuxOperationException;
}

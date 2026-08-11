package gold.debug.windowstolinux.shared.linux.distro;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.deployment.PhaseOneEnvironmentPreparationApproval;
import gold.debug.windowstolinux.shared.model.deployment.PhaseOneEnvironmentPreparationResult;

/**
 * Distribution-aware environment preparation contract.
 *
 * <p>感知发行版的环境准备契约。
 */
public interface LinuxEnvironmentOperations {
    /**
     * Performs the {@code preparePhaseOneEnvironment} operation.
     *
     * <p>执行 {@code preparePhaseOneEnvironment} 操作。
     *
     * @param approval the {@code approval} value / {@code approval} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    PhaseOneEnvironmentPreparationResult preparePhaseOneEnvironment(PhaseOneEnvironmentPreparationApproval approval)
            throws LinuxOperationException;
}

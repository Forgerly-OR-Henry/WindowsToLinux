package gold.debug.windowstolinux.shared.linux.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;

/**
 * Distribution-aware environment preparation contract.
 *
 * <p>感知发行版的环境准备契约。
 */
public interface LinuxEnvironmentPreparer {
    /** Exposes separately confirmed system preparation when the transport supports it. / 提供传输所支持的独立确认系统准备能力。 */
    default SelinuxEnvironmentPreparer selinuxPreparation() throws LinuxOperationException {
        throw LinuxOperationException.create(
                gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.ENVIRONMENT_UNSUPPORTED_DISTRO,
                "SELinux preparation is not supported by this connection");
    }
    /**
     * Performs the {@code prepareEnvironment} operation.
     *
     * <p>执行 {@code prepareEnvironment} 操作。
     *
     * @param approval the {@code approval} value / {@code approval} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    EnvironmentSetupResult prepareEnvironment(EnvironmentSetupApproval approval)
            throws LinuxOperationException;
}

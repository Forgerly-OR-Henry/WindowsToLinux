package gold.debug.windowstolinux.shared.linux.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;

/**
 * Distribution-aware environment preparation contract.
 *
 *  <p>感知发行版的环境准备契约。
 */
public interface LinuxEnvironmentPreparer {
    /**
     * Exposes separately confirmed system preparation when the transport supports it. / 提供传输所支持的独立确认系统准备能力。
     *
     * @return constructed or resolved selinux environment preparer / 构造或解析得到的Selinux环境准备器
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    default SelinuxEnvironmentPreparer selinuxPreparation() throws LinuxOperationException {
        throw LinuxOperationException.create(
                gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.ENVIRONMENT_UNSUPPORTED_DISTRO,
                "SELinux preparation is not supported by this connection");
    }
    /**
     * Prepares environment.
     * <p>准备环境。
     *
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    EnvironmentSetupResult prepareEnvironment(EnvironmentSetupApproval approval)
            throws LinuxOperationException;
}

package gold.debug.windowstolinux.shared.linux.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan;

/**
 * Fixed system preparation operations on the authenticated target. / 已认证目标上的固定系统准备操作。
 */
public interface SelinuxEnvironmentPreparer {
    /**
     * Inspects optional.
     * <p>检查可选。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    java.util.Optional<SelinuxPreparationPlan> inspect() throws LinuxOperationException;
    /**
     * Prepares reboot.
     * <p>准备重启。
     *
     * @param approved approved / 已批准
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void prepareReboot(SelinuxPreparationPlan approved) throws LinuxOperationException;
    /**
     * Enables enforcement.
     * <p>启用强制执行。
     *
     * @param approved approved / 已批准
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void enableEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException;
    /**
     * Commits enforcement.
     * <p>提交强制执行。
     *
     * @param approved approved / 已批准
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    void commitEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException;
}

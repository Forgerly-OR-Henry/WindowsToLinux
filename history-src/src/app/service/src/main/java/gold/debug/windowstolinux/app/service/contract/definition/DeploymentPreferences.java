package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.shared.model.deployment.*;

/** Immutable defaults for newly created deployment tasks. / 新建部署任务使用的不可变默认选择。
 * @param mode mode supplied to this boundary / 传入当前边界的mode值
 * @param approval approval supplied to this boundary / 传入当前边界的approval值
 */
public record DeploymentPreferences(DeploymentAutomationMode mode, AgentApprovalMode approval) {
    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param mode mode supplied to this boundary / 传入当前边界的mode值
     * @param approval approval supplied to this boundary / 传入当前边界的approval值
     */
    public DeploymentPreferences {
        java.util.Objects.requireNonNull(mode);
        java.util.Objects.requireNonNull(approval);
    }
}

package gold.debug.windowstolinux.shared.model.deployment;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Stable rejection types for an environment-setup approval. / 环境准备批准的稳定拒绝类型。
 */
public enum DeploymentApprovalFailureType implements FailureDefinition {
    /**
     * CONFIRMATION REQUIRED classification within deployment approval failure type.
     * <p>部署Approval失败类型中的确认必需分类。
     */
    CONFIRMATION_REQUIRED("deployment.approval.confirmation-required", "approval",
            "deployment.error.confirmationRequired"),
    /**
     * SERVER MISMATCH classification within deployment approval failure type.
     * <p>部署Approval失败类型中的服务器不匹配分类。
     */
    SERVER_MISMATCH("deployment.approval.server-mismatch", "approval",
            "deployment.error.approvalServerMismatch");

    /**
     * Stable machine-readable classification code.
     * <p>稳定的机器可读分类码。
     */
    private final String code;
    /**
     * Stage associated with the result or failure.
     * <p>结果或失败所属阶段。
     */
    private final String phase;
    /**
     * Stable localization key for user-facing text.
     * <p>用户可见文本的稳定本地化键。
     */
    private final String messageKey;

    /**
     * Binds the supplied dependencies and state for deployment approval failure type.
     * <p>为部署Approval失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    DeploymentApprovalFailureType(String code, String phase, String messageKey) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
    }

    /**
     * Returns stable machine-readable classification code.
     * <p>返回稳定的机器可读分类码。
     *
     * @return stable machine-readable classification code / 稳定的机器可读分类码
     */
    @Override public String code() { return code; }
    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override public String domain() { return "deployment"; }
    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    @Override public String phase() { return phase; }
    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    @Override public String messageKey() { return messageKey; }
    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    /**
     * Returns the prescribed recovery action for this failure definition.
     * <p>返回当前失败定义规定的恢复动作。
     *
     * @return the prescribed recovery action for this failure definition / 当前失败定义规定的恢复动作
     */
    @Override public FailureRecoveryAction recoveryAction() { return FailureRecoveryAction.REQUEST_USER_CORRECTION; }
}

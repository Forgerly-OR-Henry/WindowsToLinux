package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by the Windows desktop update boundary. / Windows 桌面更新边界持有的失败类型。
 */
public enum DesktopUpdateFailureType implements FailureDefinition {
    /**
     * MANIFEST INVALID classification within desktop update failure type.
     * <p>Desktop更新失败类型中的清单无效分类。
     */
    MANIFEST_INVALID("windows.update.manifest-invalid", "validation", "windows.error.updateManifestInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * SIGNATURE INVALID classification within desktop update failure type.
     * <p>Desktop更新失败类型中的签名无效分类。
     */
    SIGNATURE_INVALID("windows.update.signature-invalid", "verification", "windows.error.updateSignatureInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * VERSION REJECTED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的版本已拒绝分类。
     */
    VERSION_REJECTED("windows.update.version-rejected", "verification", "windows.error.updateVersionRejected",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ARCHITECTURE REJECTED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的架构已拒绝分类。
     */
    ARCHITECTURE_REJECTED("windows.update.architecture-rejected", "verification",
            "windows.error.updateArchitectureRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * PACKAGE INVALID classification within desktop update failure type.
     * <p>Desktop更新失败类型中的软件包无效分类。
     */
    PACKAGE_INVALID("windows.update.package-invalid", "verification", "windows.error.updatePackageInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * TRANSACTION FAILED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的事务失败分类。
     */
    TRANSACTION_FAILED("windows.update.transaction-failed", "update", "windows.error.updateTransactionFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * ROLLBACK FAILED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的回滚失败分类。
     */
    ROLLBACK_FAILED("windows.update.rollback-failed", "rollback", "windows.error.updateRollbackFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

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
     * Action required to recover from the classified failure.
     * <p>从已分类失败中恢复所需的动作。
     */
    private final FailureRecoveryAction recoveryAction;

    /**
     * Binds the supplied dependencies and state for desktop update failure type.
     * <p>为Desktop更新失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    DesktopUpdateFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    /**
     * Returns stable machine-readable classification code.
     * <p>返回稳定的机器可读分类码。
     *
     * @return stable machine-readable classification code / 稳定的机器可读分类码
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override
    public String domain() {
        return "windows";
    }

    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    @Override
    public String phase() {
        return phase;
    }

    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    @Override
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    @Override
    public FailureSeverityLevel severity() {
        return FailureSeverityLevel.ERROR;
    }

    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override
    public FailureRecoveryAction recoveryAction() {
        return recoveryAction;
    }
}

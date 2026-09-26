package gold.debug.windowstolinux.app.service.recovery;

import gold.debug.windowstolinux.shared.model.failure.*;

/**
 * Browser failures mapped for the existing rescue UI. / 旧救援界面所需的平台浏览器错误映射。
 */
public enum BrowserRecoveryFailureType implements FailureDefinition {
    /**
     * OPERATION FAILED classification within browser recovery failure type.
     * <p>浏览器恢复失败类型中的操作失败分类。
     */
    OPERATION_FAILED("linux.browser.operation-failed", "browser", "linux.error.browserOperationFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * OBSERVATION FAILED classification within browser recovery failure type.
     * <p>浏览器恢复失败类型中的观测失败分类。
     */
    OBSERVATION_FAILED("linux.browser.observation-failed", "observation", "linux.error.browserObservationFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * CLEANUP FAILED classification within browser recovery failure type.
     * <p>浏览器恢复失败类型中的清理失败分类。
     */
    CLEANUP_FAILED("linux.browser.cleanup-failed", "cleanup", "linux.error.browserCleanupFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * INTERRUPTED classification within browser recovery failure type.
     * <p>浏览器恢复失败类型中的已中断分类。
     */
    INTERRUPTED("linux.browser.interrupted", "browser", "linux.error.browserInterrupted", FailureRecoveryAction.NONE);
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
     * Binds the supplied dependencies and state for browser recovery failure type.
     * <p>为浏览器恢复失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    BrowserRecoveryFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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
        return "linux";
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

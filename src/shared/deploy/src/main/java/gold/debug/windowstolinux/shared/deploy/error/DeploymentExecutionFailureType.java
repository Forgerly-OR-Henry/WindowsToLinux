package gold.debug.windowstolinux.shared.deploy.error;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by reviewed deployment orchestration. / 经审阅部署编排持有的失败类型。
 */
public enum DeploymentExecutionFailureType implements FailureDefinition {
    /**
     * PRECONDITION REJECTED classification within deployment execution failure type.
     * <p>部署执行失败类型中的前提条件已拒绝分类。
     */
    PRECONDITION_REJECTED("deployment.preflight.rejected", "preflight", "deployment.error.preconditionRejected", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * BUILD FAILED classification within deployment execution failure type.
     * <p>部署执行失败类型中的构建失败分类。
     */
    BUILD_FAILED("deployment.build.failed", "build", "deployment.error.buildFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.CLEANUP),
    /**
     * SWITCH UNVERIFIED classification within deployment execution failure type.
     * <p>部署执行失败类型中的切换未验证分类。
     */
    SWITCH_UNVERIFIED("deployment.switch.unverified", "switch", "deployment.error.switchUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.ROLLBACK),
    /**
     * PUBLISH FAILED classification within deployment execution failure type.
     * <p>部署执行失败类型中的发布失败分类。
     */
    PUBLISH_FAILED("deployment.publish.failed", "publish", "deployment.error.publishFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.ROLLBACK),
    /**
     * HEALTH FAILED classification within deployment execution failure type.
     * <p>部署执行失败类型中的健康失败分类。
     */
    HEALTH_FAILED("deployment.health.failed", "health", "deployment.error.healthFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.ROLLBACK),
    /**
     * OBSERVATION UNVERIFIED classification within deployment execution failure type.
     * <p>部署执行失败类型中的观测未验证分类。
     */
    OBSERVATION_UNVERIFIED("deployment.observation.unverified", "observation", "deployment.error.observationUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * ARITHMETIC OVERFLOW classification within deployment execution failure type.
     * <p>部署执行失败类型中的算术溢出分类。
     */
    ARITHMETIC_OVERFLOW("deployment.preflight.arithmetic-overflow", "preflight", "deployment.error.arithmeticOverflow", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * CLEANUP UNVERIFIED classification within deployment execution failure type.
     * <p>部署执行失败类型中的清理未验证分类。
     */
    CLEANUP_UNVERIFIED("deployment.cleanup.unverified", "cleanup", "deployment.error.cleanupUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * ROLLBACK UNVERIFIED classification within deployment execution failure type.
     * <p>部署执行失败类型中的回滚未验证分类。
     */
    ROLLBACK_UNVERIFIED("deployment.rollback.unverified", "rollback", "deployment.error.rollbackUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * POST PUBLICATION CLEANUP PENDING classification within deployment execution failure type.
     * <p>部署执行失败类型中的后置发布清理待处理分类。
     */
    POST_PUBLICATION_CLEANUP_PENDING("deployment.cleanup.pending", "cleanup", "deployment.error.cleanupPending", FailureSeverityLevel.WARNING, FailureRecoveryAction.CLEANUP),
    /**
     * LOCAL OBSERVATION PERSISTENCE FAILED classification within deployment execution failure type.
     * <p>部署执行失败类型中的本地观测持久化失败分类。
     */
    LOCAL_OBSERVATION_PERSISTENCE_FAILED("deployment.persistence.observation-save-failed", "persistence", "deployment.error.observationSaveFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.RETRY);

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
     * Severity level assigned to the failure definition.
     * <p>分配给失败定义的严重级别。
     */
    private final FailureSeverityLevel severity;
    /**
     * Action required to recover from the classified failure.
     * <p>从已分类失败中恢复所需的动作。
     */
    private final FailureRecoveryAction recoveryAction;

    /**
     * Binds the supplied dependencies and state for deployment execution failure type.
     * <p>为部署执行失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param severity severity level assigned to the failure definition / 分配给失败定义的严重级别
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    DeploymentExecutionFailureType(String code, String phase, String messageKey,
                                   FailureSeverityLevel severity, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.severity = severity;
        this.recoveryAction = recoveryAction;
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
     * Returns severity level assigned to the failure definition.
     * <p>返回分配给失败定义的严重级别。
     *
     * @return severity level assigned to the failure definition / 分配给失败定义的严重级别
     */
    @Override public FailureSeverityLevel severity() { return severity; }
    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

package gold.debug.windowstolinux.shared.git;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by controlled Git snapshot preparation. / 受控 Git 快照准备持有的失败类型。
 */
public enum GitSnapshotFailureType implements FailureDefinition {
    /**
     * WORKSPACE REQUIRED classification within git snapshot failure type.
     * <p>Git快照失败类型中的工作区必需分类。
     */
    WORKSPACE_REQUIRED("git.workspace.required", "workspace", "git.error.workspaceRequired",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * WORKSPACE UNAVAILABLE classification within git snapshot failure type.
     * <p>Git快照失败类型中的工作区不可用分类。
     */
    WORKSPACE_UNAVAILABLE("git.workspace.unavailable", "workspace", "git.error.workspaceUnavailable",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * TOOL UNAVAILABLE classification within git snapshot failure type.
     * <p>Git快照失败类型中的工具不可用分类。
     */
    TOOL_UNAVAILABLE("git.command.tool-unavailable", "command", "git.error.toolUnavailable",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * REFERENCE UNAVAILABLE classification within git snapshot failure type.
     * <p>Git快照失败类型中的引用不可用分类。
     */
    REFERENCE_UNAVAILABLE("git.fetch.reference-unavailable", "fetch", "git.error.referenceUnavailable",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * TRANSIENT NETWORK FAILURE classification within git snapshot failure type.
     * <p>Git快照失败类型中的暂时网络失败分类。
     */
    TRANSIENT_NETWORK_FAILURE("git.fetch.transient-network-failure", "fetch", "git.error.transientNetworkFailure",
            FailureRecoveryAction.RETRY),
    /**
     * COMMAND FAILED classification within git snapshot failure type.
     * <p>Git快照失败类型中的命令失败分类。
     */
    COMMAND_FAILED("git.command.execution-failed", "command", "git.error.commandFailed", FailureRecoveryAction.RETRY),
    /**
     * TIMEOUT classification within git snapshot failure type.
     * <p>Git快照失败类型中的超时分类。
     */
    TIMEOUT("git.command.timeout", "command", "git.error.timeout", FailureRecoveryAction.RETRY),
    /**
     * PREPARATION FAILED classification within git snapshot failure type.
     * <p>Git快照失败类型中的准备失败分类。
     */
    PREPARATION_FAILED("git.snapshot.preparation-failed", "snapshot", "git.error.preparationFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * CLEANUP FAILED classification within git snapshot failure type.
     * <p>Git快照失败类型中的清理失败分类。
     */
    CLEANUP_FAILED("git.snapshot.cleanup-failed", "cleanup", "git.error.cleanupFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * INTERRUPTED classification within git snapshot failure type.
     * <p>Git快照失败类型中的已中断分类。
     */
    INTERRUPTED("git.snapshot.interrupted", "snapshot", "git.error.interrupted", FailureRecoveryAction.NONE);

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
     * Binds the supplied dependencies and state for git snapshot failure type.
     * <p>为Git快照失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    GitSnapshotFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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
        return "git";
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

package gold.debug.windowstolinux.app.windows.workspace;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by the Windows desktop workspace boundary. / Windows 桌面工作区边界持有的失败类型。
 */
public enum WindowsWorkspaceFailureType implements FailureDefinition {
    /**
     * DIRECTORY UNAVAILABLE classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的目录不可用分类。
     */
    DIRECTORY_UNAVAILABLE("windows.workspace.directory-unavailable", "workspace", "windows.error.workspaceUnavailable",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DIRECTORY NOT WRITABLE classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的目录未可写分类。
     */
    DIRECTORY_NOT_WRITABLE("windows.workspace.not-writable", "workspace", "windows.error.workspaceNotWritable",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * CAPACITY INSUFFICIENT classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的容量不足分类。
     */
    CAPACITY_INSUFFICIENT("windows.workspace.capacity-insufficient", "workspace",
            "windows.error.workspaceCapacityInsufficient", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * APPLICATION ID INVALID classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的应用标识无效分类。
     */
    APPLICATION_ID_INVALID("windows.workspace.application-id-invalid", "validation",
            "windows.error.applicationIdInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ARCHIVE FAILED classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的归档失败分类。
     */
    ARCHIVE_FAILED("windows.workspace.archive-failed", "archive", "windows.error.archiveFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * BACKUP MATERIAL WORKSPACE FAILED classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的备份素材工作区失败分类。
     */
    BACKUP_MATERIAL_WORKSPACE_FAILED("windows.workspace.backup-material-failed", "backup",
            "windows.error.backupMaterialWorkspaceFailed", FailureRecoveryAction.CLEANUP),
    /**
     * RESTORE WORKSPACE FAILED classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的恢复工作区失败分类。
     */
    RESTORE_WORKSPACE_FAILED("windows.workspace.restore-failed", "restore", "windows.error.restoreWorkspaceFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * HANDOFF INVALID classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的交接无效分类。
     */
    HANDOFF_INVALID("windows.workspace.handoff-invalid", "handoff", "windows.error.workspaceHandoffInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION);

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
     * Binds the supplied dependencies and state for windows workspace failure type.
     * <p>为Windows工作区失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    WindowsWorkspaceFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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

package gold.debug.windowstolinux.app.windows.workspace;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;

/**
 * Failures owned by the Windows desktop workspace boundary. / Windows 桌面工作区边界持有的失败类型。
 */
public enum WindowsWorkspaceFailureType {
    /**
     * DIRECTORY UNAVAILABLE classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的目录不可用分类。
     */
    DIRECTORY_UNAVAILABLE("windows.workspace.directory-unavailable", "workspace", "windows.error.workspaceUnavailable",
            "REQUEST_USER_CORRECTION"),
    /**
     * DIRECTORY NOT WRITABLE classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的目录未可写分类。
     */
    DIRECTORY_NOT_WRITABLE("windows.workspace.not-writable", "workspace", "windows.error.workspaceNotWritable",
            "REQUEST_USER_CORRECTION"),
    /**
     * CAPACITY INSUFFICIENT classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的容量不足分类。
     */
    CAPACITY_INSUFFICIENT("windows.workspace.capacity-insufficient", "workspace",
            "windows.error.workspaceCapacityInsufficient", "REQUEST_USER_CORRECTION"),
    /**
     * APPLICATION ID INVALID classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的应用标识无效分类。
     */
    APPLICATION_ID_INVALID("windows.workspace.application-id-invalid", "validation",
            "windows.error.applicationIdInvalid", "REQUEST_USER_CORRECTION"),
    /**
     * ARCHIVE FAILED classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的归档失败分类。
     */
    ARCHIVE_FAILED("windows.workspace.archive-failed", "archive", "windows.error.archiveFailed", "CLEANUP"),
    /**
     * BACKUP MATERIAL WORKSPACE FAILED classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的备份素材工作区失败分类。
     */
    BACKUP_MATERIAL_WORKSPACE_FAILED("windows.workspace.backup-material-failed", "backup",
            "windows.error.backupMaterialWorkspaceFailed", "CLEANUP"),
    /**
     * RESTORE WORKSPACE FAILED classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的恢复工作区失败分类。
     */
    RESTORE_WORKSPACE_FAILED("windows.workspace.restore-failed", "restore", "windows.error.restoreWorkspaceFailed",
            "CLEANUP"),
    /**
     * HANDOFF INVALID classification within windows workspace failure type.
     * <p>Windows工作区失败类型中的交接无效分类。
     */
    HANDOFF_INVALID("windows.workspace.handoff-invalid", "handoff", "windows.error.workspaceHandoffInvalid",
            "REQUEST_USER_CORRECTION");

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
    private final String recoveryAction;

    /**
     * Binds the supplied dependencies and state for windows workspace failure type.
     * <p>为Windows工作区失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    WindowsWorkspaceFailureType(String code, String phase, String messageKey, String recoveryAction) {
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
    public String code() {
        return code;
    }

    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    public String domain() {
        return "windows";
    }

    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    public String phase() {
        return phase;
    }

    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    public String severity() {
        return "ERROR";
    }

    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    public String recoveryAction() {
        return recoveryAction;
    }

    /** Creates bounded public evidence with the original operation identity. / 以原操作身份创建有界公开依据。
     * @param operation original correlation UUID / 原关联UUID
     * @param diagnostic non-secret diagnostic message / 非秘密诊断消息
     * @return immutable public problem with recovery and localization evidence / 带恢复及本地化依据的不可变公开问题
     * @throws NullPointerException if a required value is absent / 必需值缺失时
     * @throws IllegalArgumentException if diagnostic text is blank / 诊断文本为空时 */
    public Problem problem(UUID operation, String diagnostic) {
        Objects.requireNonNull(operation, "operation");
        String message = Objects.requireNonNull(diagnostic, "diagnostic").trim();
        if (message.isBlank())
            throw new IllegalArgumentException("diagnostic must not be blank");
        return new Problem(problemCode(), code, message,
                Map.of("domain", domain(), "phase", phase, "messageKey", messageKey, "severity", severity(),
                        "recoveryAction", recoveryAction, "recoveryDisposition", "NOT_ATTEMPTED"),
                Optional.of(operation));
    }

    /** Selects the SDK category while preserving the precise event code. / 保留精确事件码并选择SDK类别。
     * @return stable public failure category / 稳定公开失败类别 */
    private ProblemCode problemCode() {
        return switch (this) {
            case DIRECTORY_UNAVAILABLE, CAPACITY_INSUFFICIENT -> ProblemCode.UNAVAILABLE;
            case DIRECTORY_NOT_WRITABLE -> ProblemCode.ACCESS_DENIED;
            case APPLICATION_ID_INVALID, HANDOFF_INVALID -> ProblemCode.INVALID_INPUT;
            case ARCHIVE_FAILED, BACKUP_MATERIAL_WORKSPACE_FAILED, RESTORE_WORKSPACE_FAILED -> ProblemCode.IO_FAILURE;
        };
    }
}

package gold.debug.windowstolinux.app.windows.uninstall.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/**
 * Failures owned by the Windows desktop uninstall boundary. / Windows 桌面卸载边界持有的失败类型。
 */
public enum DesktopUninstallFailureType {
    /**
     * DECISION REQUIRED classification within desktop uninstall failure type.
     * <p>Desktop卸载失败类型中的决定必需分类。
     */
    DECISION_REQUIRED("windows.uninstall.decision-required", "validation", "windows.error.uninstallDecisionRequired",
        "REQUEST_USER_CORRECTION"),
    /**
     * BOUNDARY INVALID classification within desktop uninstall failure type.
     * <p>Desktop卸载失败类型中的边界无效分类。
     */
    BOUNDARY_INVALID("windows.uninstall.boundary-invalid", "validation", "windows.error.uninstallBoundaryInvalid",
        "REQUEST_USER_CORRECTION"),
    /**
     * TASKS ACTIVE classification within desktop uninstall failure type.
     * <p>Desktop卸载失败类型中的任务集合活跃分类。
     */
    TASKS_ACTIVE("windows.uninstall.tasks-active", "quiesce", "windows.error.uninstallTasksActive", "RETRY"),
    /**
     * REMOVAL INCOMPLETE classification within desktop uninstall failure type.
     * <p>Desktop卸载失败类型中的移除未完成分类。
     */
    REMOVAL_INCOMPLETE("windows.uninstall.removal-incomplete", "removal", "windows.error.uninstallRemovalIncomplete",
        "REQUIRE_MANUAL_RECOVERY");

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
     * Binds the supplied dependencies and state for desktop uninstall failure type.
     * <p>为Desktop卸载失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    DesktopUninstallFailureType(String code, String phase, String messageKey, String recoveryAction) {
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
            case DECISION_REQUIRED, BOUNDARY_INVALID -> ProblemCode.INVALID_INPUT;
            case TASKS_ACTIVE -> ProblemCode.BUSY;
            case REMOVAL_INCOMPLETE -> ProblemCode.IO_FAILURE;
        };
    }
}

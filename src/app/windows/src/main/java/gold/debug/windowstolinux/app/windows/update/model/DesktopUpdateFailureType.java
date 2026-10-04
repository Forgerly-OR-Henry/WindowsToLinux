package gold.debug.windowstolinux.app.windows.update.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/**
 * Failures owned by the Windows desktop update boundary. / Windows 桌面更新边界持有的失败类型。
 */
public enum DesktopUpdateFailureType {
    /**
     * MANIFEST INVALID classification within desktop update failure type.
     * <p>Desktop更新失败类型中的清单无效分类。
     */
    MANIFEST_INVALID("windows.update.manifest-invalid", "validation", "windows.error.updateManifestInvalid",
        "REQUEST_USER_CORRECTION"),
    /**
     * SIGNATURE INVALID classification within desktop update failure type.
     * <p>Desktop更新失败类型中的签名无效分类。
     */
    SIGNATURE_INVALID("windows.update.signature-invalid", "verification", "windows.error.updateSignatureInvalid",
        "REQUEST_USER_CORRECTION"),
    /**
     * VERSION REJECTED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的版本已拒绝分类。
     */
    VERSION_REJECTED("windows.update.version-rejected", "verification", "windows.error.updateVersionRejected",
        "REQUEST_USER_CORRECTION"),
    /**
     * ARCHITECTURE REJECTED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的架构已拒绝分类。
     */
    ARCHITECTURE_REJECTED("windows.update.architecture-rejected", "verification",
        "windows.error.updateArchitectureRejected", "REQUEST_USER_CORRECTION"),
    /**
     * PACKAGE INVALID classification within desktop update failure type.
     * <p>Desktop更新失败类型中的软件包无效分类。
     */
    PACKAGE_INVALID("windows.update.package-invalid", "verification", "windows.error.updatePackageInvalid",
        "REQUEST_USER_CORRECTION"),
    /**
     * TRANSACTION FAILED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的事务失败分类。
     */
    TRANSACTION_FAILED("windows.update.transaction-failed", "update", "windows.error.updateTransactionFailed",
        "ROLLBACK"),
    /**
     * ROLLBACK FAILED classification within desktop update failure type.
     * <p>Desktop更新失败类型中的回滚失败分类。
     */
    ROLLBACK_FAILED("windows.update.rollback-failed", "rollback", "windows.error.updateRollbackFailed",
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
     * Binds the supplied dependencies and state for desktop update failure type.
     * <p>为Desktop更新失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    DesktopUpdateFailureType(String code, String phase, String messageKey, String recoveryAction) {
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
            case SIGNATURE_INVALID -> ProblemCode.ACCESS_DENIED;
            case VERSION_REJECTED, ARCHITECTURE_REJECTED -> ProblemCode.INCOMPATIBLE;
            case MANIFEST_INVALID, PACKAGE_INVALID -> ProblemCode.INVALID_INPUT;
            case TRANSACTION_FAILED, ROLLBACK_FAILED -> ProblemCode.IO_FAILURE;
        };
    }
}

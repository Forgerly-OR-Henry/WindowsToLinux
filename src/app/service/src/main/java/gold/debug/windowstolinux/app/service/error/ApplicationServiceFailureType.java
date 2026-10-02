package gold.debug.windowstolinux.app.service.error;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;

/**
 * Stable failures raised at the desktop application-service boundary. / 桌面应用服务边界抛出的稳定失败。
 */
public enum ApplicationServiceFailureType {
    /** An owned native window could not apply privacy. / 自有原生窗口无法应用隐私状态。 */
    SECURITY_PRIVACY_FAILED("service.security.privacy-failed", "security", "security.error.privacyFailed", "ERROR",
        "REQUEST_USER_CORRECTION"),
    /** New password does not meet the minimum length. / 新密码未满足最小长度。 */
    SECURITY_PASSWORD_INVALID("service.security.password-invalid", "security", "security.error.passwordInvalid",
        "WARNING", "REQUEST_USER_CORRECTION"),
    /** Interactive authorization is unavailable. / 交互授权不可用。
     */
    SECURITY_LOCKED("service.security.locked", "security", "security.error.locked", "WARNING",
        "REQUEST_USER_CORRECTION"),
    /** Accepted tasks prevent a security change. / 已接纳任务阻止安全变更。
     */
    SECURITY_BUSY("service.security.busy", "security", "security.error.busy", "WARNING", "REQUEST_USER_CORRECTION"),
    /** Repeated password input differs. / 重复密码输入不一致。
     */
    SECURITY_PASSWORD_MISMATCH("service.security.password-mismatch", "security", "security.error.passwordMismatch",
        "WARNING", "REQUEST_USER_CORRECTION"),
    /** Security persistence could not be initialized. / 无法初始化安全持久化。
     */
    SECURITY_UNAVAILABLE("service.security.unavailable", "security", "security.error.unavailable", "ERROR",
        "REQUIRE_MANUAL_RECOVERY"),
    /** Approval models are required before Agent side effects. / Agent 副作用前必须存在审批模型。 */
    APPROVAL_MODEL_REQUIRED("service.ai.approval-model-required", "ai", "service.error.approvalModelRequired", "ERROR",
        "REQUEST_USER_CORRECTION"),
    /** Assisted and Agent tasks require deployment models. / AI 辅助 及 Agent 任务需要部署模型。 */
    DEPLOYMENT_MODEL_REQUIRED("service.ai.deployment-model-required", "ai", "service.error.deploymentModelRequired",
        "ERROR", "REQUEST_USER_CORRECTION"),

    /**
     * RECOVERY PROBE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复探测失败分类。
     */
    RECOVERY_PROBE_FAILED("service.recovery.probe-failed", "recovery", "service.error.recoveryProbeFailed", "ERROR",
        "REQUEST_USER_CORRECTION"),
    /**
     * RECOVERY INTERRUPTED classification within application service failure type.
     * <p>应用服务失败类型中的恢复已中断分类。
     */
    RECOVERY_INTERRUPTED("service.recovery.interrupted", "recovery", "service.error.recoveryInterrupted", "WARNING",
        "NONE"),
    /**
     * RECOVERY OBSERVATION FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复观测失败分类。
     */
    RECOVERY_OBSERVATION_FAILED("service.recovery.observation-failed", "recovery",
        "service.error.recoveryObservationFailed", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * RECOVERY CLEANUP FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复清理失败分类。
     */
    RECOVERY_CLEANUP_FAILED("service.recovery.cleanup-failed", "cleanup", "service.error.recoveryCleanupFailed",
        "ERROR", "REQUIRE_MANUAL_RECOVERY"),
    /**
     * AI CONFIGURATION TEST FAILED classification within application service failure type.
     * <p>应用服务失败类型中的AI配置测试失败分类。
     */
    AI_CONFIGURATION_TEST_FAILED("service.ai.configuration-test-failed", "ai",
        "service.error.aiConfigurationTestFailed", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * DATABASE AUTH REQUIRED classification within application service failure type.
     * <p>应用服务失败类型中的数据库认证必需分类。
     */
    DATABASE_AUTH_REQUIRED("service.database.auth-required", "database", "service.error.databaseAuthRequired", "ERROR",
        "REQUEST_USER_CORRECTION"),
    /**
     * DATABASE STATE CHANGED classification within application service failure type.
     * <p>应用服务失败类型中的数据库状态已变化分类。
     */
    DATABASE_STATE_CHANGED("service.database.state-changed", "database", "service.error.databaseStateChanged", "ERROR",
        "REQUEST_USER_CORRECTION"),
    /**
     * DATABASE INITIALIZATION FAILED classification within application service failure type.
     * <p>应用服务失败类型中的数据库初始化失败分类。
     */
    DATABASE_INITIALIZATION_FAILED("service.database.initialization-failed", "database",
        "service.error.databaseInitializationFailed", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * DATABASE MANUAL RESTORE REQUIRED classification within application service failure type.
     * <p>应用服务失败类型中的数据库人工恢复必需分类。
     */
    DATABASE_MANUAL_RESTORE_REQUIRED("service.database.manual-restore-required", "database",
        "service.error.databaseManualRestoreRequired", "ERROR", "REQUIRE_MANUAL_RECOVERY"),
    /**
     * DATABASE VERSION UNSUPPORTED classification within application service failure type.
     * <p>应用服务失败类型中的数据库版本不支持分类。
     */
    DATABASE_VERSION_UNSUPPORTED("service.database.version-unsupported", "database",
        "service.error.databaseVersionUnsupported", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * DATABASE ACTION FAILED classification within application service failure type.
     * <p>应用服务失败类型中的数据库动作失败分类。
     */
    DATABASE_ACTION_FAILED("service.database.action-failed", "database", "service.error.databaseActionFailed", "ERROR",
        "REQUEST_USER_CORRECTION"),
    /**
     * DEPLOYMENT ANALYSIS REQUIRED classification within application service failure type.
     * <p>应用服务失败类型中的部署分析必需分类。
     */
    DEPLOYMENT_ANALYSIS_REQUIRED("service.deployment.analysis-required", "deployment",
        "service.error.deploymentAnalysisRequired", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * APPLICATION SECRET REFERENCE MISSING classification within application service failure type.
     * <p>应用服务失败类型中的应用秘密引用缺失分类。
     */
    APPLICATION_SECRET_REFERENCE_MISSING("service.secret.reference-missing", "secret",
        "service.error.applicationSecretReferenceMissing", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * LIFECYCLE CONTEXT MISMATCH classification within application service failure type.
     * <p>应用服务失败类型中的生命周期上下文不匹配分类。
     */
    LIFECYCLE_CONTEXT_MISMATCH("service.lifecycle.context-mismatch", "lifecycle",
        "service.error.lifecycleContextMismatch", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * MANAGED SUMMARY READ FAILED classification within application service failure type.
     * <p>应用服务失败类型中的受管摘要读取失败分类。
     */
    MANAGED_SUMMARY_READ_FAILED("service.persistence.summary-read-failed", "persistence",
        "service.error.managedSummaryReadFailed", "ERROR", "RESTART_APPLICATION"),
    /**
     * APPLICATION NOT SELECTED classification within application service failure type.
     * <p>应用服务失败类型中的应用未已选分类。
     */
    APPLICATION_NOT_SELECTED("service.lifecycle.application-not-selected", "lifecycle",
        "service.error.applicationNotSelected", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * LEGACY RUNTIME MISSING classification within application service failure type.
     * <p>应用服务失败类型中的历史运行时缺失分类。
     */
    LEGACY_RUNTIME_MISSING("service.lifecycle.runtime-missing", "lifecycle", "service.error.runtimeMissing", "ERROR",
        "REQUIRE_MANUAL_RECOVERY"),
    /**
     * SERVER PROFILE MISSING classification within application service failure type.
     * <p>应用服务失败类型中的服务器配置资料缺失分类。
     */
    SERVER_PROFILE_MISSING("service.lifecycle.server-profile-missing", "lifecycle",
        "service.error.serverProfileMissing", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * APPLICATION NOT MANAGED classification within application service failure type.
     * <p>应用服务失败类型中的应用未受管分类。
     */
    APPLICATION_NOT_MANAGED("service.lifecycle.application-not-managed", "lifecycle",
        "service.error.applicationNotManaged", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * APPLICATION SERVER CONFLICT classification within application service failure type.
     * <p>应用服务失败类型中的应用服务器冲突分类。
     */
    APPLICATION_SERVER_CONFLICT("service.deployment.server-conflict", "deployment",
        "service.error.applicationServerConflict", "ERROR", "REQUEST_USER_CORRECTION"),
    /**
     * APPLICATION IDENTITY INVALID classification within application service failure type.
     * <p>应用服务失败类型中的应用身份无效分类。
     */
    APPLICATION_IDENTITY_INVALID("service.deployment.identity-invalid", "deployment",
        "service.error.applicationIdentityInvalid", "ERROR", "REQUIRE_MANUAL_RECOVERY"),
    /**
     * LOCAL OBSERVATION SAVE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的本地观测保存失败分类。
     */
    LOCAL_OBSERVATION_SAVE_FAILED("service.persistence.observation-save-failed", "persistence",
        "service.error.localObservationSaveFailed", "WARNING", "RETRY"),
    /**
     * DEPLOYMENT RECORD SAVE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的部署记录保存失败分类。
     */
    DEPLOYMENT_RECORD_SAVE_FAILED("service.persistence.deployment-record-save-failed", "persistence",
        "service.error.deploymentRecordSaveFailed", "WARNING", "REQUIRE_MANUAL_RECOVERY"),
    /**
     * BACKUP INPUT INCOMPLETE classification within application service failure type.
     * <p>应用服务失败类型中的备份输入未完成分类。
     */
    BACKUP_INPUT_INCOMPLETE("service.backup.input-incomplete", "backup", "service.error.backupInputIncomplete", "ERROR",
        "REQUEST_USER_CORRECTION"),
    /**
     * BACKUP RECOVERY FAILED classification within application service failure type.
     * <p>应用服务失败类型中的备份恢复失败分类。
     */
    BACKUP_RECOVERY_FAILED("service.backup.recovery-failed", "backup", "service.error.backupRecoveryFailed", "ERROR",
        "REQUIRE_MANUAL_RECOVERY"),
    /**
     * RESTORE RECORD SAVE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复记录保存失败分类。
     */
    RESTORE_RECORD_SAVE_FAILED("service.restore.record-save-failed", "restore", "service.error.restoreRecordSaveFailed",
        "ERROR", "REQUIRE_MANUAL_RECOVERY");

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
    private final String severity;

    /**
     * Action required to recover from the classified failure.
     * <p>从已分类失败中恢复所需的动作。
     */
    private final String recoveryAction;

    /**
     * Binds the supplied dependencies and state for application service failure type.
     * <p>为应用服务失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param severity severity level assigned to the failure definition / 分配给失败定义的严重级别
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    ApplicationServiceFailureType(String code, String phase, String messageKey, String severity,
                                  String recoveryAction) {
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
        return "service";
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
     * Returns severity level assigned to the failure definition.
     * <p>返回分配给失败定义的严重级别。
     *
     * @return severity level assigned to the failure definition / 分配给失败定义的严重级别
     */
    public String severity() {
        return severity;
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

    /** Creates immutable public evidence for the original occurrence. / 创建原发生事件的不可变公开依据。
     * @param occurrence original diagnostic UUID / 原诊断UUID
     * @param arguments literal localized message values / 本地化消息的字面值
     * @param diagnostic nonsecret diagnostic text / 非秘密诊断文本
     * @return exact public event and recovery context / 精确公开事件及恢复上下文
     * @throws NullPointerException if a required value is absent / 必需值缺失时
     * @throws IllegalArgumentException if the diagnostic is blank / 诊断为空时 */
    public Problem problem(UUID occurrence, Map<String, ?> arguments, String diagnostic) {
        Objects.requireNonNull(occurrence, "occurrence");
        String message = Objects.requireNonNull(diagnostic, "diagnostic").trim();
        if (message.isBlank())
            throw new IllegalArgumentException("diagnostic must not be blank");
        var context = new HashMap<String, String>();
        context.putAll(Map.of("domain", domain(), "phase", phase, "messageKey", messageKey, "severity", severity,
            "recoveryAction", recoveryAction, "recoveryDisposition", "NOT_ATTEMPTED"));
        Objects.requireNonNull(arguments, "arguments").forEach((key, value) -> {
            Objects.requireNonNull(key, "argument name");
            if (key.isBlank())
                throw new IllegalArgumentException("argument name must not be blank");
            context.put("argument." + key, String.valueOf(value));
        });
        return new Problem(problemCode(), code, message, context, Optional.of(occurrence));
    }

    /** Maps the actual service failure to its public category. / 将实际服务失败映射为公开分类。
     * @return stable SDK failure category / 稳定SDK失败分类 */
    private ProblemCode problemCode() {
        return switch (this) {
            case SECURITY_BUSY -> ProblemCode.BUSY;
            case SECURITY_LOCKED, DATABASE_AUTH_REQUIRED -> ProblemCode.ACCESS_DENIED;
            case SECURITY_PASSWORD_INVALID, SECURITY_PASSWORD_MISMATCH, LIFECYCLE_CONTEXT_MISMATCH,
                 APPLICATION_NOT_SELECTED, APPLICATION_IDENTITY_INVALID, APPLICATION_SERVER_CONFLICT,
                 BACKUP_INPUT_INCOMPLETE -> ProblemCode.INVALID_INPUT;
            case APPROVAL_MODEL_REQUIRED, DEPLOYMENT_MODEL_REQUIRED, DEPLOYMENT_ANALYSIS_REQUIRED,
                 SECURITY_UNAVAILABLE -> ProblemCode.UNAVAILABLE;
            case APPLICATION_SECRET_REFERENCE_MISSING, LEGACY_RUNTIME_MISSING, SERVER_PROFILE_MISSING,
                 APPLICATION_NOT_MANAGED -> ProblemCode.NOT_FOUND;
            case DATABASE_VERSION_UNSUPPORTED -> ProblemCode.INCOMPATIBLE;
            case DATABASE_STATE_CHANGED -> ProblemCode.UNKNOWN;
            case RECOVERY_INTERRUPTED -> ProblemCode.CANCELLED;
            default -> ProblemCode.IO_FAILURE;
        };
    }
}

package gold.debug.windowstolinux.app.service.failure;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Stable failures raised at the desktop application-service boundary. / 桌面应用服务边界抛出的稳定失败。
 */
public enum ApplicationServiceFailureType implements FailureDefinition {
    /** Approval models are required before Agent side effects. / Agent 副作用前必须存在审批模型。 */
    APPROVAL_MODEL_REQUIRED("service.ai.approval-model-required", "ai", "service.error.approvalModelRequired", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /** Assisted and Agent tasks require deployment models. / AI 辅助 及 Agent 任务需要部署模型。 */
    DEPLOYMENT_MODEL_REQUIRED("service.ai.deployment-model-required", "ai", "service.error.deploymentModelRequired", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),

    /**
     * RECOVERY PROBE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复探测失败分类。
     */
    RECOVERY_PROBE_FAILED("service.recovery.probe-failed", "recovery", "service.error.recoveryProbeFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * RECOVERY INTERRUPTED classification within application service failure type.
     * <p>应用服务失败类型中的恢复已中断分类。
     */
    RECOVERY_INTERRUPTED("service.recovery.interrupted", "recovery", "service.error.recoveryInterrupted", FailureSeverityLevel.WARNING, FailureRecoveryAction.NONE),
    /**
     * RECOVERY OBSERVATION FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复观测失败分类。
     */
    RECOVERY_OBSERVATION_FAILED("service.recovery.observation-failed", "recovery", "service.error.recoveryObservationFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * RECOVERY CLEANUP FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复清理失败分类。
     */
    RECOVERY_CLEANUP_FAILED("service.recovery.cleanup-failed", "cleanup", "service.error.recoveryCleanupFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * AI CONFIGURATION TEST FAILED classification within application service failure type.
     * <p>应用服务失败类型中的AI配置测试失败分类。
     */
    AI_CONFIGURATION_TEST_FAILED("service.ai.configuration-test-failed", "ai", "service.error.aiConfigurationTestFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE AUTH REQUIRED classification within application service failure type.
     * <p>应用服务失败类型中的数据库认证必需分类。
     */
    DATABASE_AUTH_REQUIRED("service.database.auth-required", "database",
            "service.error.databaseAuthRequired", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE STATE CHANGED classification within application service failure type.
     * <p>应用服务失败类型中的数据库状态已变化分类。
     */
    DATABASE_STATE_CHANGED("service.database.state-changed", "database",
            "service.error.databaseStateChanged", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE INITIALIZATION FAILED classification within application service failure type.
     * <p>应用服务失败类型中的数据库初始化失败分类。
     */
    DATABASE_INITIALIZATION_FAILED("service.database.initialization-failed", "database",
            "service.error.databaseInitializationFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE MANUAL RESTORE REQUIRED classification within application service failure type.
     * <p>应用服务失败类型中的数据库人工恢复必需分类。
     */
    DATABASE_MANUAL_RESTORE_REQUIRED("service.database.manual-restore-required", "database",
            "service.error.databaseManualRestoreRequired", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * DATABASE VERSION UNSUPPORTED classification within application service failure type.
     * <p>应用服务失败类型中的数据库版本不支持分类。
     */
    DATABASE_VERSION_UNSUPPORTED("service.database.version-unsupported", "database",
            "service.error.databaseVersionUnsupported", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE ACTION FAILED classification within application service failure type.
     * <p>应用服务失败类型中的数据库动作失败分类。
     */
    DATABASE_ACTION_FAILED("service.database.action-failed", "database",
            "service.error.databaseActionFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * STORAGE MODE MISMATCH classification within application service failure type.
     * <p>应用服务失败类型中的存储模式不匹配分类。
     */
    STORAGE_MODE_MISMATCH("service.validation.storage-mode-mismatch", "validation",
            "service.error.storageModeMismatch", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DEPLOYMENT ANALYSIS REQUIRED classification within application service failure type.
     * <p>应用服务失败类型中的部署分析必需分类。
     */
    DEPLOYMENT_ANALYSIS_REQUIRED("service.deployment.analysis-required", "deployment",
            "service.error.deploymentAnalysisRequired", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * APPLICATION SECRET REFERENCE MISSING classification within application service failure type.
     * <p>应用服务失败类型中的应用秘密引用缺失分类。
     */
    APPLICATION_SECRET_REFERENCE_MISSING("service.secret.reference-missing", "secret",
            "service.error.applicationSecretReferenceMissing", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * LIFECYCLE CONTEXT MISMATCH classification within application service failure type.
     * <p>应用服务失败类型中的生命周期上下文不匹配分类。
     */
    LIFECYCLE_CONTEXT_MISMATCH("service.lifecycle.context-mismatch", "lifecycle",
            "service.error.lifecycleContextMismatch", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * MANAGED SUMMARY READ FAILED classification within application service failure type.
     * <p>应用服务失败类型中的受管摘要读取失败分类。
     */
    MANAGED_SUMMARY_READ_FAILED("service.persistence.summary-read-failed", "persistence",
            "service.error.managedSummaryReadFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.RESTART_APPLICATION),
    /**
     * APPLICATION NOT SELECTED classification within application service failure type.
     * <p>应用服务失败类型中的应用未已选分类。
     */
    APPLICATION_NOT_SELECTED("service.lifecycle.application-not-selected", "lifecycle",
            "service.error.applicationNotSelected", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * LEGACY RUNTIME MISSING classification within application service failure type.
     * <p>应用服务失败类型中的历史运行时缺失分类。
     */
    LEGACY_RUNTIME_MISSING("service.lifecycle.runtime-missing", "lifecycle",
            "service.error.runtimeMissing", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * SERVER PROFILE MISSING classification within application service failure type.
     * <p>应用服务失败类型中的服务器配置资料缺失分类。
     */
    SERVER_PROFILE_MISSING("service.lifecycle.server-profile-missing", "lifecycle",
            "service.error.serverProfileMissing", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * APPLICATION NOT MANAGED classification within application service failure type.
     * <p>应用服务失败类型中的应用未受管分类。
     */
    APPLICATION_NOT_MANAGED("service.lifecycle.application-not-managed", "lifecycle",
            "service.error.applicationNotManaged", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * APPLICATION SERVER CONFLICT classification within application service failure type.
     * <p>应用服务失败类型中的应用服务器冲突分类。
     */
    APPLICATION_SERVER_CONFLICT("service.deployment.server-conflict", "deployment",
            "service.error.applicationServerConflict", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * APPLICATION IDENTITY INVALID classification within application service failure type.
     * <p>应用服务失败类型中的应用身份无效分类。
     */
    APPLICATION_IDENTITY_INVALID("service.deployment.identity-invalid", "deployment",
            "service.error.applicationIdentityInvalid", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * LOCAL OBSERVATION SAVE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的本地观测保存失败分类。
     */
    LOCAL_OBSERVATION_SAVE_FAILED("service.persistence.observation-save-failed", "persistence",
            "service.error.localObservationSaveFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.RETRY),
    /**
     * DEPLOYMENT RECORD SAVE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的部署记录保存失败分类。
     */
    DEPLOYMENT_RECORD_SAVE_FAILED("service.persistence.deployment-record-save-failed", "persistence",
            "service.error.deploymentRecordSaveFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * BACKUP INPUT INCOMPLETE classification within application service failure type.
     * <p>应用服务失败类型中的备份输入未完成分类。
     */
    BACKUP_INPUT_INCOMPLETE("service.backup.input-incomplete", "backup",
            "service.error.backupInputIncomplete", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * BACKUP RECOVERY FAILED classification within application service failure type.
     * <p>应用服务失败类型中的备份恢复失败分类。
     */
    BACKUP_RECOVERY_FAILED("service.backup.recovery-failed", "backup",
            "service.error.backupRecoveryFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * RESTORE RECORD SAVE FAILED classification within application service failure type.
     * <p>应用服务失败类型中的恢复记录保存失败分类。
     */
    RESTORE_RECORD_SAVE_FAILED("service.restore.record-save-failed", "restore",
            "service.error.restoreRecordSaveFailed", FailureSeverityLevel.ERROR,
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
     * Binds the supplied dependencies and state for application service failure type.
     * <p>为应用服务失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param severity severity level assigned to the failure definition / 分配给失败定义的严重级别
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    ApplicationServiceFailureType(String code, String phase, String messageKey,
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
    @Override public String domain() { return "service"; }
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

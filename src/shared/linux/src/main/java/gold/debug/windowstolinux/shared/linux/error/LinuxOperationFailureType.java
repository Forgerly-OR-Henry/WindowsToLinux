package gold.debug.windowstolinux.shared.linux.error;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by Linux connection, inspection, transfer and runtime operations. / Linux 连接、采集、传输与运行操作持有的失败类型。
 */
public enum LinuxOperationFailureType implements FailureDefinition {
    /**
     * EXTERNAL IDENTITY CHANGED classification within linux operation failure type.
     * <p>Linux操作失败类型中的外部身份已变化分类。
     */
    EXTERNAL_IDENTITY_CHANGED("linux.lifecycle.external-identity-changed", "lifecycle",
            "linux.error.externalIdentityChanged", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * EXTERNAL OWNERSHIP REQUIRED classification within linux operation failure type.
     * <p>Linux操作失败类型中的外部归属必需分类。
     */
    EXTERNAL_OWNERSHIP_REQUIRED("linux.lifecycle.external-ownership-required", "lifecycle",
            "linux.error.externalOwnershipRequired", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * EXTERNAL OPERATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的外部操作失败分类。
     */
    EXTERNAL_OPERATION_FAILED("linux.lifecycle.external-operation-failed", "lifecycle",
            "linux.error.externalOperationFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * AUTHENTICATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的认证失败分类。
     */
    AUTHENTICATION_FAILED("linux.connection.authentication-failed", "connection", "linux.error.authenticationFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * CONNECTION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的连接失败分类。
     */
    CONNECTION_FAILED("linux.connection.connection-failed", "connection", "linux.error.connectionFailed",
            FailureRecoveryAction.RECONNECT),
    /**
     * HOST KEY REJECTED classification within linux operation failure type.
     * <p>Linux操作失败类型中的主机键已拒绝分类。
     */
    HOST_KEY_REJECTED("linux.connection.host-key-rejected", "connection", "linux.error.hostKeyRejected",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * SSH COMMAND FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的SSH命令失败分类。
     */
    SSH_COMMAND_FAILED("linux.command.ssh-command-failed", "command", "linux.error.sshCommandFailed",
            FailureRecoveryAction.RETRY),
    /**
     * COMMAND FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的命令失败分类。
     */
    COMMAND_FAILED("linux.command.command-failed", "command", "linux.error.commandFailed", FailureRecoveryAction.RETRY),
    /**
     * CAPABILITY COLLECTION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的能力采集失败分类。
     */
    CAPABILITY_COLLECTION_FAILED("linux.capability.collection-failed", "capability",
            "linux.error.capabilityCollectionFailed", FailureRecoveryAction.RETRY),
    /**
     * DEPLOYMENT CAPABILITY COLLECTION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的部署能力采集失败分类。
     */
    DEPLOYMENT_CAPABILITY_COLLECTION_FAILED("linux.capability.deployment-collection-failed", "capability",
            "linux.error.deploymentCapabilityCollectionFailed", FailureRecoveryAction.RETRY),
    /**
     * DEPLOYMENT INPUT STAGING FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的部署输入暂存失败分类。
     */
    DEPLOYMENT_INPUT_STAGING_FAILED("linux.deployment.input-staging-failed", "deployment",
            "linux.error.deploymentInputStagingFailed", FailureRecoveryAction.CLEANUP),
    /**
     * DATABASE PREFLIGHT FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的数据库预检失败分类。
     */
    DATABASE_PREFLIGHT_FAILED("linux.database.preflight-failed", "database", "linux.error.databasePreflightFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE BACKUP FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的数据库备份失败分类。
     */
    DATABASE_BACKUP_FAILED("linux.database.backup-failed", "database", "linux.error.databaseBackupFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * DATABASE RESTORE FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的数据库恢复失败分类。
     */
    DATABASE_RESTORE_FAILED("linux.database.restore-failed", "database", "linux.error.databaseRestoreFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * DATABASE EVIDENCE INVALID classification within linux operation failure type.
     * <p>Linux操作失败类型中的数据库证据无效分类。
     */
    DATABASE_EVIDENCE_INVALID("linux.database.evidence-invalid", "database", "linux.error.databaseEvidenceInvalid",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * BACKUP ARTIFACT CREATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的备份制品创建失败分类。
     */
    BACKUP_ARTIFACT_CREATION_FAILED("linux.backup.artifact-creation-failed", "backup",
            "linux.error.backupArtifactCreationFailed", FailureRecoveryAction.CLEANUP),
    /**
     * BACKUP ARTIFACT EVIDENCE INVALID classification within linux operation failure type.
     * <p>Linux操作失败类型中的备份制品证据无效分类。
     */
    BACKUP_ARTIFACT_EVIDENCE_INVALID("linux.backup.artifact-evidence-invalid", "backup",
            "linux.error.backupArtifactEvidenceInvalid", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * BACKUP ARTIFACT TRANSFER FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的备份制品传输失败分类。
     */
    BACKUP_ARTIFACT_TRANSFER_FAILED("linux.backup.artifact-transfer-failed", "backup",
            "linux.error.backupArtifactTransferFailed", FailureRecoveryAction.CLEANUP),
    /**
     * BACKUP OPERATION CLEANUP FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的备份操作清理失败分类。
     */
    BACKUP_OPERATION_CLEANUP_FAILED("linux.backup.operation-cleanup-failed", "backup",
            "linux.error.backupOperationCleanupFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * CANDIDATE PREPARATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的候选准备失败分类。
     */
    CANDIDATE_PREPARATION_FAILED("linux.deployment.candidate-preparation-failed", "deployment",
            "linux.error.candidatePreparationFailed", FailureRecoveryAction.CLEANUP),
    /**
     * SOURCE UPLOAD FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的源码上传失败分类。
     */
    SOURCE_UPLOAD_FAILED("linux.transfer.source-upload-failed", "transfer", "linux.error.sourceUploadFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * UPLOAD VERIFICATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的上传验证失败分类。
     */
    UPLOAD_VERIFICATION_FAILED("linux.transfer.upload-verification-failed", "transfer",
            "linux.error.uploadVerificationFailed", FailureRecoveryAction.CLEANUP),
    /**
     * RESTORE SOURCE INVALID classification within linux operation failure type.
     * <p>Linux操作失败类型中的恢复源码无效分类。
     */
    RESTORE_SOURCE_INVALID("linux.restore.source-invalid", "restore", "linux.error.restoreSourceInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * RESTORE UPLOAD FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的恢复上传失败分类。
     */
    RESTORE_UPLOAD_FAILED("linux.restore.upload-failed", "restore", "linux.error.restoreUploadFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * RESTORE UPLOAD VERIFICATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的恢复上传验证失败分类。
     */
    RESTORE_UPLOAD_VERIFICATION_FAILED("linux.restore.upload-verification-failed", "restore",
            "linux.error.restoreUploadVerificationFailed", FailureRecoveryAction.CLEANUP),
    /**
     * RESTORE ACTIVATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的恢复激活失败分类。
     */
    RESTORE_ACTIVATION_FAILED("linux.restore.activation-failed", "restore", "linux.error.restoreActivationFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * RESTORE RECOVERY FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的恢复恢复失败分类。
     */
    RESTORE_RECOVERY_FAILED("linux.restore.recovery-failed", "restore", "linux.error.restoreRecoveryFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * LOCAL ARCHIVE MISSING classification within linux operation failure type.
     * <p>Linux操作失败类型中的本地归档缺失分类。
     */
    LOCAL_ARCHIVE_MISSING("linux.transfer.local-archive-missing", "transfer", "linux.error.localArchiveMissing",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * LOCAL ARCHIVE READ FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的本地归档读取失败分类。
     */
    LOCAL_ARCHIVE_READ_FAILED("linux.transfer.local-archive-read-failed", "transfer",
            "linux.error.localArchiveReadFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * LOCAL ARCHIVE SIZE MISMATCH classification within linux operation failure type.
     * <p>Linux操作失败类型中的本地归档大小不匹配分类。
     */
    LOCAL_ARCHIVE_SIZE_MISMATCH("linux.transfer.local-archive-size-mismatch", "transfer",
            "linux.error.localArchiveSizeMismatch", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * SNAPSHOT IDENTITY UNVERIFIED classification within linux operation failure type.
     * <p>Linux操作失败类型中的快照身份未验证分类。
     */
    SNAPSHOT_IDENTITY_UNVERIFIED("linux.deployment.snapshot-identity-unverified", "deployment",
            "linux.error.snapshotIdentityUnverified", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * SNAPSHOT TOKEN MISSING classification within linux operation failure type.
     * <p>Linux操作失败类型中的快照令牌缺失分类。
     */
    SNAPSHOT_TOKEN_MISSING("linux.deployment.snapshot-token-missing", "deployment", "linux.error.snapshotTokenMissing",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * UNVERIFIED BUILD PUBLISH classification within linux operation failure type.
     * <p>Linux操作失败类型中的未验证构建发布分类。
     */
    UNVERIFIED_BUILD_PUBLISH("linux.deployment.build-publish-unverified", "deployment",
            "linux.error.unverifiedBuildPublish", FailureRecoveryAction.ROLLBACK),
    /**
     * UNVERIFIED BUILD ROLLBACK classification within linux operation failure type.
     * <p>Linux操作失败类型中的未验证构建回滚分类。
     */
    UNVERIFIED_BUILD_ROLLBACK("linux.deployment.build-rollback-unverified", "rollback",
            "linux.error.unverifiedBuildRollback", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * ROOT BUILD REQUIRES ROOT SESSION classification within linux operation failure type.
     * <p>Linux操作失败类型中的根目录构建要求集合根目录会话分类。
     */
    ROOT_BUILD_REQUIRES_ROOT_SESSION("linux.build.root-session-required", "build",
            "linux.error.rootBuildRequiresRootSession", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ENVIRONMENT PREPARATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的环境准备失败分类。
     */
    ENVIRONMENT_PREPARATION_FAILED("linux.environment.preparation-failed", "environment",
            "linux.error.environmentPreparationFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * ENVIRONMENT REQUIREMENTS UNMET classification within linux operation failure type.
     * <p>Linux操作失败类型中的环境要求集合未满足分类。
     */
    ENVIRONMENT_REQUIREMENTS_UNMET("linux.environment.requirements-unmet", "environment",
            "linux.error.environmentRequirementsUnmet", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ENVIRONMENT UNSUPPORTED DISTRO classification within linux operation failure type.
     * <p>Linux操作失败类型中的环境不支持发行版分类。
     */
    ENVIRONMENT_UNSUPPORTED_DISTRO("linux.environment.unsupported-distro", "environment",
            "linux.error.environmentUnsupportedDistro", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * RUNTIME OBSERVATION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的运行时观测失败分类。
     */
    RUNTIME_OBSERVATION_FAILED("linux.runtime.observation-failed", "runtime", "linux.error.runtimeObservationFailed",
            FailureRecoveryAction.RECONNECT),
    /**
     * LIFECYCLE ACTION FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的生命周期动作失败分类。
     */
    LIFECYCLE_ACTION_FAILED("linux.lifecycle.action-failed", "lifecycle", "linux.error.lifecycleActionFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * START REQUIRES STOPPED classification within linux operation failure type.
     * <p>Linux操作失败类型中的启动要求集合已停止分类。
     */
    START_REQUIRES_STOPPED("linux.lifecycle.start-requires-stopped", "lifecycle", "linux.error.startRequiresStopped",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * POST START HEALTH FAILED classification within linux operation failure type.
     * <p>Linux操作失败类型中的后置启动健康失败分类。
     */
    POST_START_HEALTH_FAILED("linux.lifecycle.post-start-health-failed", "health", "linux.error.postStartHealthFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * HEALTH CHECK UNSUPPORTED classification within linux operation failure type.
     * <p>Linux操作失败类型中的健康检查不支持分类。
     */
    HEALTH_CHECK_UNSUPPORTED("linux.health.check-unsupported", "health", "linux.error.healthCheckUnsupported",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * STOP UNVERIFIED classification within linux operation failure type.
     * <p>Linux操作失败类型中的停止未验证分类。
     */
    STOP_UNVERIFIED("linux.lifecycle.stop-unverified", "lifecycle", "linux.error.stopUnverified",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * STOP WAIT INTERRUPTED classification within linux operation failure type.
     * <p>Linux操作失败类型中的停止等待已中断分类。
     */
    STOP_WAIT_INTERRUPTED("linux.lifecycle.stop-wait-interrupted", "lifecycle", "linux.error.stopWaitInterrupted",
            FailureRecoveryAction.NONE),
    /**
     * MAIN PROCESS STILL RUNNING classification within linux operation failure type.
     * <p>Linux操作失败类型中的主进程仍然运行中分类。
     */
    MAIN_PROCESS_STILL_RUNNING("linux.lifecycle.main-process-still-running", "lifecycle",
            "linux.error.mainProcessStillRunning", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * ENABLE AUTOSTART UNVERIFIED classification within linux operation failure type.
     * <p>Linux操作失败类型中的启用自动启动未验证分类。
     */
    ENABLE_AUTOSTART_UNVERIFIED("linux.lifecycle.enable-autostart-unverified", "lifecycle",
            "linux.error.enableAutostartUnverified", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * DISABLE AUTOSTART UNVERIFIED classification within linux operation failure type.
     * <p>Linux操作失败类型中的禁用自动启动未验证分类。
     */
    DISABLE_AUTOSTART_UNVERIFIED("linux.lifecycle.disable-autostart-unverified", "lifecycle",
            "linux.error.disableAutostartUnverified", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

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
     * Binds the supplied dependencies and state for linux operation failure type.
     * <p>为Linux操作失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    LinuxOperationFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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

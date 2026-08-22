package gold.debug.windowstolinux.shared.linux.error;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by Linux connection, inspection, transfer and runtime operations. / Linux 连接、采集、传输与运行操作持有的失败类型。 */
public enum LinuxOperationFailureType implements FailureDefinition {
    AUTHENTICATION_FAILED("linux.connection.authentication-failed", "connection", "linux.error.authenticationFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    CONNECTION_FAILED("linux.connection.connection-failed", "connection", "linux.error.connectionFailed", FailureRecoveryAction.RECONNECT),
    HOST_KEY_REJECTED("linux.connection.host-key-rejected", "connection", "linux.error.hostKeyRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    SSH_COMMAND_FAILED("linux.command.ssh-command-failed", "command", "linux.error.sshCommandFailed", FailureRecoveryAction.RETRY),
    COMMAND_FAILED("linux.command.command-failed", "command", "linux.error.commandFailed", FailureRecoveryAction.RETRY),
    CAPABILITY_COLLECTION_FAILED("linux.capability.collection-failed", "capability", "linux.error.capabilityCollectionFailed", FailureRecoveryAction.RETRY),
    DEPLOYMENT_CAPABILITY_COLLECTION_FAILED("linux.capability.deployment-collection-failed", "capability", "linux.error.deploymentCapabilityCollectionFailed", FailureRecoveryAction.RETRY),
    DEPLOYMENT_INPUT_STAGING_FAILED("linux.deployment.input-staging-failed", "deployment", "linux.error.deploymentInputStagingFailed", FailureRecoveryAction.CLEANUP),
    DATABASE_PREFLIGHT_FAILED("linux.database.preflight-failed", "database", "linux.error.databasePreflightFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    DATABASE_BACKUP_FAILED("linux.database.backup-failed", "database", "linux.error.databaseBackupFailed", FailureRecoveryAction.CLEANUP),
    DATABASE_RESTORE_FAILED("linux.database.restore-failed", "database", "linux.error.databaseRestoreFailed", FailureRecoveryAction.CLEANUP),
    DATABASE_EVIDENCE_INVALID("linux.database.evidence-invalid", "database", "linux.error.databaseEvidenceInvalid", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    BACKUP_ARTIFACT_CREATION_FAILED("linux.backup.artifact-creation-failed", "backup", "linux.error.backupArtifactCreationFailed", FailureRecoveryAction.CLEANUP),
    BACKUP_ARTIFACT_EVIDENCE_INVALID("linux.backup.artifact-evidence-invalid", "backup", "linux.error.backupArtifactEvidenceInvalid", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    BACKUP_ARTIFACT_TRANSFER_FAILED("linux.backup.artifact-transfer-failed", "backup", "linux.error.backupArtifactTransferFailed", FailureRecoveryAction.CLEANUP),
    BACKUP_OPERATION_CLEANUP_FAILED("linux.backup.operation-cleanup-failed", "backup", "linux.error.backupOperationCleanupFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    CANDIDATE_PREPARATION_FAILED("linux.deployment.candidate-preparation-failed", "deployment", "linux.error.candidatePreparationFailed", FailureRecoveryAction.CLEANUP),
    SOURCE_UPLOAD_FAILED("linux.transfer.source-upload-failed", "transfer", "linux.error.sourceUploadFailed", FailureRecoveryAction.CLEANUP),
    UPLOAD_VERIFICATION_FAILED("linux.transfer.upload-verification-failed", "transfer", "linux.error.uploadVerificationFailed", FailureRecoveryAction.CLEANUP),
    RESTORE_SOURCE_INVALID("linux.restore.source-invalid", "restore", "linux.error.restoreSourceInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    RESTORE_UPLOAD_FAILED("linux.restore.upload-failed", "restore", "linux.error.restoreUploadFailed", FailureRecoveryAction.CLEANUP),
    RESTORE_UPLOAD_VERIFICATION_FAILED("linux.restore.upload-verification-failed", "restore", "linux.error.restoreUploadVerificationFailed", FailureRecoveryAction.CLEANUP),
    LOCAL_ARCHIVE_MISSING("linux.transfer.local-archive-missing", "transfer", "linux.error.localArchiveMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    LOCAL_ARCHIVE_READ_FAILED("linux.transfer.local-archive-read-failed", "transfer", "linux.error.localArchiveReadFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    LOCAL_ARCHIVE_SIZE_MISMATCH("linux.transfer.local-archive-size-mismatch", "transfer", "linux.error.localArchiveSizeMismatch", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    SNAPSHOT_IDENTITY_UNVERIFIED("linux.deployment.snapshot-identity-unverified", "deployment", "linux.error.snapshotIdentityUnverified", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    SNAPSHOT_TOKEN_MISSING("linux.deployment.snapshot-token-missing", "deployment", "linux.error.snapshotTokenMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    UNVERIFIED_BUILD_PUBLISH("linux.deployment.build-publish-unverified", "deployment", "linux.error.unverifiedBuildPublish", FailureRecoveryAction.ROLLBACK),
    UNVERIFIED_BUILD_ROLLBACK("linux.deployment.build-rollback-unverified", "rollback", "linux.error.unverifiedBuildRollback", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    ROOT_BUILD_REQUIRES_ROOT_SESSION("linux.build.root-session-required", "build", "linux.error.rootBuildRequiresRootSession", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ENVIRONMENT_PREPARATION_FAILED("linux.environment.preparation-failed", "environment", "linux.error.environmentPreparationFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    ENVIRONMENT_REQUIREMENTS_UNMET("linux.environment.requirements-unmet", "environment", "linux.error.environmentRequirementsUnmet", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ENVIRONMENT_UNSUPPORTED_DISTRO("linux.environment.unsupported-distro", "environment", "linux.error.environmentUnsupportedDistro", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    RUNTIME_OBSERVATION_FAILED("linux.runtime.observation-failed", "runtime", "linux.error.runtimeObservationFailed", FailureRecoveryAction.RECONNECT),
    LIFECYCLE_ACTION_FAILED("linux.lifecycle.action-failed", "lifecycle", "linux.error.lifecycleActionFailed", FailureRecoveryAction.ROLLBACK),
    START_REQUIRES_STOPPED("linux.lifecycle.start-requires-stopped", "lifecycle", "linux.error.startRequiresStopped", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    POST_START_HEALTH_FAILED("linux.lifecycle.post-start-health-failed", "health", "linux.error.postStartHealthFailed", FailureRecoveryAction.ROLLBACK),
    HEALTH_CHECK_UNSUPPORTED("linux.health.check-unsupported", "health", "linux.error.healthCheckUnsupported", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    STOP_UNVERIFIED("linux.lifecycle.stop-unverified", "lifecycle", "linux.error.stopUnverified", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    STOP_WAIT_INTERRUPTED("linux.lifecycle.stop-wait-interrupted", "lifecycle", "linux.error.stopWaitInterrupted", FailureRecoveryAction.NONE),
    MAIN_PROCESS_STILL_RUNNING("linux.lifecycle.main-process-still-running", "lifecycle", "linux.error.mainProcessStillRunning", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    ENABLE_AUTOSTART_UNVERIFIED("linux.lifecycle.enable-autostart-unverified", "lifecycle", "linux.error.enableAutostartUnverified", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    DISABLE_AUTOSTART_UNVERIFIED("linux.lifecycle.disable-autostart-unverified", "lifecycle", "linux.error.disableAutostartUnverified", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    LinuxOperationFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "linux"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

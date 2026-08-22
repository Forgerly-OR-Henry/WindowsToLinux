package gold.debug.windowstolinux.app.service.failure;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Stable failures raised at the desktop application-service boundary. / 桌面应用服务边界抛出的稳定失败。 */
public enum ApplicationServiceFailureType implements FailureDefinition {
    STORAGE_MODE_MISMATCH("service.validation.storage-mode-mismatch", "validation",
            "service.error.storageModeMismatch", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    DEPLOYMENT_ANALYSIS_REQUIRED("service.deployment.analysis-required", "deployment",
            "service.error.deploymentAnalysisRequired", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    APPLICATION_SECRET_REFERENCE_MISSING("service.secret.reference-missing", "secret",
            "service.error.applicationSecretReferenceMissing", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    LIFECYCLE_CONTEXT_MISMATCH("service.lifecycle.context-mismatch", "lifecycle",
            "service.error.lifecycleContextMismatch", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    MANAGED_SUMMARY_READ_FAILED("service.persistence.summary-read-failed", "persistence",
            "service.error.managedSummaryReadFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.RESTART_APPLICATION),
    APPLICATION_NOT_SELECTED("service.lifecycle.application-not-selected", "lifecycle",
            "service.error.applicationNotSelected", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    LEGACY_RUNTIME_MISSING("service.lifecycle.runtime-missing", "lifecycle",
            "service.error.runtimeMissing", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    SERVER_PROFILE_MISSING("service.lifecycle.server-profile-missing", "lifecycle",
            "service.error.serverProfileMissing", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    APPLICATION_NOT_MANAGED("service.lifecycle.application-not-managed", "lifecycle",
            "service.error.applicationNotManaged", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    APPLICATION_SERVER_CONFLICT("service.deployment.server-conflict", "deployment",
            "service.error.applicationServerConflict", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    APPLICATION_IDENTITY_INVALID("service.deployment.identity-invalid", "deployment",
            "service.error.applicationIdentityInvalid", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    LOCAL_OBSERVATION_SAVE_FAILED("service.persistence.observation-save-failed", "persistence",
            "service.error.localObservationSaveFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.RETRY),
    DEPLOYMENT_RECORD_SAVE_FAILED("service.persistence.deployment-record-save-failed", "persistence",
            "service.error.deploymentRecordSaveFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    BACKUP_INPUT_INCOMPLETE("service.backup.input-incomplete", "backup",
            "service.error.backupInputIncomplete", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    BACKUP_RECOVERY_FAILED("service.backup.recovery-failed", "backup",
            "service.error.backupRecoveryFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureSeverityLevel severity;
    private final FailureRecoveryAction recoveryAction;

    ApplicationServiceFailureType(String code, String phase, String messageKey,
                                  FailureSeverityLevel severity, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.severity = severity;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "service"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return severity; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

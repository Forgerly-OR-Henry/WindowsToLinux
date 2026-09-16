package gold.debug.windowstolinux.shared.linux.error;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Native database failures with stable helper status names. / 保留固定 helper 状态名称的原生数据库失败。 */
public enum NativeDatabaseFailureType implements FailureDefinition {
    AUTH_REQUIRED("auth-required", "nativeDatabaseAuthRequired", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    STATE_CHANGED("state-changed", "nativeDatabaseStateChanged", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    INITIALIZATION_FAILED("initialization-failed", "nativeDatabaseInitializationFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    MANUAL_RESTORE_REQUIRED("manual-restore-required", "nativeDatabaseManualRestoreRequired", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    VERSION_UNSUPPORTED("version-unsupported", "nativeDatabaseVersionUnsupported", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ACTION_FAILED("action-failed", "nativeDatabaseActionFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION);

    private final String reason;
    private final String message;
    private final FailureRecoveryAction recovery;

    NativeDatabaseFailureType(String reason, String message, FailureRecoveryAction recovery) {
        this.reason = reason;
        this.message = message;
        this.recovery = recovery;
    }

    @Override public String code() { return "linux.database." + reason; }
    @Override public String domain() { return "linux"; }
    @Override public String phase() { return "database"; }
    @Override public String messageKey() { return "linux.error." + message; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recovery; }
}

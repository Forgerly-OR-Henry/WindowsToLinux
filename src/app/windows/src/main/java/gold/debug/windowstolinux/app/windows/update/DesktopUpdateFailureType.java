package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by the Windows desktop update boundary. / Windows 桌面更新边界持有的失败类型。 */
public enum DesktopUpdateFailureType implements FailureDefinition {
    MANIFEST_INVALID("windows.update.manifest-invalid", "validation", "windows.error.updateManifestInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    SIGNATURE_INVALID("windows.update.signature-invalid", "verification", "windows.error.updateSignatureInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    VERSION_REJECTED("windows.update.version-rejected", "verification", "windows.error.updateVersionRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ARCHITECTURE_REJECTED("windows.update.architecture-rejected", "verification", "windows.error.updateArchitectureRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    PACKAGE_INVALID("windows.update.package-invalid", "verification", "windows.error.updatePackageInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    TRANSACTION_FAILED("windows.update.transaction-failed", "update", "windows.error.updateTransactionFailed", FailureRecoveryAction.ROLLBACK),
    ROLLBACK_FAILED("windows.update.rollback-failed", "rollback", "windows.error.updateRollbackFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    DesktopUpdateFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "windows"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

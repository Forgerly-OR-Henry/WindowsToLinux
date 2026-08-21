package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by the Windows desktop uninstall boundary. / Windows 桌面卸载边界持有的失败类型。 */
public enum DesktopUninstallFailureType implements FailureDefinition {
    DECISION_REQUIRED("windows.uninstall.decision-required", "validation", "windows.error.uninstallDecisionRequired", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    BOUNDARY_INVALID("windows.uninstall.boundary-invalid", "validation", "windows.error.uninstallBoundaryInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    TASKS_ACTIVE("windows.uninstall.tasks-active", "quiesce", "windows.error.uninstallTasksActive", FailureRecoveryAction.RETRY),
    REMOVAL_INCOMPLETE("windows.uninstall.removal-incomplete", "removal", "windows.error.uninstallRemovalIncomplete", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    DesktopUninstallFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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

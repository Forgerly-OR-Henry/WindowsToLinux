package gold.debug.windowstolinux.app.windows.workspace;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by the Windows desktop workspace boundary. / Windows 桌面工作区边界持有的失败类型。 */
public enum WindowsWorkspaceFailureType implements FailureDefinition {
    DIRECTORY_UNAVAILABLE("windows.workspace.directory-unavailable", "workspace", "windows.error.workspaceUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    DIRECTORY_NOT_WRITABLE("windows.workspace.not-writable", "workspace", "windows.error.workspaceNotWritable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    CAPACITY_INSUFFICIENT("windows.workspace.capacity-insufficient", "workspace", "windows.error.workspaceCapacityInsufficient", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    APPLICATION_ID_INVALID("windows.workspace.application-id-invalid", "validation", "windows.error.applicationIdInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ARCHIVE_FAILED("windows.workspace.archive-failed", "archive", "windows.error.archiveFailed", FailureRecoveryAction.CLEANUP);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    WindowsWorkspaceFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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

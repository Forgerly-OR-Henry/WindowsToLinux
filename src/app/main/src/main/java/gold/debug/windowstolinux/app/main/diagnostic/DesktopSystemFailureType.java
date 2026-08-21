package gold.debug.windowstolinux.app.main.diagnostic;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Desktop-process failures owned by app/main. / app/main 持有的桌面进程失败类型。 */
public enum DesktopSystemFailureType implements FailureDefinition {
    STARTUP_LAYOUT_INVALID("desktop.startup.layout-invalid", "startup", "desktop.error.startupLayoutInvalid", FailureSeverityLevel.FATAL, FailureRecoveryAction.EXIT_PROCESS),
    DATA_DIRECTORY_UNAVAILABLE("desktop.startup.data-directory-unavailable", "startup", "desktop.error.dataDirectoryUnavailable", FailureSeverityLevel.FATAL, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    DATABASE_INITIALIZATION_FAILED("desktop.startup.database-initialization-failed", "startup", "desktop.error.databaseInitializationFailed", FailureSeverityLevel.FATAL, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    UI_INITIALIZATION_FAILED("desktop.startup.ui-initialization-failed", "startup", "desktop.error.uiInitializationFailed", FailureSeverityLevel.FATAL, FailureRecoveryAction.RESTART_APPLICATION),
    UNKNOWN_RUNTIME_FAILURE("desktop.runtime.unknown-failure", "runtime", "desktop.error.unknownRuntimeFailure", FailureSeverityLevel.ERROR, FailureRecoveryAction.RESTART_APPLICATION),
    DIAGNOSTIC_REPORT_WRITE_FAILED("desktop.diagnostic.report-write-failed", "diagnostic", "desktop.error.diagnosticReportWriteFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    RESOURCE_EXHAUSTED("desktop.runtime.resource-exhausted", "runtime", "desktop.error.resourceExhausted", FailureSeverityLevel.FATAL, FailureRecoveryAction.EXIT_PROCESS),
    SHUTDOWN_FAILED("desktop.shutdown.failed", "shutdown", "desktop.error.shutdownFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.RESTART_APPLICATION);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureSeverityLevel severity;
    private final FailureRecoveryAction recoveryAction;

    DesktopSystemFailureType(String code, String phase, String messageKey,
                             FailureSeverityLevel severity, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.severity = severity;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "desktop"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return severity; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

package gold.debug.windowstolinux.shared.git;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by controlled Git snapshot preparation. / 受控 Git 快照准备持有的失败类型。 */
public enum GitSnapshotFailureType implements FailureDefinition {
    WORKSPACE_REQUIRED("git.workspace.required", "workspace", "git.error.workspaceRequired", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    WORKSPACE_UNAVAILABLE("git.workspace.unavailable", "workspace", "git.error.workspaceUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    TOOL_UNAVAILABLE("git.command.tool-unavailable", "command", "git.error.toolUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    REFERENCE_UNAVAILABLE("git.fetch.reference-unavailable", "fetch", "git.error.referenceUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    TRANSIENT_NETWORK_FAILURE("git.fetch.transient-network-failure", "fetch", "git.error.transientNetworkFailure", FailureRecoveryAction.RETRY),
    COMMAND_FAILED("git.command.execution-failed", "command", "git.error.commandFailed", FailureRecoveryAction.RETRY),
    TIMEOUT("git.command.timeout", "command", "git.error.timeout", FailureRecoveryAction.RETRY),
    PREPARATION_FAILED("git.snapshot.preparation-failed", "snapshot", "git.error.preparationFailed", FailureRecoveryAction.CLEANUP),
    CLEANUP_FAILED("git.snapshot.cleanup-failed", "cleanup", "git.error.cleanupFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    INTERRUPTED("git.snapshot.interrupted", "snapshot", "git.error.interrupted", FailureRecoveryAction.NONE);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    GitSnapshotFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "git"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

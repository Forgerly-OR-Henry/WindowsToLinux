package gold.debug.windowstolinux.shared.source.archive;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by source-boundary validation and deterministic archiving. / 源码边界校验与确定性归档持有的失败类型。 */
public enum SourceArchiveFailureType implements FailureDefinition {
    SOURCE_DIRECTORY_INVALID("source.validation.directory-invalid", "validation", "source.error.directoryInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    DESTINATION_INVALID("source.validation.destination-invalid", "validation", "source.error.destinationInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    BOUNDARY_ESCAPE("source.validation.boundary-escape", "validation", "source.error.boundaryEscape", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    SYMBOLIC_LINK_REJECTED("source.validation.symbolic-link-rejected", "validation", "source.error.symbolicLinkRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ENTRY_UNREADABLE("source.collection.entry-unreadable", "collection", "source.error.entryUnreadable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ENTRY_CHANGED("source.archive.entry-changed", "archive", "source.error.entryChanged", FailureRecoveryAction.RETRY),
    PATH_TOO_LONG("source.archive.path-too-long", "archive", "source.error.pathTooLong", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    WRITE_FAILED("source.archive.write-failed", "archive", "source.error.writeFailed", FailureRecoveryAction.CLEANUP),
    HASH_FAILED("source.archive.hash-failed", "verification", "source.error.hashFailed", FailureRecoveryAction.CLEANUP),
    CLEANUP_FAILED("source.archive.cleanup-failed", "cleanup", "source.error.cleanupFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    INTERRUPTED("source.archive.interrupted", "archive", "source.error.interrupted", FailureRecoveryAction.CLEANUP);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    SourceArchiveFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "source"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

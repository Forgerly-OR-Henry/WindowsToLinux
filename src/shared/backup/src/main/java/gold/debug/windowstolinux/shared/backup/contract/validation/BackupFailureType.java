package gold.debug.windowstolinux.shared.backup.contract.validation;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by portable backup, restore and migration contracts. / 可移植备份、恢复与迁移契约持有的失败类型。 */
public enum BackupFailureType implements FailureDefinition {
    MANIFEST_INVALID("backup.manifest.invalid", "validation", "backup.error.manifestInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ARCHIVE_INVALID("backup.archive.invalid", "validation", "backup.error.archiveInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    MEMBER_REJECTED("backup.archive.member-rejected", "validation", "backup.error.memberRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    LIMIT_EXCEEDED("backup.archive.limit-exceeded", "validation", "backup.error.limitExceeded", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    INTEGRITY_FAILED("backup.archive.integrity-failed", "verification", "backup.error.integrityFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    PROVENANCE_FAILED("backup.archive.provenance-failed", "verification", "backup.error.provenanceFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    WRITE_FAILED("backup.archive.write-failed", "archive", "backup.error.writeFailed", FailureRecoveryAction.CLEANUP),
    EXTRACTION_FAILED("backup.restore.extraction-failed", "restore", "backup.error.extractionFailed", FailureRecoveryAction.CLEANUP),
    ARCHIVE_CHANGED("backup.restore.archive-changed", "restore", "backup.error.archiveChanged", FailureRecoveryAction.RETRY),
    CLEANUP_FAILED("backup.restore.cleanup-failed", "cleanup", "backup.error.cleanupFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    BackupFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "backup"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

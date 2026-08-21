package gold.debug.windowstolinux.app.db.failure;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by desktop SQLite persistence. / 桌面 SQLite 持久化持有的失败类型。 */
public enum DesktopPersistenceFailureType implements FailureDefinition {
    DATA_DIRECTORY_UNAVAILABLE("persistence.directory.unavailable", "directory", "persistence.error.directoryUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    DATABASE_OPEN_FAILED("persistence.database.open-failed", "open", "persistence.error.databaseOpenFailed", FailureRecoveryAction.RESTART_APPLICATION),
    DATABASE_LOCKED("persistence.database.locked", "open", "persistence.error.databaseLocked", FailureRecoveryAction.RETRY),
    DATABASE_CORRUPTED("persistence.integrity.corrupted", "integrity", "persistence.error.databaseCorrupted", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    SCHEMA_NEWER("persistence.schema.newer-than-client", "migration", "persistence.error.schemaNewer", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    DISK_UNAVAILABLE("persistence.storage.disk-unavailable", "storage", "persistence.error.diskUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ROLLBACK_FAILED("persistence.transaction.rollback-failed", "transaction", "persistence.error.rollbackFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    DesktopPersistenceFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "persistence"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

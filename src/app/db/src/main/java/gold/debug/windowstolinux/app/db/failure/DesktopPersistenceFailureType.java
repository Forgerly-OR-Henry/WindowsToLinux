package gold.debug.windowstolinux.app.db.failure;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by desktop SQLite persistence. / 桌面 SQLite 持久化持有的失败类型。
 */
public enum DesktopPersistenceFailureType implements FailureDefinition {
    /**
     * RECOVERY JOURNAL FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的恢复日志失败分类。
     */
    RECOVERY_JOURNAL_FAILED("persistence.recovery.journal-failed", "recovery", "persistence.error.recoveryJournalFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * RECOVERY PREFERENCE FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的恢复偏好失败分类。
     */
    RECOVERY_PREFERENCE_FAILED("persistence.recovery.preference-failed", "recovery", "persistence.error.recoveryPreferenceFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATA DIRECTORY UNAVAILABLE classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据目录不可用分类。
     */
    DATA_DIRECTORY_UNAVAILABLE("persistence.directory.unavailable", "directory", "persistence.error.directoryUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE OPEN FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据库打开失败分类。
     */
    DATABASE_OPEN_FAILED("persistence.database.open-failed", "open", "persistence.error.databaseOpenFailed", FailureRecoveryAction.RESTART_APPLICATION),
    /**
     * DATABASE LOCKED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据库已锁定分类。
     */
    DATABASE_LOCKED("persistence.database.locked", "open", "persistence.error.databaseLocked", FailureRecoveryAction.RETRY),
    /**
     * DATABASE CORRUPTED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据库已损坏分类。
     */
    DATABASE_CORRUPTED("persistence.integrity.corrupted", "integrity", "persistence.error.databaseCorrupted", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * SCHEMA NEWER classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的结构更新版本分类。
     */
    SCHEMA_NEWER("persistence.schema.newer-than-client", "migration", "persistence.error.schemaNewer", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * DISK UNAVAILABLE classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的磁盘不可用分类。
     */
    DISK_UNAVAILABLE("persistence.storage.disk-unavailable", "storage", "persistence.error.diskUnavailable", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ROLLBACK FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的回滚失败分类。
     */
    ROLLBACK_FAILED("persistence.transaction.rollback-failed", "transaction", "persistence.error.rollbackFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

    /**
     * Stable machine-readable classification code.
     * <p>稳定的机器可读分类码。
     */
    private final String code;
    /**
     * Stage associated with the result or failure.
     * <p>结果或失败所属阶段。
     */
    private final String phase;
    /**
     * Stable localization key for user-facing text.
     * <p>用户可见文本的稳定本地化键。
     */
    private final String messageKey;
    /**
     * Action required to recover from the classified failure.
     * <p>从已分类失败中恢复所需的动作。
     */
    private final FailureRecoveryAction recoveryAction;

    /**
     * Binds the supplied dependencies and state for desktop persistence failure type.
     * <p>为Desktop持久化失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    DesktopPersistenceFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    /**
     * Returns stable machine-readable classification code.
     * <p>返回稳定的机器可读分类码。
     *
     * @return stable machine-readable classification code / 稳定的机器可读分类码
     */
    @Override public String code() { return code; }
    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override public String domain() { return "persistence"; }
    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    @Override public String phase() { return phase; }
    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    @Override public String messageKey() { return messageKey; }
    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}

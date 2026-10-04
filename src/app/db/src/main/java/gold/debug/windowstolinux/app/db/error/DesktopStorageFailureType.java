package gold.debug.windowstolinux.app.db.error;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/**
 * Failures owned by desktop H2 persistence. / 桌面 H2 持久化持有的失败类型。
 */
public enum DesktopStorageFailureType {
    /**
     * RECOVERY JOURNAL FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的恢复日志失败分类。
     */
    RECOVERY_JOURNAL_FAILED("persistence.recovery.journal-failed", "recovery",
        "persistence.error.recoveryJournalFailed", "REQUIRE_MANUAL_RECOVERY"),
    /**
     * RECOVERY PREFERENCE FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的恢复偏好失败分类。
     */
    RECOVERY_PREFERENCE_FAILED("persistence.recovery.preference-failed", "recovery",
        "persistence.error.recoveryPreferenceFailed", "REQUEST_USER_CORRECTION"),
    /**
     * DATA DIRECTORY UNAVAILABLE classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据目录不可用分类。
     */
    DATA_DIRECTORY_UNAVAILABLE("persistence.directory.unavailable", "directory",
        "persistence.error.directoryUnavailable", "REQUEST_USER_CORRECTION"),
    /**
     * DATABASE OPEN FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据库打开失败分类。
     */
    DATABASE_OPEN_FAILED("persistence.database.open-failed", "open", "persistence.error.databaseOpenFailed",
        "RESTART_APPLICATION"),
    /**
     * DATABASE LOCKED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据库已锁定分类。
     */
    DATABASE_LOCKED("persistence.database.locked", "open", "persistence.error.databaseLocked",
        "RETRY"),
    /**
     * DATABASE CORRUPTED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的数据库已损坏分类。
     */
    DATABASE_CORRUPTED("persistence.integrity.corrupted", "integrity", "persistence.error.databaseCorrupted",
        "REQUIRE_MANUAL_RECOVERY"),
    /**
     * SCHEMA INCOMPATIBLE classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的结构不兼容分类。
     */
    SCHEMA_INCOMPATIBLE("persistence.schema.incompatible", "schema", "persistence.error.schemaIncompatible",
        "REQUIRE_MANUAL_RECOVERY"),
    /**
     * DISK UNAVAILABLE classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的磁盘不可用分类。
     */
    DISK_UNAVAILABLE("persistence.storage.disk-unavailable", "storage", "persistence.error.diskUnavailable",
        "REQUEST_USER_CORRECTION"),
    /**
     * ROLLBACK FAILED classification within desktop persistence failure type.
     * <p>Desktop持久化失败类型中的回滚失败分类。
     */
    ROLLBACK_FAILED("persistence.transaction.rollback-failed", "transaction", "persistence.error.rollbackFailed",
        "REQUIRE_MANUAL_RECOVERY");

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
    private final String recoveryAction;

    /**
     * Binds the supplied dependencies and state for desktop persistence failure type.
     * <p>为Desktop持久化失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    DesktopStorageFailureType(String code, String phase, String messageKey, String recoveryAction) {
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
    public String code() {
        return code;
    }

    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    public String domain() {
        return "persistence";
    }

    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    public String phase() {
        return phase;
    }

    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    public String severity() {
        return "ERROR";
    }

    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    public String recoveryAction() {
        return recoveryAction;
    }

    /** Creates immutable evidence for the original persistence failure. / 创建原持久化失败的不可变依据。
     * @param occurrence original diagnostic UUID / 原诊断UUID
     * @param diagnostic nonsecret diagnostic text / 非秘密诊断文本
     * @return exact event and recovery context / 精确事件及恢复上下文
     * @throws NullPointerException if a required value is absent / 必需值缺失时
     * @throws IllegalArgumentException if the diagnostic is blank / 诊断为空时 */
    public Problem problem(UUID occurrence, String diagnostic) {
        Objects.requireNonNull(occurrence, "occurrence");
        String message = Objects.requireNonNull(diagnostic, "diagnostic").trim();
        if (message.isBlank()) throw new IllegalArgumentException("diagnostic must not be blank");
        return new Problem(problemCode(), code, message, Map.of("domain", domain(), "phase", phase,
            "messageKey", messageKey, "severity", severity(), "recoveryAction", recoveryAction,
            "recoveryDisposition", "NOT_ATTEMPTED"), Optional.of(occurrence));
    }

    /** Maps an actual persistence failure without changing its event. / 映射实际持久化失败，不更改其事件。
     * @return stable SDK failure category / 稳定SDK失败分类 */
    private ProblemCode problemCode() {
        return switch (this) {
            case DATA_DIRECTORY_UNAVAILABLE, DISK_UNAVAILABLE -> ProblemCode.UNAVAILABLE;
            case DATABASE_LOCKED -> ProblemCode.BUSY;
            case SCHEMA_INCOMPATIBLE -> ProblemCode.INCOMPATIBLE;
            default -> ProblemCode.IO_FAILURE;
        };
    }
}

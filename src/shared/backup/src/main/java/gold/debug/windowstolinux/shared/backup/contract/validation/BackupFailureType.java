package gold.debug.windowstolinux.shared.backup.contract.validation;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by portable backup, restore and migration contracts. / 可移植备份、恢复与迁移契约持有的失败类型。
 */
public enum BackupFailureType implements FailureDefinition {
    /**
     * COLLECTION PREFLIGHT FAILED classification within backup failure type.
     * <p>备份失败类型中的采集预检失败分类。
     */
    COLLECTION_PREFLIGHT_FAILED("backup.collection.preflight-failed", "preflight",
            "backup.error.collectionPreflightFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * COLLECTION FAILED classification within backup failure type.
     * <p>备份失败类型中的采集失败分类。
     */
    COLLECTION_FAILED("backup.collection.failed", "collection", "backup.error.collectionFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * COLLECTION RECOVERY FAILED classification within backup failure type.
     * <p>备份失败类型中的采集恢复失败分类。
     */
    COLLECTION_RECOVERY_FAILED("backup.collection.recovery-failed", "recovery", "backup.error.collectionRecoveryFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * MANIFEST INVALID classification within backup failure type.
     * <p>备份失败类型中的清单无效分类。
     */
    MANIFEST_INVALID("backup.manifest.invalid", "validation", "backup.error.manifestInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ARCHIVE INVALID classification within backup failure type.
     * <p>备份失败类型中的归档无效分类。
     */
    ARCHIVE_INVALID("backup.archive.invalid", "validation", "backup.error.archiveInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * MEMBER REJECTED classification within backup failure type.
     * <p>备份失败类型中的成员已拒绝分类。
     */
    MEMBER_REJECTED("backup.archive.member-rejected", "validation", "backup.error.memberRejected",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * LIMIT EXCEEDED classification within backup failure type.
     * <p>备份失败类型中的限制已超限分类。
     */
    LIMIT_EXCEEDED("backup.archive.limit-exceeded", "validation", "backup.error.limitExceeded",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * INTEGRITY FAILED classification within backup failure type.
     * <p>备份失败类型中的完整性失败分类。
     */
    INTEGRITY_FAILED("backup.archive.integrity-failed", "verification", "backup.error.integrityFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * PROVENANCE FAILED classification within backup failure type.
     * <p>备份失败类型中的来源证据失败分类。
     */
    PROVENANCE_FAILED("backup.archive.provenance-failed", "verification", "backup.error.provenanceFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * WRITE FAILED classification within backup failure type.
     * <p>备份失败类型中的写入失败分类。
     */
    WRITE_FAILED("backup.archive.write-failed", "archive", "backup.error.writeFailed", FailureRecoveryAction.CLEANUP),
    /**
     * EXTRACTION FAILED classification within backup failure type.
     * <p>备份失败类型中的提取失败分类。
     */
    EXTRACTION_FAILED("backup.restore.extraction-failed", "restore", "backup.error.extractionFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * ARCHIVE CHANGED classification within backup failure type.
     * <p>备份失败类型中的归档已变化分类。
     */
    ARCHIVE_CHANGED("backup.restore.archive-changed", "restore", "backup.error.archiveChanged",
            FailureRecoveryAction.RETRY),
    /**
     * CLEANUP FAILED classification within backup failure type.
     * <p>备份失败类型中的清理失败分类。
     */
    CLEANUP_FAILED("backup.restore.cleanup-failed", "cleanup", "backup.error.cleanupFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * RESTORE PREFLIGHT FAILED classification within backup failure type.
     * <p>备份失败类型中的恢复预检失败分类。
     */
    RESTORE_PREFLIGHT_FAILED("backup.restore.preflight-failed", "preflight", "backup.error.restorePreflightFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * RESTORE FILE FAILED classification within backup failure type.
     * <p>备份失败类型中的恢复文件失败分类。
     */
    RESTORE_FILE_FAILED("backup.restore.file-failed", "restore", "backup.error.restoreFileFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * RESTORE HEALTH FAILED classification within backup failure type.
     * <p>备份失败类型中的恢复健康失败分类。
     */
    RESTORE_HEALTH_FAILED("backup.restore.health-failed", "health", "backup.error.restoreHealthFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * RESTORE COMMIT FAILED classification within backup failure type.
     * <p>备份失败类型中的恢复提交失败分类。
     */
    RESTORE_COMMIT_FAILED("backup.restore.commit-failed", "commit", "backup.error.restoreCommitFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * RESTORE RECOVERY FAILED classification within backup failure type.
     * <p>备份失败类型中的恢复恢复失败分类。
     */
    RESTORE_RECOVERY_FAILED("backup.restore.recovery-failed", "rollback", "backup.error.restoreRecoveryFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * DATABASE PREFLIGHT FAILED classification within backup failure type.
     * <p>备份失败类型中的数据库预检失败分类。
     */
    DATABASE_PREFLIGHT_FAILED("backup.database.preflight-failed", "preflight", "backup.error.databasePreflightFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE BACKUP FAILED classification within backup failure type.
     * <p>备份失败类型中的数据库备份失败分类。
     */
    DATABASE_BACKUP_FAILED("backup.database.export-failed", "database", "backup.error.databaseBackupFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * DATABASE RESTORE FAILED classification within backup failure type.
     * <p>备份失败类型中的数据库恢复失败分类。
     */
    DATABASE_RESTORE_FAILED("backup.database.restore-failed", "database", "backup.error.databaseRestoreFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * DATABASE EVIDENCE INVALID classification within backup failure type.
     * <p>备份失败类型中的数据库证据无效分类。
     */
    DATABASE_EVIDENCE_INVALID("backup.database.evidence-invalid", "verification",
            "backup.error.databaseEvidenceInvalid", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * MIGRATION PREFLIGHT FAILED classification within backup failure type.
     * <p>备份失败类型中的迁移预检失败分类。
     */
    MIGRATION_PREFLIGHT_FAILED("backup.migration.preflight-failed", "preflight",
            "backup.error.migrationPreflightFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * MIGRATION SYNC FAILED classification within backup failure type.
     * <p>备份失败类型中的迁移同步失败分类。
     */
    MIGRATION_SYNC_FAILED("backup.migration.sync-failed", "synchronization", "backup.error.migrationSyncFailed",
            FailureRecoveryAction.CLEANUP),
    /**
     * MIGRATION QUIESCE FAILED classification within backup failure type.
     * <p>备份失败类型中的迁移停写失败分类。
     */
    MIGRATION_QUIESCE_FAILED("backup.migration.quiesce-failed", "quiesce", "backup.error.migrationQuiesceFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * MIGRATION TARGET FAILED classification within backup failure type.
     * <p>备份失败类型中的迁移目标失败分类。
     */
    MIGRATION_TARGET_FAILED("backup.migration.target-failed", "target", "backup.error.migrationTargetFailed",
            FailureRecoveryAction.ROLLBACK),
    /**
     * MIGRATION RECOVERY FAILED classification within backup failure type.
     * <p>备份失败类型中的迁移恢复失败分类。
     */
    MIGRATION_RECOVERY_FAILED("backup.migration.recovery-failed", "rollback", "backup.error.migrationRecoveryFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY);

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
     * Binds the supplied dependencies and state for backup failure type.
     * <p>为备份失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    BackupFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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
    @Override
    public String code() {
        return code;
    }

    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override
    public String domain() {
        return "backup";
    }

    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    @Override
    public String phase() {
        return phase;
    }

    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    @Override
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    @Override
    public FailureSeverityLevel severity() {
        return FailureSeverityLevel.ERROR;
    }

    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override
    public FailureRecoveryAction recoveryAction() {
        return recoveryAction;
    }
}

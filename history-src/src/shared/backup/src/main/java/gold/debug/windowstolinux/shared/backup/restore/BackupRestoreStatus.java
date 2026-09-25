package gold.debug.windowstolinux.shared.backup.restore;

/**
 * Exact terminal result of one candidate restore attempt. / 单次候选恢复尝试的精确终态。
 */
public enum BackupRestoreStatus {
    /**
     * SUCCEEDED classification within backup restore status.
     * <p>备份恢复状态中的已成功分类。
     */
    SUCCEEDED,
    /**
     * FAILED EXISTING PRESERVED classification within backup restore status.
     * <p>备份恢复状态中的失败既有已保留分类。
     */
    FAILED_EXISTING_PRESERVED,
    /**
     * MANUAL RECOVERY REQUIRED classification within backup restore status.
     * <p>备份恢复状态中的人工恢复必需分类。
     */
    MANUAL_RECOVERY_REQUIRED
}

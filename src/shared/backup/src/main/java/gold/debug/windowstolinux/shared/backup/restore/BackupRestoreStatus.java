package gold.debug.windowstolinux.shared.backup.restore;

/** Exact terminal result of one candidate restore attempt. / 单次候选恢复尝试的精确终态。 */
public enum BackupRestoreStatus {
    SUCCEEDED,
    FAILED_EXISTING_PRESERVED,
    MANUAL_RECOVERY_REQUIRED
}

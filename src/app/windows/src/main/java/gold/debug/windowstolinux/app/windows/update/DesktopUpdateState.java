package gold.debug.windowstolinux.app.windows.update;

/** Ordered evidence states of one desktop update transaction. / 单次桌面更新事务的有序证据状态。 */
public enum DesktopUpdateState {
    TASKS_QUIESCED,
    BACKUP_CREATED,
    INDEPENDENT_UPDATER_VERIFIED,
    PROGRAM_REPLACED,
    DATABASE_MIGRATED,
    NEW_VERSION_HEALTHY,
    ROLLBACK_VERIFIED
}

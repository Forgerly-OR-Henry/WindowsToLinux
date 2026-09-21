package gold.debug.windowstolinux.app.windows.update;

/**
 * Ordered evidence states of one desktop update transaction. / 单次桌面更新事务的有序证据状态。
 */
public enum DesktopUpdateState {
    /**
     * TASKS QUIESCED classification within desktop update state.
     * <p>Desktop更新状态中的任务集合已停写分类。
     */
    TASKS_QUIESCED,
    /**
     * BACKUP CREATED classification within desktop update state.
     * <p>Desktop更新状态中的备份已创建分类。
     */
    BACKUP_CREATED,
    /**
     * INDEPENDENT UPDATER VERIFIED classification within desktop update state.
     * <p>Desktop更新状态中的独立更新程序已验证分类。
     */
    INDEPENDENT_UPDATER_VERIFIED,
    /**
     * PROGRAM REPLACED classification within desktop update state.
     * <p>Desktop更新状态中的程序已替换分类。
     */
    PROGRAM_REPLACED,
    /**
     * DATABASE MIGRATED classification within desktop update state.
     * <p>Desktop更新状态中的数据库已迁移分类。
     */
    DATABASE_MIGRATED,
    /**
     * NEW VERSION HEALTHY classification within desktop update state.
     * <p>Desktop更新状态中的新版本健康分类。
     */
    NEW_VERSION_HEALTHY,
    /**
     * ROLLBACK VERIFIED classification within desktop update state.
     * <p>Desktop更新状态中的回滚已验证分类。
     */
    ROLLBACK_VERIFIED
}

package gold.debug.windowstolinux.shared.backup.execution.migration;

/**
 * Ordered evidence states of one offline migration preparation. / 单次离线迁移准备的有序证据状态。
 */
public enum OfflineMigrationState {
    /**
     * TARGET PREFLIGHT VERIFIED classification within offline migration state.
     * <p>离线迁移状态中的目标预检已验证分类。
     */
    TARGET_PREFLIGHT_VERIFIED,
    /**
     * INITIAL SYNC VERIFIED classification within offline migration state.
     * <p>离线迁移状态中的初始同步已验证分类。
     */
    INITIAL_SYNC_VERIFIED,
    /**
     * SOURCE WRITES STOPPED classification within offline migration state.
     * <p>离线迁移状态中的源码写入集合已停止分类。
     */
    SOURCE_WRITES_STOPPED,
    /**
     * FINAL SYNC VERIFIED classification within offline migration state.
     * <p>离线迁移状态中的最终同步已验证分类。
     */
    FINAL_SYNC_VERIFIED,
    /**
     * TARGET CANDIDATE VERIFIED classification within offline migration state.
     * <p>离线迁移状态中的目标候选已验证分类。
     */
    TARGET_CANDIDATE_VERIFIED,
    /**
     * TARGET CANDIDATE RECOVERY VERIFIED classification within offline migration state.
     * <p>离线迁移状态中的目标候选恢复已验证分类。
     */
    TARGET_CANDIDATE_RECOVERY_VERIFIED,
    /**
     * SOURCE RECOVERY VERIFIED classification within offline migration state.
     * <p>离线迁移状态中的源码恢复已验证分类。
     */
    SOURCE_RECOVERY_VERIFIED,
    /**
     * MANUAL TRAFFIC SWITCH REQUIRED classification within offline migration state.
     * <p>离线迁移状态中的人工流量切换必需分类。
     */
    MANUAL_TRAFFIC_SWITCH_REQUIRED
}

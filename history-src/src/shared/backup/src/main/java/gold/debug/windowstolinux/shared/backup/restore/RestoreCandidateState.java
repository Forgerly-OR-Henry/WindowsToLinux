package gold.debug.windowstolinux.shared.backup.restore;

/**
 * Ordered candidate restore states reported independently. / 独立报告的有序候选恢复状态。
 */
public enum RestoreCandidateState {
    /**
     * PREFLIGHT VERIFIED classification within restore candidate state.
     * <p>恢复候选状态中的预检已验证分类。
     */
    PREFLIGHT_VERIFIED,
    /**
     * FILES STAGED classification within restore candidate state.
     * <p>恢复候选状态中的文件集合已暂存分类。
     */
    FILES_STAGED,
    /**
     * DATABASE RESTORED classification within restore candidate state.
     * <p>恢复候选状态中的数据库已恢复分类。
     */
    DATABASE_RESTORED,
    /**
     * DATABASE COMMITTED classification within restore candidate state.
     * <p>恢复候选状态中的数据库已提交分类。
     */
    DATABASE_COMMITTED,
    /**
     * COMPONENTS HEALTHY classification within restore candidate state.
     * <p>恢复候选状态中的组件集合健康分类。
     */
    COMPONENTS_HEALTHY,
    /**
     * APPLICATION HEALTHY classification within restore candidate state.
     * <p>恢复候选状态中的应用健康分类。
     */
    APPLICATION_HEALTHY,
    /**
     * COMMITTED classification within restore candidate state.
     * <p>恢复候选状态中的已提交分类。
     */
    COMMITTED,
    /**
     * RECOVERY VERIFIED classification within restore candidate state.
     * <p>恢复候选状态中的恢复已验证分类。
     */
    RECOVERY_VERIFIED
}

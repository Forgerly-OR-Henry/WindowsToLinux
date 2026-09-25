package gold.debug.windowstolinux.shared.backup.execution.migration;

/**
 * Exact terminal status before any external traffic switch. / 任何外部流量切换前的精确终态。
 */
public enum OfflineMigrationStatus {
    /**
     * READY FOR MANUAL TRAFFIC SWITCH classification within offline migration status.
     * <p>离线迁移状态中的就绪对应人工流量切换分类。
     */
    READY_FOR_MANUAL_TRAFFIC_SWITCH,
    /**
     * PRECONDITION REJECTED classification within offline migration status.
     * <p>离线迁移状态中的前提条件已拒绝分类。
     */
    PRECONDITION_REJECTED,
    /**
     * FAILED TARGET CLEANED classification within offline migration status.
     * <p>离线迁移状态中的失败目标已清理分类。
     */
    FAILED_TARGET_CLEANED,
    /**
     * FAILED SOURCE RECOVERED classification within offline migration status.
     * <p>离线迁移状态中的失败源码已恢复分类。
     */
    FAILED_SOURCE_RECOVERED,
    /**
     * MANUAL RECOVERY REQUIRED classification within offline migration status.
     * <p>离线迁移状态中的人工恢复必需分类。
     */
    MANUAL_RECOVERY_REQUIRED
}

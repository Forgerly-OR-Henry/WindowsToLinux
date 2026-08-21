package gold.debug.windowstolinux.shared.backup.execution.migration;

/** Exact terminal status before any external traffic switch. / 任何外部流量切换前的精确终态。 */
public enum OfflineMigrationStatus {
    READY_FOR_MANUAL_TRAFFIC_SWITCH,
    PRECONDITION_REJECTED,
    FAILED_TARGET_CLEANED,
    FAILED_SOURCE_RECOVERED,
    MANUAL_RECOVERY_REQUIRED
}

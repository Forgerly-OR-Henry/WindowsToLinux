package gold.debug.windowstolinux.shared.backup.execution.migration;

/** Ordered evidence states of one offline migration preparation. / 单次离线迁移准备的有序证据状态。 */
public enum OfflineMigrationState {
    TARGET_PREFLIGHT_VERIFIED,
    INITIAL_SYNC_VERIFIED,
    SOURCE_WRITES_STOPPED,
    FINAL_SYNC_VERIFIED,
    TARGET_CANDIDATE_VERIFIED,
    TARGET_CANDIDATE_RECOVERY_VERIFIED,
    SOURCE_RECOVERY_VERIFIED,
    MANUAL_TRAFFIC_SWITCH_REQUIRED
}

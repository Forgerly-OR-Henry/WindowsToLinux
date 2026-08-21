package gold.debug.windowstolinux.shared.backup.restore;

/** Ordered candidate restore states reported independently. / 独立报告的有序候选恢复状态。 */
public enum RestoreCandidateState {
    PREFLIGHT_VERIFIED,
    FILES_STAGED,
    DATABASE_RESTORED,
    COMPONENTS_HEALTHY,
    APPLICATION_HEALTHY,
    COMMITTED,
    RECOVERY_VERIFIED
}

package gold.debug.windowstolinux.app.windows.update;

/** Exact terminal desktop update status. / 精确桌面更新终态。 */
public enum DesktopUpdateStatus {
    SUCCEEDED,
    PRECONDITION_REJECTED,
    FAILED_ROLLED_BACK,
    MANUAL_RECOVERY_REQUIRED
}

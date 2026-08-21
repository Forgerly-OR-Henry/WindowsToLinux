package gold.debug.windowstolinux.app.windows.uninstall;

/** Exact desktop uninstall terminal status. / 精确桌面卸载终态。 */
public enum DesktopUninstallStatus {
    DECISION_REQUIRED,
    PRECONDITION_REJECTED,
    SUCCEEDED_DATA_RETAINED,
    SUCCEEDED_DATA_DELETED,
    COMPLETED_WITH_RESIDUALS
}

package gold.debug.windowstolinux.app.windows.uninstall;

/**
 * Exact desktop uninstall terminal status. / 精确桌面卸载终态。
 */
public enum DesktopUninstallStatus {
    /**
     * DECISION REQUIRED classification within desktop uninstall status.
     * <p>Desktop卸载状态中的决定必需分类。
     */
    DECISION_REQUIRED,
    /**
     * PRECONDITION REJECTED classification within desktop uninstall status.
     * <p>Desktop卸载状态中的前提条件已拒绝分类。
     */
    PRECONDITION_REJECTED,
    /**
     * SUCCEEDED DATA RETAINED classification within desktop uninstall status.
     * <p>Desktop卸载状态中的已成功数据已保留分类。
     */
    SUCCEEDED_DATA_RETAINED,
    /**
     * SUCCEEDED DATA DELETED classification within desktop uninstall status.
     * <p>Desktop卸载状态中的已成功数据已删除分类。
     */
    SUCCEEDED_DATA_DELETED,
    /**
     * COMPLETED WITH RESIDUALS classification within desktop uninstall status.
     * <p>Desktop卸载状态中的已完成具有残留集合分类。
     */
    COMPLETED_WITH_RESIDUALS
}

package gold.debug.windowstolinux.app.windows.uninstall;

/**
 * Ordered desktop uninstall evidence states. / 有序桌面卸载证据状态。
 */
public enum DesktopUninstallState {
    /**
     * DECISION VALIDATED classification within desktop uninstall state.
     * <p>Desktop卸载状态中的决定已验证分类。
     */
    DECISION_VALIDATED,
    /**
     * TASKS STOPPED classification within desktop uninstall state.
     * <p>Desktop卸载状态中的任务集合已停止分类。
     */
    TASKS_STOPPED,
    /**
     * INDEPENDENT WORKER VERIFIED classification within desktop uninstall state.
     * <p>Desktop卸载状态中的独立工作线程已验证分类。
     */
    INDEPENDENT_WORKER_VERIFIED,
    /**
     * BOUNDARIES VERIFIED classification within desktop uninstall state.
     * <p>Desktop卸载状态中的边界已验证分类。
     */
    BOUNDARIES_VERIFIED,
    /**
     * PROGRAM REMOVED classification within desktop uninstall state.
     * <p>Desktop卸载状态中的程序已移除分类。
     */
    PROGRAM_REMOVED,
    /**
     * DATA REMOVED classification within desktop uninstall state.
     * <p>Desktop卸载状态中的数据已移除分类。
     */
    DATA_REMOVED,
    /**
     * CREDENTIALS REMOVED classification within desktop uninstall state.
     * <p>Desktop卸载状态中的凭据已移除分类。
     */
    CREDENTIALS_REMOVED
}

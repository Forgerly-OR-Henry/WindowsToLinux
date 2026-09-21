package gold.debug.windowstolinux.app.windows.uninstall;

/**
 * Main-process uninstall status before an external worker is launched. / 启动外部执行器前的主进程卸载状态。
 */
public enum DesktopUninstallPreparationStatus {
    /**
     * READY FOR HANDOFF classification within desktop uninstall preparation status.
     * <p>Desktop卸载准备状态中的就绪对应交接分类。
     */
    READY_FOR_HANDOFF,
    /**
     * DECISION REQUIRED classification within desktop uninstall preparation status.
     * <p>Desktop卸载准备状态中的决定必需分类。
     */
    DECISION_REQUIRED,
    /**
     * PRECONDITION REJECTED classification within desktop uninstall preparation status.
     * <p>Desktop卸载准备状态中的前提条件已拒绝分类。
     */
    PRECONDITION_REJECTED
}

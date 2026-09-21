package gold.debug.windowstolinux.app.windows.update;

/**
 * Main-process terminal status before an independent updater is launched. / 启动独立更新器前的主进程终态。
 */
public enum DesktopUpdatePreparationStatus {
    /**
     * READY FOR HANDOFF classification within desktop update preparation status.
     * <p>Desktop更新准备状态中的就绪对应交接分类。
     */
    READY_FOR_HANDOFF,
    /**
     * PRECONDITION REJECTED classification within desktop update preparation status.
     * <p>Desktop更新准备状态中的前提条件已拒绝分类。
     */
    PRECONDITION_REJECTED
}

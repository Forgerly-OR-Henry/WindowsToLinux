package gold.debug.windowstolinux.app.windows.uninstall;

/** Main-process uninstall status before an external worker is launched. / 启动外部执行器前的主进程卸载状态。 */
public enum DesktopUninstallPreparationStatus {
    READY_FOR_HANDOFF,
    DECISION_REQUIRED,
    PRECONDITION_REJECTED
}

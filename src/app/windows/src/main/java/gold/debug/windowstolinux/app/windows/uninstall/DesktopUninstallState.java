package gold.debug.windowstolinux.app.windows.uninstall;

/** Ordered desktop uninstall evidence states. / 有序桌面卸载证据状态。 */
public enum DesktopUninstallState {
    DECISION_VALIDATED,
    TASKS_STOPPED,
    INDEPENDENT_WORKER_VERIFIED,
    BOUNDARIES_VERIFIED,
    PROGRAM_REMOVED,
    DATA_REMOVED,
    CREDENTIALS_REMOVED
}

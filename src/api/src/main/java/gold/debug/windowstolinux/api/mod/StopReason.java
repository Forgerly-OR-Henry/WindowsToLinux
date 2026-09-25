package gold.debug.windowstolinux.api.mod;

/**
 * Explicit lifecycle stop cause without implying resource release. / 不代表资源已经释放的明确生命周期停止原因。
 */
public enum StopReason {
    /**
     * The host is draining resources before exit. / 宿主正在退出前释放资源。
     */
    APPLICATION_EXIT,
    /**
     * The host is draining resources for a requested restart. / 宿主正在为请求的重启释放资源。
     */
    APPLICATION_RESTART,
    /**
     * Partial startup failed and requires cleanup. / 部分启动失败，需要执行清理。
     */
    START_FAILURE,
    /**
     * The isolated recovery session has completed. / 隔离恢复会话已经完成。
     */
    RECOVERY_FINISHED
}

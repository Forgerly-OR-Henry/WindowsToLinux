package gold.debug.windowstolinux.shared.model.failure;

/**
 * Closed action selected for a structured failure. / 为结构化失败选择的封闭恢复动作。
 */
public enum FailureRecoveryAction {
    /**
     * No recovery action is necessary or safe. / 无需或无法安全执行恢复动作。
     */
    NONE,
    /**
     * Retry the same idempotent operation within a fixed bound. / 在固定上限内重试同一幂等操作。
     */
    RETRY,
    /**
     * Reconnect and reverify the authoritative remote state. / 重连并重新验证权威远端状态。
     */
    RECONNECT,
    /**
     * Remove only operation-owned temporary resources. / 仅清理本次操作拥有的临时资源。
     */
    CLEANUP,
    /**
     * Restore the last verified state and then verify it. / 恢复并验证上一个已确认状态。
     */
    ROLLBACK,
    /**
     * Ask the user to correct bounded input or configuration. / 请求用户修正有界输入或配置。
     */
    REQUEST_USER_CORRECTION,
    /**
     * Restart the desktop application without changing managed targets. / 重启桌面应用且不修改受管目标。
     */
    RESTART_APPLICATION,
    /**
     * Stop automation and require an explicit recovery procedure. / 停止自动化并要求显式恢复流程。
     */
    REQUIRE_MANUAL_RECOVERY,
    /**
     * End a process that cannot safely continue. / 结束无法安全继续的进程。
     */
    EXIT_PROCESS
}

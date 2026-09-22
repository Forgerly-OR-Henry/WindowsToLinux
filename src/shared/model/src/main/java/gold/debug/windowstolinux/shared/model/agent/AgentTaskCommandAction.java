package gold.debug.windowstolinux.shared.model.agent;

/** Explicit process-local task controls. / 显式进程内任务控制。 */
public enum AgentTaskCommandAction {
    /** Wait at the next safe transaction boundary. / 在下一个安全事务边界等待。 */
    PAUSE,
    /** Continue the same frozen snapshot. / 使用同一冻结快照继续。 */
    RESUME,
    /** Explicitly adopt newly validated model settings while paused; invalidate pending reviews. / 暂停时显式采用新验证模型设置，使待执行审批失效。 */
    REFRESH_MODELS,
    /** Finish current safe cleanup and stop. / 完成当前安全清理后停止。 */
    CANCEL
}

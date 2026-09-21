package gold.debug.windowstolinux.shared.model.agent;

/** Persisted task lifecycle without automatic write recovery. / 不自动恢复写操作的持久化任务状态。 */
public enum AgentTaskState {
    /** Accepting serial actions. / 接受串行动作。 */
    RUNNING,
    /** Waiting for a safe boundary. / 等待安全边界。 */
    PAUSE_REQUESTED,
    /** No new actions until resumed. / 恢复前不产生新动作。 */
    PAUSED,
    /** Stopping at a safe boundary. / 在安全边界停止。 */
    CANCEL_REQUESTED,
    /** Owned operation has stopped. / 所属操作已停止。 */
    CANCELLED,
    /** Remote outcome needs reconciliation. / 远端结果需要核实。 */
    UNKNOWN,
    /** Actual deployment and health checks succeeded. / 实际部署和健康检查成功。 */
    SUCCEEDED,
    /** Known failure with no automatic replay. / 已知失败，不自动重放。 */
    FAILED,
    /** App exited; historical record only. / App 已退出，仅保留历史记录。 */
    INTERRUPTED;
}

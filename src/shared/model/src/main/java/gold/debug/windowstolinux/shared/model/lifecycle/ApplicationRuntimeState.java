package gold.debug.windowstolinux.shared.model.lifecycle;

/** Aggregated live runtime state of every component in one application. / 一个应用全部组件的实时运行汇总状态。 */
public enum ApplicationRuntimeState {
    /** Every component is running. / 每个组件均在运行。 */
    RUNNING,
    /** Every component is stopped. / 每个组件均已停止。 */
    STOPPED,
    /** Verified components contain both running and stopped states. / 已验证组件同时包含运行和停止状态。 */
    PARTIALLY_RUNNING,
    /** At least one component state is unknown. / 至少一个组件状态未知。 */
    UNKNOWN,
    /** At least one component reports an error. / 至少一个组件报告错误。 */
    ERROR
}

package gold.debug.windowstolinux.shared.model.lifecycle;

/**
 * Aggregated live autostart state of every component in one application. / 一个应用全部组件的实时自启汇总状态。
 */
public enum ApplicationAutostartState {
    /**
     * Every component is enabled. / 每个组件均已启用自启。
     */
    ENABLED,
    /**
     * Every component is disabled. / 每个组件均已禁用自启。
     */
    DISABLED,
    /**
     * Verified components contain both enabled and disabled states. / 已验证组件同时包含启用和禁用状态。
     */
    PARTIALLY_ENABLED,
    /**
     * At least one component state is unknown. / 至少一个组件状态未知。
     */
    UNKNOWN,
    /**
     * At least one component reports an error. / 至少一个组件报告错误。
     */
    ERROR
}

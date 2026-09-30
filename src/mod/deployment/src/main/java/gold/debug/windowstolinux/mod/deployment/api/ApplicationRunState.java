package gold.debug.windowstolinux.mod.deployment.api;

/** Verified application run status or explicit uncertainty. / 已验证的应用运行状态或明确未知。 */
public enum ApplicationRunState {
    /** All required components are running and healthy. / 所有必需组件运行且健康。 */ RUNNING,
    /** All required components are stopped. / 所有必需组件已停止。 */ STOPPED,
    /** A component or health gate failed. / 组件或健康门槛失败。 */ DEGRADED,
    /** Current remote state cannot be established. / 无法确认当前远端状态。 */ UNKNOWN
}

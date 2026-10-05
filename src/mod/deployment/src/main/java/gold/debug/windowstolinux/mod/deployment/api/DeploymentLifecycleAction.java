package gold.debug.windowstolinux.mod.deployment.api;

/** Explicit managed lifecycle action. / 明确的受管生命周期动作。 */
public enum DeploymentLifecycleAction {
    /** Start a stopped application. / 启动已停止应用。 */ START,
    /** Stop an application. / 停止应用。 */ STOP,
    /** Stop and then start in dependency order. / 按依赖顺序停止后启动。 */ RESTART,
    /** Enable automatic startup without changing runtime state. / 启用自动启动而不更改运行状态。 */ ENABLE_AUTOSTART,
    /** Disable automatic startup without changing runtime state. / 禁用自动启动而不更改运行状态。 */ DISABLE_AUTOSTART;

    /** Returns the original fixed helper verb. / 返回原固定helper动作。
     * @return exact helper lifecycle verb / 精确helper生命周期动作 */
    public String verb() {
        return switch (this) {
            case ENABLE_AUTOSTART -> "enable";
            case DISABLE_AUTOSTART -> "disable";
            default -> name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** Identifies state-declaration actions rather than runtime changes. / 标识状态声明动作而非运行状态变更。
     * @return whether this action changes automatic startup / 此动作是否更改自动启动 */
    public boolean autostart() {
        return this == ENABLE_AUTOSTART || this == DISABLE_AUTOSTART;
    }
}

package gold.debug.windowstolinux.mod.deployment.api;

/** Explicit managed lifecycle action. / 明确的受管生命周期动作。 */
public enum DeploymentLifecycleAction {
    /** Start a stopped application. / 启动已停止应用。 */ START,
    /** Stop an application. / 停止应用。 */ STOP,
    /** Stop and then start in dependency order. / 按依赖顺序停止后启动。 */ RESTART
}

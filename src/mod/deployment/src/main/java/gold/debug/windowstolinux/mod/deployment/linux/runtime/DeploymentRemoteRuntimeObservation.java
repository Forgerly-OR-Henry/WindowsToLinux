package gold.debug.windowstolinux.mod.deployment.linux.runtime;

import gold.debug.windowstolinux.mod.deployment.api.ApplicationAutostartState;

/** Exact owned runtime observation after a remote publication or rollback. / 远端发布或回滚后的精确所属运行时观测。
 * @param state verified actual runtime state / 已核实实际运行状态
 * @param autostart exact observed automatic startup / 精确观测的自动启动 */
public record DeploymentRemoteRuntimeObservation(StateType state, ApplicationAutostartState autostart) {
    /** Keeps absent historical automatic-startup evidence explicitly unknown. / 将缺失的历史自动启动依据明确保留为未知。
     * @param state verified actual runtime state / 已核实实际运行状态 */
    public DeploymentRemoteRuntimeObservation(StateType state) {
        this(state, state == StateType.INSTALLED ? ApplicationAutostartState.NOT_APPLICABLE
            : ApplicationAutostartState.UNKNOWN);
    }

    /** Distinguishes installed jobs, stopped services and known failed services. / 区分已安装任务、停止服务与已知失败服务。 */
    public enum StateType {
        /** Verified active daemon. / 已核实活动守护进程。 */
        RUNNING,
        /** Verified installed bounded job. / 已核实已安装按需任务。 */
        INSTALLED,
        /** Verified stopped daemon. / 已核实已停止守护进程。 */
        STOPPED,
        /** Verified failed service. / 已核实失败服务。 */
        ERROR
    }

    /** Requires an explicit observation rather than a default state. / 要求显式观测而非默认状态。
     * @param state verified state / 已核实状态
     * @param autostart exact observed automatic startup / 精确观测的自动启动 */
    public DeploymentRemoteRuntimeObservation {
        java.util.Objects.requireNonNull(state);
        java.util.Objects.requireNonNull(autostart);
    }
}

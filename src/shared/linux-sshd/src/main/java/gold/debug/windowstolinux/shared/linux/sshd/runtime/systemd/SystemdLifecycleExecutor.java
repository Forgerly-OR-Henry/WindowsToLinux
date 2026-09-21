package gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ManagedRuntimeProtocolExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.time.Duration;
import java.util.Objects;

/**
 * Executes lifecycle changes only after ownership observation and verifies their postconditions. / 仅在归属观察后执行生命周期变更并验证其后置条件。
 */
public final class SystemdLifecycleExecutor {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;
    /**
     * Bound managed runtime protocol executor collaborator for runtimes.
     * <p>处理运行时集合的受管运行时协议执行器协作对象。
     */
    private final ManagedRuntimeProtocolExecutor runtimes;
    /**
     * Observer.
     * <p>观测器。
     */
    private final SystemdOwnershipObserver observer;
    /**
     * Health.
     * <p>健康。
     */
    private final SystemdHealthProbe health;
    /**
     * Account name used by the reviewed connection.
     * <p>已审阅连接使用的账户名。
     */
    private final String username;

    /**
     * Creates a lifecycle executor. / 创建生命周期执行器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param runtimes runtimes / 运行时集合
     * @param observer observer / 观测器
     * @param health health / 健康
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SystemdLifecycleExecutor(SshCommandExecutor commands, ManagedRuntimeProtocolExecutor runtimes,
                                    SystemdOwnershipObserver observer, SystemdHealthProbe health, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.health = Objects.requireNonNull(health, "health");
        this.username = Objects.requireNonNull(username, "username");
    }

    /**
     * Executes an action for a legacy managed unit. / 为旧受管 unit 执行动作。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LifecycleObservation execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck)
            throws LinuxOperationException {
        return execute(application, action, healthCheck, SystemdUnitRenderer.render(username, application));
    }

    /**
     * Executes an action after matching complete expected unit content. / 在匹配完整预期 unit 内容后执行动作。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param expectedUnitContent expected unit content / 预期单元内容
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LifecycleObservation execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
                                        String expectedUnitContent) throws LinuxOperationException {
        LifecycleObservation before = observer.observe(application, expectedUnitContent);
        if (!before.ownershipVerified()) return before;
        if (action == LifecycleAction.REFRESH_STATUS) return before;
        if (before.runtimeState() == RuntimeState.UNKNOWN || (before.runtimeState() == RuntimeState.ERROR
                && action != LifecycleAction.STOP && action != LifecycleAction.DISABLE_AUTOSTART)) {
            throw LinuxOperationException.create(LinuxOperationFailureType.LIFECYCLE_ACTION_FAILED,
                    "Native runtime state requires refresh or a verified STOP before START; " + before.evidence());
        }
        if (action == LifecycleAction.START && before.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.create(LinuxOperationFailureType.START_REQUIRES_STOPPED,
                    "Start is allowed only for a managed application confirmed as stopped");
        }
        String actionVerb = switch (action) {
            case START -> "start";
            case STOP -> "stop";
            case RESTART -> "restart";
            case ENABLE_AUTOSTART -> "enable";
            case DISABLE_AUTOSTART -> "disable";
            case REFRESH_STATUS -> null;
        };
        var result = runtimes.lifecycle(application, actionVerb);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.LIFECYCLE_ACTION_FAILED, result.evidence());
        }
        if ((action == LifecycleAction.START || action == LifecycleAction.RESTART)
                && !health.check(application, healthCheck).healthy()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.POST_START_HEALTH_FAILED, "Post-start health check failed");
        }
        LifecycleObservation after = action == LifecycleAction.STOP
                ? awaitStopped(application, expectedUnitContent) : observer.observe(application, expectedUnitContent);
        verifyPostconditions(application, action, after);
        return new LifecycleObservation(after.application(), after.runtimeState(), after.autostartState(),
                after.ownershipVerified(), after.observedAt(), after.evidence() + "; " + result.evidence());
    }

    /**
     * Waits for stopped.
     * <p>等待已停止。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param expectedUnitContent expected unit content / 预期单元内容
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private LifecycleObservation awaitStopped(ManagedApplication application, String expectedUnitContent)
            throws LinuxOperationException {
        LifecycleObservation observation = observer.observe(application, expectedUnitContent);
        for (int attempt = 0; observation.runtimeState() != RuntimeState.STOPPED && attempt < 20; attempt++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw LinuxOperationException.create(LinuxOperationFailureType.STOP_WAIT_INTERRUPTED,
                        "Interrupted while waiting to verify the stopped state", interrupted);
            }
            observation = observer.observe(application, expectedUnitContent);
        }
        return observation;
    }

    /**
     * Verifies postconditions.
     * <p>验证后置条件集合。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param after after / 之后
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void verifyPostconditions(ManagedApplication application, LifecycleAction action, LifecycleObservation after)
            throws LinuxOperationException {
        if (action == LifecycleAction.STOP && after.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.create(LinuxOperationFailureType.STOP_UNVERIFIED,
                    "Stop operation could not be verified remotely: state=" + after.runtimeState()
                            + ", evidence=" + after.evidence());
        }
        if (action == LifecycleAction.STOP && !commands.exec("test \"$(systemctl show --value --property MainPID "
                + SshCommandExecutor.quote(application.systemdUnit()) + ")\" = 0", Duration.ofSeconds(10), false).succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.MAIN_PROCESS_STILL_RUNNING,
                    "A systemd main process is still present after stop");
        }
        if (action == LifecycleAction.ENABLE_AUTOSTART && after.autostartState() != AutostartState.ENABLED) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENABLE_AUTOSTART_UNVERIFIED,
                    "Autostart enablement could not be verified remotely");
        }
        if (action == LifecycleAction.DISABLE_AUTOSTART && after.autostartState() != AutostartState.DISABLED) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DISABLE_AUTOSTART_UNVERIFIED,
                    "Autostart disablement could not be verified remotely");
        }
    }
}

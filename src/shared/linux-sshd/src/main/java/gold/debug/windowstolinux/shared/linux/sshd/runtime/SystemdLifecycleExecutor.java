package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedRuntimeController;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.time.Duration;
import java.util.Objects;

/** Executes lifecycle changes only after ownership observation and verifies their postconditions. / 仅在归属观察后执行生命周期变更并验证其后置条件。 */
public final class SystemdLifecycleExecutor {
    private final SshCommandExecutor commands;
    private final ManagedRuntimeController runtimes;
    private final SystemdOwnershipObserver observer;
    private final SystemdHealthChecker health;
    private final String username;

    /** Creates a lifecycle executor. / 创建生命周期执行器。 */
    public SystemdLifecycleExecutor(SshCommandExecutor commands, ManagedRuntimeController runtimes,
                                    SystemdOwnershipObserver observer, SystemdHealthChecker health, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.health = Objects.requireNonNull(health, "health");
        this.username = Objects.requireNonNull(username, "username");
    }

    /** Executes an action for a legacy managed unit. / 为旧受管 unit 执行动作。 */
    public LifecycleObservation execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck)
            throws LinuxOperationException {
        return execute(application, action, healthCheck, SystemdUnitRenderer.render(username, application));
    }

    /** Executes an action after matching complete expected unit content. / 在匹配完整预期 unit 内容后执行动作。 */
    public LifecycleObservation execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
                                        String expectedUnitContent) throws LinuxOperationException {
        LifecycleObservation before = observer.observe(application, expectedUnitContent);
        if (!before.ownershipVerified()) return before;
        if (action == LifecycleAction.START && before.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.localized("linux.error.startRequiresStopped",
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
        if (actionVerb != null && !runtimes.lifecycle(application, actionVerb).succeeded()) {
            throw LinuxOperationException.localized("linux.error.lifecycleActionFailed", "systemd lifecycle operation failed");
        }
        if ((action == LifecycleAction.START || action == LifecycleAction.RESTART)
                && !health.check(application, healthCheck).healthy()) {
            throw LinuxOperationException.localized("linux.error.postStartHealthFailed", "Post-start health check failed");
        }
        LifecycleObservation after = action == LifecycleAction.STOP
                ? awaitStopped(application, expectedUnitContent) : observer.observe(application, expectedUnitContent);
        verifyPostconditions(application, action, after);
        return after;
    }

    private LifecycleObservation awaitStopped(ManagedApplication application, String expectedUnitContent)
            throws LinuxOperationException {
        LifecycleObservation observation = observer.observe(application, expectedUnitContent);
        for (int attempt = 0; observation.runtimeState() != RuntimeState.STOPPED && attempt < 20; attempt++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw LinuxOperationException.localized("linux.error.stopWaitInterrupted",
                        "Interrupted while waiting to verify the stopped state", interrupted);
            }
            observation = observer.observe(application, expectedUnitContent);
        }
        return observation;
    }

    private void verifyPostconditions(ManagedApplication application, LifecycleAction action, LifecycleObservation after)
            throws LinuxOperationException {
        if (action == LifecycleAction.STOP && after.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.localized("linux.error.stopUnverified",
                    "Stop operation could not be verified remotely: state=" + after.runtimeState()
                            + ", evidence=" + after.evidence());
        }
        if (action == LifecycleAction.STOP && !commands.exec("test \"$(systemctl show --value --property MainPID "
                + SshCommandExecutor.quote(application.systemdUnit()) + ")\" = 0", Duration.ofSeconds(10), false).succeeded()) {
            throw LinuxOperationException.localized("linux.error.mainProcessStillRunning",
                    "A systemd main process is still present after stop");
        }
        if (action == LifecycleAction.ENABLE_AUTOSTART && after.autostartState() != AutostartState.ENABLED) {
            throw LinuxOperationException.localized("linux.error.enableAutostartUnverified",
                    "Autostart enablement could not be verified remotely");
        }
        if (action == LifecycleAction.DISABLE_AUTOSTART && after.autostartState() != AutostartState.DISABLED) {
            throw LinuxOperationException.localized("linux.error.disableAutostartUnverified",
                    "Autostart disablement could not be verified remotely");
        }
    }
}

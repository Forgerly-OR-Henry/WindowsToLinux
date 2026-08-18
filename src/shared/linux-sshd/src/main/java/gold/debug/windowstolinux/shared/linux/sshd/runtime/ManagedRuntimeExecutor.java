package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.sshd.runtime.ContainerRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdHealthChecker;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdOwnershipObserver;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.release.ContainerReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.release.DeploymentReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.Objects;

/** Restores lifecycle control from sealed remote runtime markers after an application restart. / 应用重启后从已封存的远端运行时标记恢复生命周期控制。 */
public final class ManagedRuntimeExecutor {
    private final ManagedRuntimeKindProbe runtimeKinds;
    private final DeploymentReleaseProtocolExecutor deploymentProtocol;
    private final ContainerReleaseProtocolExecutor containerProtocol;
    private final SystemdOwnershipObserver systemdObservation;
    private final SystemdLifecycleExecutor systemdLifecycle;
    private final SystemdHealthChecker systemdHealth;
    private final ContainerRuntimeExecutor containerRuntime;

    /** Creates the persisted lifecycle runtime dispatcher. / 创建持久化生命周期运行时分派器。 */
    public ManagedRuntimeExecutor(ManagedRuntimeKindProbe runtimeKinds,
                                  DeploymentReleaseProtocolExecutor deploymentProtocol,
                                  ContainerReleaseProtocolExecutor containerProtocol,
                                  SystemdOwnershipObserver systemdObservation,
                                  SystemdLifecycleExecutor systemdLifecycle,
                                  SystemdHealthChecker systemdHealth,
                                  ContainerRuntimeExecutor containerRuntime) {
        this.runtimeKinds = Objects.requireNonNull(runtimeKinds, "runtimeKinds");
        this.deploymentProtocol = Objects.requireNonNull(deploymentProtocol, "deploymentProtocol");
        this.containerProtocol = Objects.requireNonNull(containerProtocol, "containerProtocol");
        this.systemdObservation = Objects.requireNonNull(systemdObservation, "systemdObservation");
        this.systemdLifecycle = Objects.requireNonNull(systemdLifecycle, "systemdLifecycle");
        this.systemdHealth = Objects.requireNonNull(systemdHealth, "systemdHealth");
        this.containerRuntime = Objects.requireNonNull(containerRuntime, "containerRuntime");
    }

    /** Observes the remotely sealed runtime kind and its actual state. / 观察远端封存的运行时类型及其实际状态。 */
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        return observe(application, runtimeKinds.inspect(application));
    }

    /** Executes one lifecycle action without relying on a transient deployment form. / 在不依赖临时部署表单的情况下执行生命周期动作。 */
    public LifecycleObservation execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck)
            throws LinuxOperationException {
        ManagedRuntimeIdentity identity = runtimeKinds.inspect(application);
        LifecycleObservation before = observe(application, identity);
        if (!before.ownershipVerified() || action == LifecycleAction.REFRESH_STATUS) {
            return before;
        }
        if (action == LifecycleAction.START && before.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.localized("linux.error.startRequiresStopped",
                    "Start is allowed only for a managed runtime confirmed as stopped");
        }
        if (identity.kind() == ManagedRuntimeIdentity.Kind.ORDINARY) {
            return systemdLifecycle.execute(application, action, healthCheck);
        }
        String verb = verb(action);
        RemoteStepResult result = identity.kind() == ManagedRuntimeIdentity.Kind.CONTAINER
                ? containerProtocol.lifecycle(application, verb)
                : deploymentProtocol.lifecycle(application, verb);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.lifecycleActionFailed", result.evidence());
        }
        if (action == LifecycleAction.START || action == LifecycleAction.RESTART) {
            boolean healthy = identity.kind() == ManagedRuntimeIdentity.Kind.CONTAINER
                    ? containerRuntime.checkHealth(application, identity.containerEngine().orElseThrow(), healthCheck).healthy()
                    : systemdHealth.check(application, healthCheck).healthy();
            if (!healthy) {
                throw LinuxOperationException.localized("linux.error.postStartHealthFailed",
                        "Post-start health check failed");
            }
        }
        LifecycleObservation after = observe(application, runtimeKinds.inspect(application));
        verifyPostcondition(action, after);
        return after;
    }

    private LifecycleObservation observe(ManagedApplication application, ManagedRuntimeIdentity identity)
            throws LinuxOperationException {
        return switch (identity.kind()) {
            case ORDINARY -> systemdObservation.observe(application);
            case DEPLOYMENT -> deploymentProtocol.observe(application);
            case CONTAINER -> containerRuntime.observe(application, identity.containerEngine().orElseThrow());
        };
    }

    private static String verb(LifecycleAction action) {
        return switch (action) {
            case START -> "start";
            case STOP -> "stop";
            case RESTART -> "restart";
            case ENABLE_AUTOSTART -> "enable";
            case DISABLE_AUTOSTART -> "disable";
            case REFRESH_STATUS -> throw new IllegalStateException("refresh is handled before dispatch");
        };
    }

    private static void verifyPostcondition(LifecycleAction action, LifecycleObservation observation)
            throws LinuxOperationException {
        boolean verified = switch (action) {
            case START, RESTART -> observation.runtimeState() == RuntimeState.RUNNING;
            case STOP -> observation.runtimeState() == RuntimeState.STOPPED;
            case ENABLE_AUTOSTART -> observation.autostartState() == AutostartState.ENABLED;
            case DISABLE_AUTOSTART -> observation.autostartState() == AutostartState.DISABLED;
            case REFRESH_STATUS -> true;
        };
        if (!verified) {
            throw LinuxOperationException.localized("linux.error.lifecycleActionFailed",
                    "Managed runtime lifecycle postcondition was not verified: " + observation.evidence());
        }
    }
}

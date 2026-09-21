package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.sshd.runtime.ContainerRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdHealthProbe;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdOwnershipObserver;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release.ContainerReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release.DeploymentReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import java.util.Objects;
import java.util.Optional;

/**
 * Restores lifecycle control from sealed remote runtime markers after an application restart. / 应用重启后从已封存的远端运行时标记恢复生命周期控制。
 */
public final class ManagedRuntimeExecutor {
    /**
     * Runtime kinds.
     * <p>运行时种类集合。
     */
    private final ManagedRuntimeKindProbe runtimeKinds;
    /**
     * Bound deployment release protocol executor collaborator for deployment protocol.
     * <p>处理部署协议的部署发布协议执行器协作对象。
     */
    private final DeploymentReleaseProtocolExecutor deploymentProtocol;
    /**
     * Bound container release protocol executor collaborator for container protocol.
     * <p>处理容器协议的容器发布协议执行器协作对象。
     */
    private final ContainerReleaseProtocolExecutor containerProtocol;
    /**
     * Systemd observation.
     * <p>systemd观测。
     */
    private final SystemdOwnershipObserver systemdObservation;
    /**
     * Bound systemd lifecycle executor collaborator for systemd lifecycle.
     * <p>处理systemd生命周期的Systemd生命周期执行器协作对象。
     */
    private final SystemdLifecycleExecutor systemdLifecycle;
    /**
     * Systemd health.
     * <p>systemd健康。
     */
    private final SystemdHealthProbe systemdHealth;
    /**
     * Bound container runtime executor collaborator for container runtime.
     * <p>处理容器运行时的容器运行时执行器协作对象。
     */
    private final ContainerRuntimeExecutor containerRuntime;

    /**
     * Creates the persisted lifecycle runtime dispatcher. / 创建持久化生命周期运行时分派器。
     *
     * @param runtimeKinds runtime kinds / 运行时种类集合
     * @param deploymentProtocol deployment protocol / 部署协议
     * @param containerProtocol container protocol / 容器协议
     * @param systemdObservation systemd observation / systemd观测
     * @param systemdLifecycle systemd lifecycle / systemd生命周期
     * @param systemdHealth systemd health / systemd健康
     * @param containerRuntime container runtime / 容器运行时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRuntimeExecutor(ManagedRuntimeKindProbe runtimeKinds,
                                  DeploymentReleaseProtocolExecutor deploymentProtocol,
                                  ContainerReleaseProtocolExecutor containerProtocol,
                                  SystemdOwnershipObserver systemdObservation,
                                  SystemdLifecycleExecutor systemdLifecycle,
                                  SystemdHealthProbe systemdHealth,
                                  ContainerRuntimeExecutor containerRuntime) {
        this.runtimeKinds = Objects.requireNonNull(runtimeKinds, "runtimeKinds");
        this.deploymentProtocol = Objects.requireNonNull(deploymentProtocol, "deploymentProtocol");
        this.containerProtocol = Objects.requireNonNull(containerProtocol, "containerProtocol");
        this.systemdObservation = Objects.requireNonNull(systemdObservation, "systemdObservation");
        this.systemdLifecycle = Objects.requireNonNull(systemdLifecycle, "systemdLifecycle");
        this.systemdHealth = Objects.requireNonNull(systemdHealth, "systemdHealth");
        this.containerRuntime = Objects.requireNonNull(containerRuntime, "containerRuntime");
    }

    /**
     * Observes the remotely sealed runtime kind and its actual state. / 观察远端封存的运行时类型及其实际状态。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        return observe(application, runtimeKinds.inspect(application));
    }

    /**
     * Rechecks runtime ownership and action prerequisites, executes the selected lifecycle change and verifies its postcondition and startup health.
     * <p>复核运行归属及动作前提条件、执行所选生命周期变更，并验证后置条件及启动健康。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return fresh verified lifecycle observation, or the original observation when no mutation is admitted / 新鲜已验证生命周期观测；未准入变更时为原始观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public LifecycleObservation execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck)
            throws LinuxOperationException {
        ManagedRuntimeIdentity identity = runtimeKinds.inspect(application);
        return execute(application, identity, action, healthCheck, true);
    }

    /**
     * Rechecks runtime ownership and action prerequisites, executes the selected lifecycle change and verifies its postcondition and startup health.
     * <p>复核运行归属及动作前提条件、执行所选生命周期变更，并验证后置条件及启动健康。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return fresh verified lifecycle observation, or the original observation when no mutation is admitted / 新鲜已验证生命周期观测；未准入变更时为原始观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public LifecycleObservation execute(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                        LifecycleAction action) throws LinuxOperationException {
        Objects.requireNonNull(runtime, "runtime");
        ManagedRuntimeIdentity identity = runtimeKinds.inspect(application);
        return execute(application, identity, action, runtime.healthCheck(), false);
    }

    /**
     * Rechecks runtime ownership and action prerequisites, executes the selected lifecycle change and verifies its postcondition and startup health.
     * <p>复核运行归属及动作前提条件、执行所选生命周期变更，并验证后置条件及启动健康。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param identity identity / 身份
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param persisted persisted / 已持久化
     * @return fresh verified lifecycle observation, or the original observation when no mutation is admitted / 新鲜已验证生命周期观测；未准入变更时为原始观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private LifecycleObservation execute(ManagedApplication application, ManagedRuntimeIdentity identity,
            LifecycleAction action, HealthCheck healthCheck, boolean persisted) throws LinuxOperationException {
        Objects.requireNonNull(action, "action");
        LifecycleObservation before = observe(application, identity);
        if (!before.ownershipVerified() || action == LifecycleAction.REFRESH_STATUS) {
            return before;
        }
        if (identity.kind() != ManagedRuntimeIdentity.Kind.CONTAINER
                && (before.runtimeState() == RuntimeState.UNKNOWN || (before.runtimeState() == RuntimeState.ERROR
                && action != LifecycleAction.STOP && action != LifecycleAction.DISABLE_AUTOSTART))) {
            throw LinuxOperationException.create(LinuxOperationFailureType.LIFECYCLE_ACTION_FAILED,
                    "Native runtime state requires refresh or a verified STOP before START; " + before.evidence());
        }
        if (action == LifecycleAction.START && before.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.create(LinuxOperationFailureType.START_REQUIRES_STOPPED,
                    "Start is allowed only for a managed runtime confirmed as stopped");
        }
        if (identity.kind() == ManagedRuntimeIdentity.Kind.ORDINARY) {
            LifecycleObservation after = systemdLifecycle.execute(application, action, healthCheck);
            verifyPostcondition(action, after);
            return after;
        }
        if (identity.mode() == gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND)
            throw LinuxOperationException.create(LinuxOperationFailureType.LIFECYCLE_ACTION_FAILED,
                    "On-demand tools have no lifecycle actions; use the reviewed application command");
        String verb = verb(action);
        RemoteStepResult result = identity.kind() == ManagedRuntimeIdentity.Kind.CONTAINER
                ? containerProtocol.lifecycle(application, verb)
                : deploymentProtocol.lifecycle(application, verb);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.LIFECYCLE_ACTION_FAILED, result.evidence());
        }
        if (action == LifecycleAction.START || action == LifecycleAction.RESTART) {
            boolean healthy = identity.kind() == ManagedRuntimeIdentity.Kind.CONTAINER
                    ? containerRuntime.checkHealth(application, identity.containerEngine().orElseThrow(), healthCheck).healthy()
                    : systemdHealth.check(application, healthCheck).healthy();
            if (!healthy) {
                throw LinuxOperationException.create(LinuxOperationFailureType.POST_START_HEALTH_FAILED,
                        "Post-start health check failed");
            }
        }
        LifecycleObservation after = observe(application, persisted ? runtimeKinds.inspect(application) : identity);
        verifyPostcondition(action, after);
        return new LifecycleObservation(after.application(), after.runtimeState(), after.autostartState(),
                after.ownershipVerified(), after.observedAt(), after.evidence() + "; " + result.evidence());
    }

    /**
     * Observes lifecycle observation.
     * <p>观测生命周期观测。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param identity identity / 身份
     * @return constructed or resolved lifecycle observation / 构造或解析得到的生命周期观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private LifecycleObservation observe(ManagedApplication application, ManagedRuntimeIdentity identity)
            throws LinuxOperationException {
        if (identity.mode() == gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND)
            return new LifecycleObservation(application, RuntimeState.INSTALLED, AutostartState.DISABLED, true,
                    java.time.Instant.now(), "Owned on-demand application is installed; no persistent process required");
        return switch (identity.kind()) {
            case ORDINARY -> systemdObservation.observe(application);
            case DEPLOYMENT -> deploymentProtocol.observe(application);
            case CONTAINER -> containerRuntime.observe(application, identity.containerEngine().orElseThrow());
        };
    }

    /**
     * Maps a mutating lifecycle action to its fixed helper verb; status refresh is handled separately.
     * <p>将变更生命周期动作映射为固定 helper 动词；状态刷新另行处理。
     *
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return verb text / 操作动词文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
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

    /**
     * Verifies postcondition.
     * <p>验证后置条件。
     *
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param observation observation / 观测
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static void verifyPostcondition(LifecycleAction action, LifecycleObservation observation)
            throws LinuxOperationException {
        boolean verified = switch (action) {
            case START, RESTART -> observation.runtimeState() == RuntimeState.RUNNING;
            case STOP -> observation.runtimeState() == RuntimeState.STOPPED;
            case ENABLE_AUTOSTART -> observation.autostartState() == AutostartState.ENABLED;
            case DISABLE_AUTOSTART -> observation.autostartState() == AutostartState.DISABLED;
            case REFRESH_STATUS -> true;
        };
        if (!observation.ownershipVerified() || !verified) {
            throw LinuxOperationException.create(LinuxOperationFailureType.LIFECYCLE_ACTION_FAILED,
                    "Managed runtime lifecycle postcondition was not verified: " + observation.evidence());
        }
    }
}

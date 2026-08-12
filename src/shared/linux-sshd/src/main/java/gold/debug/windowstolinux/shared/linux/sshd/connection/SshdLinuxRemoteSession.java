package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.build.MavenBuildExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.build.DeploymentBuildExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.distro.ManagedEnvironmentExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.DeploymentReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.ContainerReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.DeploymentInputProtocolExecutor;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.SystemdHealthChecker;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.SystemdLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.SystemdOwnershipObserver;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ContainerRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ManagedRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ManagedRuntimeKindProbe;
import gold.debug.windowstolinux.shared.linux.sshd.transfer.SshdSourceTransfer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;

import java.util.List;

/**
 * Unified session facade delegating each typed capability to its implementation package.
 *
 * <p>将各项类型化能力委派给其实现包的统一会话门面。
 */
final class SshdLinuxRemoteSession implements DeploymentRemoteSession {
    private final SshClient client;
    private final ClientSession session;
    private final String username;
    private final SshdCapabilityCollector capabilities;
    private final SshdPlatformCapabilityCollector deploymentCapabilities;
    private final ManagedEnvironmentExecutor environment;
    private final SshdSourceTransfer transfer;
    private final MavenBuildExecutor build;
    private final DeploymentBuildExecutor deploymentBuild;
    private final ManagedReleaseProtocolExecutor protocol;
    private final DeploymentReleaseProtocolExecutor deploymentProtocol;
    private final ContainerReleaseProtocolExecutor containerProtocol;
    private final DeploymentInputProtocolExecutor deploymentInputs;
    private final SystemdHealthChecker systemdHealth;
    private final SystemdOwnershipObserver systemdObservation;
    private final SystemdLifecycleExecutor systemdLifecycle;
    private final ContainerRuntimeExecutor containerRuntime;
    private final ManagedRuntimeExecutor managedRuntime;

    SshdLinuxRemoteSession(SshClient client, ClientSession session,
                              SshEndpoint endpoint, String hostFingerprint) {
        this.client = client;
        this.session = session;
        this.username = endpoint.username();
        SshCommandExecutor commands = new SshCommandExecutor(session);
        this.capabilities = new SshdCapabilityCollector(commands, hostFingerprint);
        this.deploymentCapabilities = new SshdPlatformCapabilityCollector(commands, hostFingerprint);
        this.environment = new ManagedEnvironmentExecutor(
                commands, capabilities, deploymentCapabilities, endpoint.serverId(), endpoint.username());
        this.protocol = new ManagedReleaseProtocolExecutor(commands);
        this.deploymentProtocol = new DeploymentReleaseProtocolExecutor(commands);
        this.containerProtocol = new ContainerReleaseProtocolExecutor(commands);
        this.deploymentInputs = new DeploymentInputProtocolExecutor(commands);
        this.transfer = new SshdSourceTransfer(session, commands, protocol);
        this.build = new MavenBuildExecutor(commands, endpoint.username());
        this.deploymentBuild = new DeploymentBuildExecutor(commands, endpoint.username());
        this.systemdHealth = new SystemdHealthChecker(commands);
        this.systemdObservation = new SystemdOwnershipObserver(commands, endpoint.username());
        this.systemdLifecycle = new SystemdLifecycleExecutor(
                commands, protocol, systemdObservation, systemdHealth, endpoint.username());
        this.containerRuntime = new ContainerRuntimeExecutor(commands);
        this.managedRuntime = new ManagedRuntimeExecutor(new ManagedRuntimeKindProbe(commands, protocol),
                deploymentProtocol, containerProtocol, systemdObservation, systemdLifecycle, systemdHealth,
                containerRuntime);
    }

    @Override
    public ServerCapabilities collectCapabilities() throws LinuxOperationException {
        return capabilities.collect();
    }

    @Override
    public LinuxCapabilities collectDeploymentCapabilities() throws LinuxOperationException {
        return deploymentCapabilities.collectDeploymentCapabilities();
    }

    @Override
    public EnvironmentPreparationResult prepareEnvironment(
            EnvironmentPreparationApproval approval) throws LinuxOperationException {
        return environment.prepare(approval);
    }

    @Override
    public UploadReceipt uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace)
            throws LinuxOperationException {
        return transfer.upload(archive, workspace);
    }

    @Override
    public RemoteBuildResult build(RemoteWorkspace workspace, BuildLimits limits) throws LinuxOperationException {
        return build.build(workspace, limits);
    }

    @Override
    public DeploymentBuildResult buildDeployment(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                                 RemoteWorkspace workspace, BuildLimits limits,
                                                 ConfigurationSnapshot configuration)
            throws LinuxOperationException {
        return deploymentBuild.build(facts, runtime, workspace, limits, configuration);
    }

    @Override
    public DeploymentInputManifest stageDeploymentInputs(ManagedApplication application,
                                                         ConfigurationSnapshot configuration,
                                                         List<ResolvedSecretRevision> secrets)
            throws LinuxOperationException {
        return deploymentInputs.stage(application, configuration, secrets);
    }

    @Override
    public RemoteStepResult cleanupCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
        return protocol.cleanupCandidate(workspace);
    }

    @Override
    public ReleaseSnapshot snapshot(ManagedApplication application) throws LinuxOperationException {
        return protocol.snapshot(application);
    }

    @Override
    public RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace,
                                    RemoteBuildResult buildResult, ReleaseSnapshot snapshot)
            throws LinuxOperationException {
        return protocol.publish(application, workspace, buildResult, snapshot);
    }

    @Override
    public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck)
            throws LinuxOperationException {
        return systemdHealth.check(application, healthCheck);
    }

    @Override
    public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application)
            throws LinuxOperationException {
        return protocol.retainRecentSuccessfulReleases(application);
    }

    @Override
    public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot,
                                     RemoteBuildResult buildResult) throws LinuxOperationException {
        return protocol.rollback(application, snapshot, buildResult);
    }

    @Override
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        return managedRuntime.observe(application);
    }

    @Override
    public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action,
                                                 HealthCheck healthCheck) throws LinuxOperationException {
        return managedRuntime.execute(application, action, healthCheck);
    }

    @Override
    public ReleaseSnapshot snapshotDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.snapshot(application, container);
        }
        return deploymentProtocol.snapshot(application, runtime);
    }

    @Override
    public RemoteStepResult publishDeployment(ManagedApplication application, RemoteWorkspace workspace,
                                              DeploymentBuildResult buildResult, String releaseIdentity,
                                              DeploymentRuntimeSpecification runtime, DeploymentInputManifest inputs,
                                              ReleaseSnapshot snapshot)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.publish(application, workspace, buildResult, releaseIdentity, container, inputs, snapshot);
        }
        return deploymentProtocol.publish(application, workspace, buildResult, releaseIdentity, runtime, inputs, snapshot);
    }

    @Override
    public RemoteStepResult rollbackDeployment(ManagedApplication application, ReleaseSnapshot snapshot,
                                               DeploymentBuildResult buildResult, String releaseIdentity,
                                               DeploymentRuntimeSpecification runtime, DeploymentInputManifest inputs)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.rollback(application, snapshot, buildResult, releaseIdentity, container, inputs);
        }
        return deploymentProtocol.rollback(application, snapshot, buildResult, releaseIdentity, runtime, inputs);
    }

    @Override
    public HealthCheckResult checkDeploymentHealth(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                                   HealthCheck healthCheck) throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerRuntime.checkHealth(application, container, healthCheck);
        }
        return systemdHealth.check(application, healthCheck);
    }

    @Override
    public LifecycleObservation observeDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerRuntime.observe(application, container);
        }
        return deploymentProtocol.observe(application);
    }

    @Override
    public LifecycleObservation executeDeploymentLifecycle(ManagedApplication application,
                                                            DeploymentRuntimeSpecification runtime,
                                                            LifecycleAction action) throws LinuxOperationException {
        LifecycleObservation before = observeDeployment(application, runtime);
        if (!before.ownershipVerified()) {
            return before;
        }
        if (action == LifecycleAction.REFRESH_STATUS) {
            return before;
        }
        if (action == LifecycleAction.START && before.runtimeState() != RuntimeState.STOPPED) {
            throw LinuxOperationException.localized("linux.error.startRequiresStopped",
                    "Start is allowed only for a managed runtime confirmed as stopped");
        }
        String verb = switch (action) {
            case START -> "start";
            case STOP -> "stop";
            case RESTART -> "restart";
            case ENABLE_AUTOSTART -> "enable";
            case DISABLE_AUTOSTART -> "disable";
            case REFRESH_STATUS -> throw new IllegalStateException("handled above");
        };
        RemoteStepResult result = runtime instanceof DeploymentRuntimeSpecification.Container container
                ? containerProtocol.lifecycle(application, verb)
                : deploymentProtocol.lifecycle(application, verb);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.lifecycleActionFailed", result.evidence());
        }
        if ((action == LifecycleAction.START || action == LifecycleAction.RESTART)
                && !checkDeploymentHealth(application, runtime, runtime.healthCheck()).healthy()) {
            throw LinuxOperationException.localized("linux.error.postStartHealthFailed",
                    "Post-start health check failed");
        }
        return observeDeployment(application, runtime);
    }

    @Override
    public void close() {
        try {
            session.close(true);
        } catch (RuntimeException ignored) {
            // Nothing useful can be done after a transport shutdown failure. / 传输关闭失败后无法再执行有意义的操作。
        } finally {
            SshdLinuxGateway.closeQuietly(client);
        }
    }
}

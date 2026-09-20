package gold.debug.windowstolinux.shared.linux.sshd.session;

import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.backup.SshdDatabaseOperationPort;
import gold.debug.windowstolinux.shared.linux.sshd.backup.SshdBackupArtifactPort;

import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.build.DeploymentBuildExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.distro.ManagedEnvironmentExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.distro.extension.registry.DistributionSetupRegistry;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.CandidateWorkspaceExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ManagedRuntimeProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release.DeploymentReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release.ContainerReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input.DeploymentInputProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.restore.SshdRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteSecretPayload;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdHealthProbe;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdOwnershipObserver;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ContainerRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ManagedRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ManagedRuntimeKindProbe;
import gold.debug.windowstolinux.shared.linux.sshd.execution.transfer.SshdSourceTransport;
import gold.debug.windowstolinux.shared.linux.sshd.execution.transfer.SshdRestoreTransport;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingEvidence;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingRequest;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.transfer.SourceUploadResult;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;

import java.util.List;

/**
 * Unified session facade delegating each typed capability to its implementation package.
 *
 * <p>将各项类型化能力委派给其实现包的统一会话门面。
 */
public final class SshdLinuxRemoteSession implements DeploymentRemoteSession {
    private final SshClient client;
    private final ClientSession session;
    private final String username;
    private final SshdCapabilityCollector capabilities;
    private final SshdPlatformCapabilityCollector deploymentCapabilities;
    private final ManagedEnvironmentExecutor environment;
    private final gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer selinux;
    private final SshdSourceTransport transfer;
    private final SshdRestoreTransport restoreTransfer;
    private final DeploymentBuildExecutor deploymentBuild;
    private final CandidateWorkspaceExecutor candidates;
    private final ManagedRuntimeProtocolExecutor runtimes;
    private final DeploymentReleaseProtocolExecutor deploymentProtocol;
    private final ContainerReleaseProtocolExecutor containerProtocol;
    private final DeploymentInputProtocolExecutor deploymentInputs;
    private final SystemdHealthProbe systemdHealth;
    private final SystemdOwnershipObserver systemdObservation;
    private final SystemdLifecycleExecutor systemdLifecycle;
    private final ContainerRuntimeExecutor containerRuntime;
    private final ManagedRuntimeExecutor managedRuntime;
    /** Returns native DB operations for this already authenticated and trusted connection. / 返回当前已认证且可信连接的原生数据库操作能力。 */
    @Override public gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort nativeDatabases() { return nativeDatabases; }
    private final SshdDatabaseOperationPort databases;
    private final gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort nativeDatabases;
    private final SshdBackupArtifactPort backupArtifacts;
    private final SshdRestoreActivationPort restoreActivation;
    private final gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort externalApplications;
    /** Returns bounded application discovery on this trusted session. / 返回当前可信会话上的有界应用发现能力。 */
    @Override public gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort externalApplications() { return externalApplications; }

    /** Creates an instance of this type. / 创建此类型的实例。 */
    public SshdLinuxRemoteSession(SshClient client, ClientSession session,
                              SshEndpoint endpoint, String hostFingerprint) {
        this.client = client;
        this.session = session;
        this.username = endpoint.username();
        SshCommandExecutor commands = new SshCommandExecutor(session);
        this.externalApplications = new gold.debug.windowstolinux.shared.linux.sshd.runtime.SshdExternalApplicationPort(commands);
        this.selinux = new gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.SelinuxPreparationExecutor(commands, endpoint.serverId());
        this.databases = new SshdDatabaseOperationPort(commands);
        this.nativeDatabases = new gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.database.SshdNativeDatabasePort(commands);
        this.backupArtifacts = new SshdBackupArtifactPort(commands);
        this.restoreActivation = new SshdRestoreActivationPort(commands);
        this.capabilities = new SshdCapabilityCollector(commands, hostFingerprint);
        this.deploymentCapabilities = new SshdPlatformCapabilityCollector(commands, hostFingerprint);
        this.environment = new ManagedEnvironmentExecutor(
                commands, capabilities, deploymentCapabilities, endpoint.serverId(), endpoint.username(),
                DistributionSetupRegistry.defaults());
        this.candidates = new CandidateWorkspaceExecutor(commands);
        this.runtimes = new ManagedRuntimeProtocolExecutor(commands);
        this.deploymentProtocol = new DeploymentReleaseProtocolExecutor(commands);
        this.containerProtocol = new ContainerReleaseProtocolExecutor(commands);
        this.deploymentInputs = new DeploymentInputProtocolExecutor(commands);
        this.transfer = new SshdSourceTransport(session, commands, candidates);
        this.restoreTransfer = new SshdRestoreTransport(session, candidates);
        this.deploymentBuild = new DeploymentBuildExecutor(commands, endpoint.username());
        this.systemdHealth = new SystemdHealthProbe(commands);
        this.systemdObservation = new SystemdOwnershipObserver(commands, endpoint.username());
        this.systemdLifecycle = new SystemdLifecycleExecutor(
                commands, runtimes, systemdObservation, systemdHealth, endpoint.username());
        this.containerRuntime = new ContainerRuntimeExecutor(commands);
        this.managedRuntime = new ManagedRuntimeExecutor(new ManagedRuntimeKindProbe(runtimes),
                deploymentProtocol, containerProtocol, systemdObservation, systemdLifecycle, systemdHealth,
                containerRuntime);
    }

    /** Performs the {@code collectCapabilities} operation. / 执行 {@code collectCapabilities} 操作。 */
    @Override
    public ServerCapabilityFacts collectCapabilities() throws LinuxOperationException {
        return capabilities.collect();
    }

    /** Performs the {@code collectDeploymentCapabilities} operation. / 执行 {@code collectDeploymentCapabilities} 操作。 */
    @Override
    public LinuxCapabilityFacts collectDeploymentCapabilities() throws LinuxOperationException {
        return deploymentCapabilities.collectDeploymentCapabilities();
    }

    @Override
    public gold.debug.windowstolinux.shared.linux.build.ToolchainPreparationResult prepareToolchains(
            DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime, BuildLimitConfiguration limits)
            throws LinuxOperationException {
        var tools = deploymentBuild.prepare(facts, runtime, limits);
        return new gold.debug.windowstolinux.shared.linux.build.ToolchainPreparationResult(tools, collectDeploymentCapabilities());
    }

    /** Performs the {@code prepareEnvironment} operation. / 执行 {@code prepareEnvironment} 操作。 */
    @Override
    public EnvironmentSetupResult prepareEnvironment(
            EnvironmentSetupApproval approval) throws LinuxOperationException {
        return environment.prepare(approval);
    }

    /** Returns fixed system preparation for this trusted session. / 返回当前可信会话的固定系统准备能力。 */
    @Override public gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer selinuxPreparation() {
        return selinux;
    }

    /** Performs the {@code uploadSource} operation. / 执行 {@code uploadSource} 操作。 */
    @Override
    public SourceUploadResult uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace, long maxWorkspaceBytes)
            throws LinuxOperationException {
        return transfer.upload(archive, workspace, maxWorkspaceBytes);
    }

    /** Performs the {@code buildDeployment} operation. / 执行 {@code buildDeployment} 操作。 */
    @Override
    public DeploymentBuildResult buildDeployment(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                                 RemoteWorkspace workspace, BuildLimitConfiguration limits,
                                                 RemoteBuildEnvironment configuration)
            throws LinuxOperationException {
        return deploymentBuild.build(facts, runtime, workspace, limits, configuration);
    }

    /** Performs the {@code stageDeploymentInputs} operation. / 执行 {@code stageDeploymentInputs} 操作。 */
    @Override
    public RemoteDeploymentInputs stageDeploymentInputs(ManagedApplication application,
                                                         RemoteRuntimeConfiguration configuration,
                                                         List<RemoteSecretPayload> secrets)
            throws LinuxOperationException {
        return deploymentInputs.stage(application, configuration, secrets);
    }

    /** Performs the {@code cleanupCandidate} operation. / 执行 {@code cleanupCandidate} 操作。 */
    @Override
    public RemoteStepResult cleanupCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
        return candidates.cleanup(workspace);
    }

    /** Performs the {@code stageRestoreFiles} operation. / 执行 {@code stageRestoreFiles} 操作。 */
    @Override
    public RemoteRestoreStagingEvidence stageRestoreFiles(RemoteRestoreStagingRequest request)
            throws LinuxOperationException {
        return restoreTransfer.stageRestoreFiles(request);
    }

    /** Performs the {@code discardRestoreFiles} operation. / 执行 {@code discardRestoreFiles} 操作。 */
    @Override
    public RemoteStepResult discardRestoreFiles(RemoteRestoreStagingRequest request) throws LinuxOperationException {
        return restoreTransfer.discardRestoreFiles(request);
    }

    /** Performs the {@code checkHealth} operation. / 执行 {@code checkHealth} 操作。 */
    @Override
    public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck)
            throws LinuxOperationException {
        return systemdHealth.check(application, healthCheck);
    }

    /** Performs the {@code retainRecentSuccessfulReleases} operation. / 执行 {@code retainRecentSuccessfulReleases} 操作。 */
    @Override
    public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application)
            throws LinuxOperationException {
        return runtimes.retain(application);
    }

    /** Performs the {@code observe} operation. / 执行 {@code observe} 操作。 */
    @Override
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        return managedRuntime.observe(application);
    }

    /** Performs the {@code executeLifecycle} operation. / 执行 {@code executeLifecycle} 操作。 */
    @Override
    public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action,
                                                 HealthCheck healthCheck) throws LinuxOperationException {
        return managedRuntime.execute(application, action, healthCheck);
    }

    /** Performs the {@code snapshotDeployment} operation. / 执行 {@code snapshotDeployment} 操作。 */
    @Override
    public ReleaseSnapshot snapshotDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.snapshot(application, container);
        }
        return deploymentProtocol.snapshot(application, runtime);
    }

    /** Performs the {@code publishDeployment} operation. / 执行 {@code publishDeployment} 操作。 */
    @Override
    public RemoteStepResult publishDeployment(ManagedApplication application, DeploymentProjectFacts facts,
                                              RemoteWorkspace workspace,
                                              DeploymentBuildResult buildResult, String releaseIdentity,
                                              DeploymentRuntimeSpecification runtime, RemoteDeploymentInputs inputs,
                                              ManagedContentPublication contentPublication, ReleaseSnapshot snapshot)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.publish(application, workspace, buildResult, releaseIdentity, container, inputs,
                    contentPublication, snapshot);
        }
        return deploymentProtocol.publish(application, facts, workspace, buildResult, releaseIdentity, runtime, inputs,
                contentPublication, snapshot);
    }

    /** Performs the {@code rollbackDeployment} operation. / 执行 {@code rollbackDeployment} 操作。 */
    @Override
    public RemoteStepResult rollbackDeployment(ManagedApplication application, ReleaseSnapshot snapshot,
                                               DeploymentBuildResult buildResult, String releaseIdentity,
                                               DeploymentRuntimeSpecification runtime, RemoteDeploymentInputs inputs)
            throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerProtocol.rollback(application, snapshot, buildResult, releaseIdentity, container, inputs);
        }
        return deploymentProtocol.rollback(application, snapshot, buildResult, releaseIdentity, runtime, inputs);
    }

    /** Performs the {@code checkDeploymentHealth} operation. / 执行 {@code checkDeploymentHealth} 操作。 */
    @Override
    public HealthCheckResult checkDeploymentHealth(ManagedApplication application, DeploymentRuntimeSpecification runtime,
                                                   HealthCheck healthCheck) throws LinuxOperationException {
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            return containerRuntime.checkHealth(application, container, healthCheck);
        }
        return systemdHealth.check(application, healthCheck);
    }

    /** Performs the {@code observeDeployment} operation. / 执行 {@code observeDeployment} 操作。 */
    @Override
    public LifecycleObservation observeDeployment(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException {
        return managedRuntime.observe(application);
    }

    /** Performs the {@code executeDeploymentLifecycle} operation. / 执行 {@code executeDeploymentLifecycle} 操作。 */
    @Override
    public LifecycleObservation executeDeploymentLifecycle(ManagedApplication application,
                                                            DeploymentRuntimeSpecification runtime,
                                                            LifecycleAction action) throws LinuxOperationException {
        return managedRuntime.execute(application, runtime, action);
    }

    /** Returns the connection's database operations. / 返回当前连接的数据库操作能力。 */
    @Override public RemoteDatabasePort databaseOperations() { return databases; }

    /** Returns the connection's backup artifacts. / 返回当前连接的备份制品能力。 */
    @Override public RemoteBackupArtifactPort backupArtifacts() { return backupArtifacts; }

    /** Returns the connection's restore activation. / 返回当前连接的恢复激活能力。 */
    @Override public RemoteRestoreActivationPort restoreActivation() { return restoreActivation; }

    /** Closes this resource. / 关闭此资源。 */
    @Override
    public void close() {
        try {
            SshSessionLifecycleExecutor.closeQuietly(session);
        } finally {
            SshSessionLifecycleExecutor.closeQuietly(client);
        }
    }
}

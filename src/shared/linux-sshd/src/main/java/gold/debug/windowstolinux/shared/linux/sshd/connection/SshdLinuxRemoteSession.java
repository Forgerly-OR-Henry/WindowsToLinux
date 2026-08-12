package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.build.MavenBuildExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.capability.SshdCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.sshd.distro.UbuntuEnvironmentExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.SystemdRuntimeExecutor;
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
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;

/**
 * Unified session facade delegating each typed capability to its implementation package.
 *
 * <p>将各项类型化能力委派给其实现包的统一会话门面。
 */
final class SshdLinuxRemoteSession implements LinuxRemoteSession {
    private final SshClient client;
    private final ClientSession session;
    private final SshdCapabilityCollector capabilities;
    private final UbuntuEnvironmentExecutor environment;
    private final SshdSourceTransfer transfer;
    private final MavenBuildExecutor build;
    private final ManagedReleaseProtocolExecutor protocol;
    private final SystemdRuntimeExecutor runtime;

    SshdLinuxRemoteSession(SshClient client, ClientSession session,
                              SshEndpoint endpoint, String hostFingerprint) {
        this.client = client;
        this.session = session;
        SshCommandExecutor commands = new SshCommandExecutor(session);
        this.capabilities = new SshdCapabilityCollector(commands, hostFingerprint);
        this.environment = new UbuntuEnvironmentExecutor(
                commands, capabilities, endpoint.serverId(), endpoint.username());
        this.protocol = new ManagedReleaseProtocolExecutor(commands);
        this.transfer = new SshdSourceTransfer(session, commands, protocol);
        this.build = new MavenBuildExecutor(commands, endpoint.username());
        this.runtime = new SystemdRuntimeExecutor(commands, protocol, endpoint.username());
    }

    @Override
    public ServerCapabilities collectCapabilities() throws LinuxOperationException {
        return capabilities.collect();
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
        return runtime.checkHealth(application, healthCheck);
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
        return runtime.observe(application);
    }

    @Override
    public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action,
                                                 HealthCheck healthCheck) throws LinuxOperationException {
        return runtime.executeLifecycle(application, action, healthCheck);
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

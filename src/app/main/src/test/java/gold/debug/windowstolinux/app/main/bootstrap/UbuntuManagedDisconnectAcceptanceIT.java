package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.deployment.*;
import gold.debug.windowstolinux.app.service.lifecycle.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.source.*;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live transport failure exercise. Only the test transport wrapper disconnects; all candidate publication and recovery decisions remain in the managed-deployment deployment service.
 *
 * <p>可选实时传输失败演练。只有测试传输包装器会断开连接；全部候选发布和恢复决策仍由受管部署部署服务负责。
 */
@EnabledIfSystemProperty(named = "managed.runtime.disconnect", matches = "true")
class UbuntuManagedDisconnectAcceptanceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reconnectsAndRestoresTheOldReleaseWhenThePostPublishSessionIsLost() throws Exception {
        String v1Property = System.getProperty("managed.disconnect.v1.source");
        String v2Property = System.getProperty("managed.disconnect.v2.source");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("managed.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertTrue(v1Property != null && !v1Property.isBlank(), "managed.disconnect.v1.source is required");
        assertTrue(v2Property != null && !v2Property.isBlank(), "managed.disconnect.v2.source is required");
        assertTrue(host != null && !host.isBlank(), "managed.ssh.host is required");
        assertTrue(username != null && !username.isBlank(), "managed.ssh.user is required");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit managed.root-build=true confirmation");
        assertTrue(password != null && !password.isBlank(), "WINDOWSTOLINUX_TEST_SSH_PASSWORD is required");
        int proofPort = Integer.getInteger("managed.disconnect.port", 18084);
        Path v1 = Path.of(v1Property).toAbsolutePath().normalize();
        Path v2 = Path.of(v2Property).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(v1), "disconnect v1 source directory is required");
        assertTrue(Files.isDirectory(v2), "disconnect v2 source directory is required");

        HealthCheck.Http proofHealth = new HealthCheck.Http(
                URI.create("http://127.0.0.1:" + proofPort + "/disconnect-proof"), 200, 15
        );
        UserAccessUrl userAccessUrl = businessUrl(host, proofPort);
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("desktop-data"))) {
            Path workDirectory = temporaryDirectory.resolve("work");
            DesktopApplicationService service = new DesktopApplicationService(
                    database, workDirectory, new SshdLinuxGateway());
            SourcePreparation firstPreparation = service.prepareSource(v1);
            SourcePreparation candidatePreparation = service.prepareSource(v2);
            assertTrue(firstPreparation.archive().isPresent(), "v1 must pass managed-deployment static analysis");
            assertTrue(candidatePreparation.archive().isPresent(), "v2 must pass managed-deployment static analysis");

            ServerProfile profile = new ServerProfile("ubuntu-managed-disconnect", host, 22, username,
                    "ssh/ubuntu-managed-disconnect/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(firstPreparation.assessment().facts().orElseThrow().usesMavenWrapper(), proofHealth),
                    () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();

            DeploymentRequest firstRequest = request(service, firstPreparation, server, proofHealth, userAccessUrl, rootBuild);
            DeploymentResult first = service.deployResultWithStoredPassword(
                    firstRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), fingerprint -> true);
            assertEquals(DeploymentStatus.SUCCEEDED, first.status(), () -> first.events().toString());
            String firstDigest = first.publishedArtifactSha256().orElseThrow();

            DeploymentRequest candidateRequest = request(service, candidatePreparation, server, proofHealth, userAccessUrl, rootBuild);
            assertEquals(firstRequest.application(), candidateRequest.application(), "candidate must retain the verified ownership identity");
            DisconnectAfterPublishGateway disconnectingGateway = new DisconnectAfterPublishGateway(new SshdLinuxGateway());
            DesktopApplicationService disconnectingService = new DesktopApplicationService(
                    database, workDirectory, disconnectingGateway);
            DeploymentResult candidate = disconnectingService.deployResultWithStoredPassword(
                    candidateRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), fingerprint -> true);

            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, candidate.status(), () -> candidate.events().toString());
            assertEquals(2, disconnectingGateway.connectionCount(), "recovery must establish a new verified SSH session");
            assertEvent(candidate, "remote-build", true);
            assertEvent(candidate, "snapshot", true);
            assertEvent(candidate, "publish", true);
            assertEvent(candidate, "linux-operation", false);
            assertEvent(candidate, "recovery-reconnect", true);
            assertEvent(candidate, "rollback", true);
            assertEvent(candidate, "rollback-health", true);
            assertEvent(candidate, "rollback-observation", true);

            LifecycleActionResult refreshed = service.executeLifecycleResultWithStoredPassword(firstRequest.application(),
                    LifecycleAction.REFRESH_STATUS, proofHealth, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray());
            assertTrue(refreshed.accepted(), refreshed::toString);
            assertEquals(RuntimeState.RUNNING, refreshed.observation().orElseThrow().runtimeState());
            assertEquals(firstDigest, database.findCurrentRelease(firstRequest.application().id()).orElseThrow().artifactSha256(),
                    "session loss must not replace the recorded successful artifact");
        }
    }

    private static DeploymentRequest request(
            DesktopApplicationService service,
            SourcePreparation preparation,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity server,
            HealthCheck health,
            UserAccessUrl userAccessUrl,
            boolean rootBuild
    ) {
        return service.createDeploymentRequest(preparation, server, health, Optional.of(userAccessUrl),
                new BuildLimits(1200, 1024, 4096, 4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
    }

    private static UserAccessUrl businessUrl(String host, int port) {
        return new UserAccessUrl(URI.create("http://" + host + ":" + port + "/"));
    }

    private static void assertEvent(DeploymentResult result, String step, boolean expected) {
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals(step) && event.succeeded() == expected),
                () -> "missing event " + step + "=" + expected + ": " + result.events());
    }

    private static final class DisconnectAfterPublishGateway implements LinuxGateway {
        private final LinuxGateway delegate;
        private int connections;

        private DisconnectAfterPublishGateway(LinuxGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public LinuxRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyVerifier verifier)
                throws LinuxOperationException {
            connections++;
            LinuxRemoteSession session = delegate.connect(endpoint, credential, verifier);
            return connections == 1 ? new DisconnectAfterPublishSession(session) : session;
        }

        private int connectionCount() {
            return connections;
        }
    }

    private static final class DisconnectAfterPublishSession implements LinuxRemoteSession {
        private final LinuxRemoteSession delegate;

        private DisconnectAfterPublishSession(LinuxRemoteSession delegate) {
            this.delegate = delegate;
        }

        @Override
        public ServerCapabilities collectCapabilities() throws LinuxOperationException {
            return delegate.collectCapabilities();
        }

        @Override
        public gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult prepareEnvironment(
                gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationApproval approval
        ) throws LinuxOperationException {
            return delegate.prepareEnvironment(approval);
        }

        @Override
        public UploadReceipt uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace) throws LinuxOperationException {
            return delegate.uploadSource(archive, workspace);
        }

        @Override
        public RemoteBuildResult build(RemoteWorkspace workspace, BuildLimits limits) throws LinuxOperationException {
            return delegate.build(workspace, limits);
        }

        @Override
        public ReleaseSnapshot snapshot(ManagedApplication application) throws LinuxOperationException {
            return delegate.snapshot(application);
        }

        @Override
        public RemoteStepResult publish(
                ManagedApplication application,
                RemoteWorkspace workspace,
                RemoteBuildResult build,
                ReleaseSnapshot snapshot
        ) throws LinuxOperationException {
            RemoteStepResult published = delegate.publish(application, workspace, build, snapshot);
            if (published.succeeded()) {
                delegate.close();
            }
            return published;
        }

        @Override
        public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck) throws LinuxOperationException {
            throw LinuxOperationException.localized(
                    "linux.error.connection",
                    "test-only post-publish SSH transport loss"
            );
        }

        @Override
        public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application) throws LinuxOperationException {
            return delegate.retainRecentSuccessfulReleases(application);
        }

        @Override
        public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot, RemoteBuildResult build)
                throws LinuxOperationException {
            return delegate.rollback(application, snapshot, build);
        }

        @Override
        public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
            return delegate.observe(application);
        }

        @Override
        public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck)
                throws LinuxOperationException {
            return delegate.executeLifecycle(application, action, healthCheck);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}

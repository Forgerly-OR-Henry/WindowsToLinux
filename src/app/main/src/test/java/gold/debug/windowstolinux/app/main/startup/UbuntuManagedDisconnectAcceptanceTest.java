package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.*;
import gold.debug.windowstolinux.app.service.execution.lifecycle.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.source.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.LifecycleActionResult;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
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
 * Opt-in live transport failure exercise. Only the test transport wrapper disconnects; all candidate publication and recovery decisions remain in the managed deployment service.
 *
 * <p>可选实时传输失败演练。只有测试传输包装器会断开连接；全部候选发布和恢复决策仍由受管部署服务负责。
 */
@EnabledIfSystemProperty(named = "managed.runtime.disconnect", matches = "true")
class UbuntuManagedDisconnectAcceptanceTest {
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
        int proofPort = Integer.getInteger("managed.disconnect.port", 19096);
        Path v1 = Path.of(v1Property).toAbsolutePath().normalize();
        Path v2 = Path.of(v2Property).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(v1), "disconnect v1 source directory is required");
        assertTrue(Files.isDirectory(v2), "disconnect v2 source directory is required");

        HealthCheck.Http proofHealth = new HealthCheck.Http(
                URI.create("http://127.0.0.1:" + proofPort + "/disconnect-proof"), 200, 15
        );
        UserAccessUrl userAccessUrl = businessUrl(host, proofPort);
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            Path workDirectory = temporaryDirectory.resolve("work");
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, workDirectory, new SshdLinuxGateway());
            ReviewedSourcePreparation firstPreparation = ReviewedMavenAcceptanceFixture.prepare(service, v1);
            ReviewedSourcePreparation candidatePreparation = ReviewedMavenAcceptanceFixture.prepare(service, v2);
            assertTrue(firstPreparation.archive().isPresent(), "v1 must pass managed-deployment static analysis");
            assertTrue(candidatePreparation.archive().isPresent(), "v2 must pass managed-deployment static analysis");

            ServerProfile profile = new ServerProfile("ubuntu-managed-disconnect", host, 22, username,
                    "ssh/ubuntu-managed-disconnect/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(
                    firstPreparation.assessment().facts().orElseThrow().buildTool()
                            == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.MAVEN_WRAPPER,
                    proofHealth),
                    () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();

            ReviewedDeploymentRequest firstRequest = request(service, firstPreparation, server, proofHealth, userAccessUrl, rootBuild);
            DeploymentResult first = service.deployReviewedWithStoredPassword(
                    firstRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), fingerprint -> true).result();
            assertEquals(DeploymentStatus.SUCCEEDED, first.status(), () -> first.events().toString());
            String firstDigest = first.publishedReleaseSha256().orElseThrow();

            ReviewedDeploymentRequest candidateRequest = request(service, candidatePreparation, server, proofHealth, userAccessUrl, rootBuild);
            assertEquals(firstRequest.facts().applicationId(), candidateRequest.facts().applicationId(),
                    "candidate must retain the verified ownership identity");
            DisconnectAfterPublishGateway disconnectingGateway = new DisconnectAfterPublishGateway(new SshdLinuxGateway());
            DesktopApplicationFacade disconnectingService = new DesktopApplicationFacade(
                    database, workDirectory, disconnectingGateway);
            DeploymentResult candidate = disconnectingService.deployReviewedWithStoredPassword(
                    candidateRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-disconnect-master".toCharArray(), fingerprint -> true).result();

            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, candidate.status(), () -> candidate.events().toString());
            assertEquals(2, disconnectingGateway.connectionCount(), "recovery must establish a new verified SSH session");
            assertEvent(candidate, "remote-build", true);
            assertEvent(candidate, "snapshot", true);
            assertEvent(candidate, "publish", true);
            assertEvent(candidate, "linux-operation", false);
            assertEvent(candidate, "recovery-reconnect", true);
            assertEvent(candidate, "rollback", true);
            assertEvent(candidate, "rollback-observation", true);

            LifecycleActionResult refreshed = service.executePersistedLifecycleResultWithStoredPassword(
                    firstRequest.facts().applicationId(), LifecycleAction.REFRESH_STATUS,
                    "managed-disconnect-master".toCharArray());
            assertTrue(refreshed.accepted(), refreshed::toString);
            assertEquals(RuntimeState.RUNNING, refreshed.observation().orElseThrow().runtimeState());
            assertEquals(firstDigest, database.managedApplications().findRelease(
                            firstRequest.facts().applicationId()).orElseThrow().releaseSha256(),
                    "session loss must not replace the recorded successful artifact");
        }
    }

    private static ReviewedDeploymentRequest request(
            DesktopApplicationFacade service,
            ReviewedSourcePreparation preparation,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity server,
            HealthCheck health,
            UserAccessUrl userAccessUrl,
            boolean rootBuild
    ) throws Exception {
        return ReviewedMavenAcceptanceFixture.request(service, preparation, server, health,
                Optional.of(userAccessUrl),
                new BuildLimitConfiguration(1200, 1024, 4096, 4L * 1024 * 1024,
                        2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
    }

    private static UserAccessUrl businessUrl(String host, int port) {
        return new UserAccessUrl(URI.create("http://" + host + ":" + port + "/"));
    }

    private static void assertEvent(DeploymentResult result, String step, boolean expected) {
        assertTrue(result.events().stream().anyMatch(event -> event.step().code().equals(step) && event.succeeded() == expected),
                () -> "missing event " + step + "=" + expected + ": " + result.events());
    }

    private static final class DisconnectAfterPublishGateway implements DeploymentLinuxGateway {
        private final DeploymentLinuxGateway delegate;
        private int connections;

        private DisconnectAfterPublishGateway(DeploymentLinuxGateway delegate) {
            this.delegate = delegate;
        }

        /** Performs the {@code connect} operation. / 执行 {@code connect} 操作。 */
        @Override
        public DeploymentRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator verifier)
                throws LinuxOperationException {
            connections++;
            DeploymentRemoteSession session = delegate.connect(endpoint, credential, verifier);
            return connections == 1 ? disconnectAfterPublish(session) : session;
        }

        private DeploymentRemoteSession disconnectAfterPublish(DeploymentRemoteSession session) {
            java.util.concurrent.atomic.AtomicBoolean published = new java.util.concurrent.atomic.AtomicBoolean();
            return (DeploymentRemoteSession) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{DeploymentRemoteSession.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("checkDeploymentHealth") && published.get()) {
                            throw LinuxOperationException.create(
                                    LinuxOperationFailureType.CONNECTION_FAILED,
                                    "test-only post-publish SSH transport loss");
                        }
                        try {
                            Object result = method.invoke(session, arguments);
                            if (method.getName().equals("publishDeployment")
                                    && result instanceof RemoteStepResult step && step.succeeded()) {
                                published.set(true);
                                session.close();
                            }
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        private int connectionCount() {
            return connections;
        }
    }

}

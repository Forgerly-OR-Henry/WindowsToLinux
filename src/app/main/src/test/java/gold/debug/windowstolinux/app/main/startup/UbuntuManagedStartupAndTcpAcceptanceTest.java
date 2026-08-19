package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.*;
import gold.debug.windowstolinux.app.service.lifecycle.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.source.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.lifecycle.LifecycleActionResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
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
 * Opt-in live acceptance for startup failure and TCP ownership-aware health checks.
 *
 * <p>针对启动失败和感知资源归属的 TCP 健康检查的可选实时验收。
 */
@EnabledIfSystemProperty(named = "managed.runtime.startup-tcp", matches = "true")
class UbuntuManagedStartupAndTcpAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rollsBackStartupAndTcpFailuresAndAcceptsTheTcpStabilityPath() throws Exception {
        String baselineProperty = System.getProperty("managed.startup-tcp.v1.source");
        String startupFailureProperty = System.getProperty("managed.startup-tcp.startup.source");
        String wrongPortProperty = System.getProperty("managed.startup-tcp.wrong-port.source");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("managed.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertPresent(baselineProperty, "managed.startup-tcp.v1.source");
        assertPresent(startupFailureProperty, "managed.startup-tcp.startup.source");
        assertPresent(wrongPortProperty, "managed.startup-tcp.wrong-port.source");
        assertPresent(host, "managed.ssh.host");
        assertPresent(username, "managed.ssh.user");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit managed.root-build=true confirmation");
        assertPresent(password, "WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        Path baseline = source(baselineProperty, "baseline source");
        Path startupFailure = source(startupFailureProperty, "startup-failure source");
        Path wrongPort = source(wrongPortProperty, "wrong-port source");

        HealthCheck.Http httpHealth = new HealthCheck.Http(URI.create("http://127.0.0.1:18085/health"), 200, 15);
        HealthCheck.Tcp tcpHealth = new HealthCheck.Tcp(18085, 15, 1);
        UserAccessUrl userAccessUrl = new UserAccessUrl(URI.create("http://" + host + ":18085/"));
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            ReviewedSourcePreparation baselinePreparation = ReviewedMavenAcceptanceFixture.prepare(service, baseline);
            ReviewedSourcePreparation startupPreparation = ReviewedMavenAcceptanceFixture.prepare(service, startupFailure);
            ReviewedSourcePreparation wrongPortPreparation = ReviewedMavenAcceptanceFixture.prepare(service, wrongPort);
            assertTrue(baselinePreparation.archive().isPresent(), "baseline must pass static analysis");
            assertTrue(startupPreparation.archive().isPresent(), "startup candidate must pass static analysis");
            assertTrue(wrongPortPreparation.archive().isPresent(), "TCP candidate must pass static analysis");
            String applicationId = baselinePreparation.assessment().facts().orElseThrow().applicationId();
            assertEquals(applicationId, startupPreparation.assessment().facts().orElseThrow().applicationId());
            assertEquals(applicationId, wrongPortPreparation.assessment().facts().orElseThrow().applicationId());

            ServerProfile profile = new ServerProfile("ubuntu-managed-startup-tcp", host, 22, username,
                    "ssh/ubuntu-managed-startup-tcp/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-startup-tcp-master".toCharArray(), password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-startup-tcp-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(
                    baselinePreparation.assessment().facts().orElseThrow().buildTool()
                            == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.MAVEN_WRAPPER,
                    httpHealth),
                    () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();

            ReviewedDeploymentRequest baselineRequest = request(service, baselinePreparation, server, httpHealth, Optional.of(userAccessUrl), rootBuild);
            DeploymentResult baselineResult = deploy(service, baselineRequest, profile);
            assertEquals(DeploymentStatus.SUCCEEDED, baselineResult.status(), () -> baselineResult.events().toString());

            LifecycleActionResult tcpRestart = service.executePersistedLifecycleResultWithStoredPassword(
                    baselineRequest.facts().applicationId(), LifecycleAction.RESTART,
                    "managed-startup-tcp-master".toCharArray());
            assertTrue(tcpRestart.accepted(), tcpRestart::toString);
            assertEquals(RuntimeState.RUNNING, tcpRestart.observation().orElseThrow().runtimeState());

            ReviewedDeploymentRequest startupRequest = request(service, startupPreparation, server, httpHealth, Optional.of(userAccessUrl), rootBuild);
            assertEquals(baselineRequest.facts().applicationId(), startupRequest.facts().applicationId());
            DeploymentResult startupResult = deploy(service, startupRequest, profile);
            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, startupResult.status(), () -> startupResult.events().toString());
            assertEvent(startupResult, "remote-build", true);
            assertTrue(startupResult.events().stream().anyMatch(event ->
                            (event.step().equals("publish") || event.step().equals("candidate-health")) && !event.succeeded()),
                    () -> "startup failure must fail publication or candidate health: " + startupResult.events());
            assertEvent(startupResult, "rollback", true);

            ReviewedDeploymentRequest wrongPortRequest = request(service, wrongPortPreparation, server, tcpHealth, Optional.empty(), rootBuild);
            assertEquals(baselineRequest.facts().applicationId(), wrongPortRequest.facts().applicationId());
            DeploymentResult tcpFailure = deploy(service, wrongPortRequest, profile);
            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, tcpFailure.status(), () -> tcpFailure.events().toString());
            assertEvent(tcpFailure, "publish", true);
            assertEvent(tcpFailure, "candidate-health", false);
            assertEvent(tcpFailure, "rollback", true);

            LifecycleActionResult refreshed = service.executePersistedLifecycleResultWithStoredPassword(
                    baselineRequest.facts().applicationId(), LifecycleAction.REFRESH_STATUS,
                    "managed-startup-tcp-master".toCharArray());
            assertTrue(refreshed.accepted(), refreshed::toString);
            assertEquals(RuntimeState.RUNNING, refreshed.observation().orElseThrow().runtimeState());
        }
    }

    private static DeploymentResult deploy(DesktopApplicationFacade service, ReviewedDeploymentRequest request, ServerProfile profile)
            throws Exception {
        return service.deployReviewedWithStoredPassword(request, profile, CredentialStorageMode.MASTER_PASSWORD,
                "managed-startup-tcp-master".toCharArray(), fingerprint -> true).result();
    }

    private static ReviewedDeploymentRequest request(
            DesktopApplicationFacade service,
            ReviewedSourcePreparation preparation,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity server,
            HealthCheck health,
            Optional<UserAccessUrl> userAccessUrl,
            boolean rootBuild
    ) throws Exception {
        return ReviewedMavenAcceptanceFixture.request(service, preparation, server, health, userAccessUrl,
                new BuildLimitConfiguration(1200, 1024, 4096, 4L * 1024 * 1024,
                        2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
    }

    private static Path source(String value, String name) {
        Path source = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), name + " directory is required");
        return source;
    }

    private static void assertPresent(String value, String name) {
        assertTrue(value != null && !value.isBlank(), name + " is required");
    }

    private static void assertEvent(DeploymentResult result, String step, boolean expected) {
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals(step) && event.succeeded() == expected),
                () -> "missing event " + step + "=" + expected + ": " + result.events());
    }
}

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
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live update test proving that an unhealthy candidate restores its old version.
 *
 * <p>用于证明不健康候选版本会恢复旧版本的可选实时更新测试。
 */
@EnabledIfSystemProperty(named = "managed.runtime.rollback", matches = "true")
class UbuntuManagedRollbackAcceptanceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresTheLiveVersionAfterAnUnhealthyShortDowntimeUpdate() throws Exception {
        String v1Property = System.getProperty("managed.rollback.v1.source");
        String v2Property = System.getProperty("managed.rollback.v2.source");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("managed.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertTrue(v1Property != null && !v1Property.isBlank(), "managed.rollback.v1.source is required");
        assertTrue(v2Property != null && !v2Property.isBlank(), "managed.rollback.v2.source is required");
        assertTrue(host != null && !host.isBlank(), "managed.ssh.host is required");
        assertTrue(username != null && !username.isBlank(), "managed.ssh.user is required");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit managed.root-build=true confirmation");
        assertTrue(password != null && !password.isBlank(), "WINDOWSTOLINUX_TEST_SSH_PASSWORD is required");
        int proofPort = Integer.getInteger("managed.rollback.port", 18083);
        Path v1 = Path.of(v1Property).toAbsolutePath().normalize();
        Path v2 = Path.of(v2Property).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(v1), "rollback v1 source directory is required");
        assertTrue(Files.isDirectory(v2), "rollback v2 source directory is required");

        HealthCheck.Http proofHealth = new HealthCheck.Http(
                URI.create("http://127.0.0.1:" + proofPort + "/rollback-proof"), 200, 15
        );
        UserAccessUrl userAccessUrl = businessUrl(host, proofPort);
        char[] masterPassword = "managed-rollback-master".toCharArray();
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            SourcePreparation firstPreparation = service.prepareSource(v1);
            SourcePreparation candidatePreparation = service.prepareSource(v2);
            assertTrue(firstPreparation.archive().isPresent(), "v1 must pass managed-deployment static analysis");
            assertTrue(candidatePreparation.archive().isPresent(), "v2 must pass managed-deployment static analysis");
            assertEquals(firstPreparation.assessment().facts().orElseThrow().applicationName(),
                    candidatePreparation.assessment().facts().orElseThrow().applicationName(),
                    "both revisions must update the same managed application identity");

            ServerProfile profile = new ServerProfile("ubuntu-managed-rollback", host, 22, username,
                    "ssh/ubuntu-managed-rollback/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD, masterPassword, password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-rollback-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(firstPreparation.assessment().facts().orElseThrow().usesMavenWrapper(), proofHealth),
                    () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();

            DeploymentRequest firstRequest = request(service, firstPreparation, server, proofHealth, userAccessUrl, rootBuild);
            DeploymentResult first = service.deployResultWithStoredPassword(
                    firstRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-rollback-master".toCharArray(), fingerprint -> true);
            assertEquals(DeploymentStatus.SUCCEEDED, first.status(), () -> first.events().toString());
            String firstDigest = first.publishedArtifactSha256().orElseThrow();

            DeploymentRequest candidateRequest = request(service, candidatePreparation, server, proofHealth, userAccessUrl, rootBuild);
            assertEquals(firstRequest.application(), candidateRequest.application(),
                    "repeat deployment must retain the locally verified ownership identity");
            DeploymentResult candidate = service.deployResultWithStoredPassword(
                    candidateRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-rollback-master".toCharArray(), fingerprint -> true);
            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, candidate.status(), () -> candidate.events().toString());
            assertEvent(candidate, "remote-build", true);
            assertEvent(candidate, "snapshot", true);
            assertEvent(candidate, "publish", true);
            assertEvent(candidate, "candidate-health", false);
            assertEvent(candidate, "rollback", true);
            assertEvent(candidate, "rollback-health", true);
            assertEvent(candidate, "rollback-observation", true);

            LifecycleActionResult refreshed = service.executeLifecycleResultWithStoredPassword(firstRequest.application(),
                    LifecycleAction.REFRESH_STATUS, proofHealth, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-rollback-master".toCharArray());
            assertTrue(refreshed.accepted(), refreshed::toString);
            assertEquals(RuntimeState.RUNNING, refreshed.observation().orElseThrow().runtimeState());
            assertEquals(firstDigest, database.findCurrentRelease(firstRequest.application().id()).orElseThrow().artifactSha256(),
                    "a failed candidate must not replace the locally recorded successful artifact");
            assertNotEquals(firstPreparation.archive().orElseThrow().contentSha256(),
                    candidatePreparation.archive().orElseThrow().contentSha256(),
                    "fixture revisions must differ before they are sent to the target");
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
}

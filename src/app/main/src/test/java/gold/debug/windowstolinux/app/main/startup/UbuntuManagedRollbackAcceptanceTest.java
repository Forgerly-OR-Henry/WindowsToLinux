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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Opt-in live update test proving that an unhealthy candidate restores its old version.
 *
 * <p>用于证明不健康候选版本会恢复旧版本的可选实时更新测试。
 */
@EnabledIfSystemProperty(named = "managed.runtime.rollback", matches = "true")
class UbuntuManagedRollbackAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresTheLiveVersionAfterAnUnhealthyShortDowntimeUpdate() throws Exception {
        String v1Property = System.getProperty("managed.rollback.v1.source");
        String v2Property = System.getProperty("managed.rollback.v2.source");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "root");
        boolean rootBuild = false;
        assertFalse(Boolean.getBoolean("managed.root-build"), "root builds were removed in helper v7");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertTrue(v1Property != null && !v1Property.isBlank(), "managed.rollback.v1.source is required");
        assertTrue(v2Property != null && !v2Property.isBlank(), "managed.rollback.v2.source is required");
        assertTrue(host != null && !host.isBlank(), "managed.ssh.host is required");
        assertTrue(username != null && !username.isBlank(), "managed.ssh.user is required");
        assertEquals("root", username, "root management must launch only restricted builds");
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
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            ReviewedSourcePreparation firstPreparation = ReviewedMavenAcceptanceFixture.prepare(service, v1);
            ReviewedSourcePreparation candidatePreparation = ReviewedMavenAcceptanceFixture.prepare(service, v2);
            assertTrue(firstPreparation.archive().isPresent(), "v1 must pass managed-deployment static analysis");
            assertTrue(candidatePreparation.archive().isPresent(), "v2 must pass managed-deployment static analysis");
            assertEquals(firstPreparation.assessment().facts().orElseThrow().applicationId(),
                    candidatePreparation.assessment().facts().orElseThrow().applicationId(),
                    "both revisions must update the same managed application identity");

            ServerProfile profile = new ServerProfile("ubuntu-managed-rollback", host, 22, username,
                    "ssh/ubuntu-managed-rollback/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD, masterPassword, password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-rollback-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(
                    firstPreparation.assessment().facts().orElseThrow().buildTool()
                            == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.MAVEN_WRAPPER,
                    proofHealth),
                    () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();

            ReviewedDeploymentRequest firstRequest = request(service, firstPreparation, server, proofHealth, userAccessUrl, rootBuild);
            DeploymentResult first = service.deployReviewedWithStoredPassword(
                    firstRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-rollback-master".toCharArray(), fingerprint -> true).result();
            assertEquals(DeploymentStatus.SUCCEEDED, first.status(), () -> first.events().toString());
            String firstDigest = first.publishedReleaseSha256().orElseThrow();

            ReviewedDeploymentRequest candidateRequest = request(service, candidatePreparation, server, proofHealth, userAccessUrl, rootBuild);
            assertEquals(firstRequest.facts().applicationId(), candidateRequest.facts().applicationId(),
                    "repeat deployment must retain the locally verified ownership identity");
            DeploymentResult candidate = service.deployReviewedWithStoredPassword(
                    candidateRequest, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-rollback-master".toCharArray(), fingerprint -> true).result();
            assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, candidate.status(), () -> candidate.events().toString());
            assertEvent(candidate, "remote-build", true);
            assertEvent(candidate, "snapshot", true);
            assertEvent(candidate, "publish", true);
            assertEvent(candidate, "candidate-health", false);
            assertEvent(candidate, "rollback", true);
            assertEvent(candidate, "rollback-observation", true);

            LifecycleActionResult refreshed = service.executePersistedLifecycleResultWithStoredPassword(
                    firstRequest.facts().applicationId(), LifecycleAction.REFRESH_STATUS,
                    "managed-rollback-master".toCharArray());
            assertTrue(refreshed.accepted(), refreshed::toString);
            assertEquals(RuntimeState.RUNNING, refreshed.observation().orElseThrow().runtimeState());
            assertEquals(firstDigest, database.managedApplications().findRelease(
                            firstRequest.facts().applicationId()).orElseThrow().releaseSha256(),
                    "a failed candidate must not replace the locally recorded successful artifact");
            assertNotEquals(firstPreparation.archive().orElseThrow().contentSha256(),
                    candidatePreparation.archive().orElseThrow().contentSha256(),
                    "fixture revisions must differ before they are sent to the target");
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
}

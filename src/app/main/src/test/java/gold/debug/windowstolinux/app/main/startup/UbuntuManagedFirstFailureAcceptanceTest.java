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
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live first-deployment failure exercise. The program, rather than the test operator, performs the target build, candidate publish and rollback.
 *
 * <p>可选实时首次部署失败演练。由程序而非测试操作员执行目标机构建、候选发布和回滚。
 */
@EnabledIfSystemProperty(named = "managed.runtime.first-failure", matches = "true")
class UbuntuManagedFirstFailureAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cleansTheFirstCandidateWhenItsPublishedHttpHealthCheckFails() throws Exception {
        String sourceProperty = System.getProperty("managed.first-failure.source");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("managed.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertTrue(sourceProperty != null && !sourceProperty.isBlank(), "managed.first-failure.source is required");
        assertTrue(host != null && !host.isBlank(), "managed.ssh.host is required");
        assertTrue(username != null && !username.isBlank(), "managed.ssh.user is required");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit managed.root-build=true confirmation");
        assertTrue(password != null && !password.isBlank(), "WINDOWSTOLINUX_TEST_SSH_PASSWORD is required");
        Path source = Path.of(sourceProperty).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), "first-failure source directory is required");

        HealthCheck.Http deliberatelyMissingEndpoint = new HealthCheck.Http(
                URI.create("http://127.0.0.1:18082/health-missing"), 200, 10
        );
        char[] masterPassword = "managed-first-failure-master".toCharArray();
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            ReviewedSourcePreparation preparation = ReviewedMavenAcceptanceFixture.prepare(service, source);
            assertTrue(preparation.archive().isPresent(), "fixture must pass managed-deployment static analysis");

            ServerProfile profile = new ServerProfile("ubuntu-managed-first-failure", host, 22, username,
                    "ssh/ubuntu-managed-first-failure/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD, masterPassword, password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-first-failure-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(
                    preparation.assessment().facts().orElseThrow().buildTool()
                            == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.MAVEN_WRAPPER,
                    deliberatelyMissingEndpoint), () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);

            var server = service.findTrustedServer(profile.id()).orElseThrow();
            ReviewedDeploymentRequest request = ReviewedMavenAcceptanceFixture.request(service, preparation, server,
                    deliberatelyMissingEndpoint,
                    Optional.of(new UserAccessUrl(URI.create("http://" + host + ":18082/"))),
                    new BuildLimitConfiguration(1200, 1024, 4096, 4L * 1024 * 1024,
                            2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
            DeploymentResult result = service.deployReviewedWithStoredPassword(
                    request, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-first-failure-master".toCharArray(), fingerprint -> true).result();

            assertEquals(DeploymentStatus.FAILED_FIRST_DEPLOYMENT, result.status(), () -> result.events().toString());
            assertEvent(result, "source-upload", true);
            assertEvent(result, "remote-build", true);
            assertEvent(result, "snapshot", true);
            assertEvent(result, "publish", true);
            assertEvent(result, "candidate-health", false);
            assertEvent(result, "rollback", true);
            assertFalse(service.listManagedApplications().stream()
                    .anyMatch(application -> application.id().equals(request.facts().applicationId())),
                    "failed first deployment must not become a locally managed application");
        }
    }

    private static void assertEvent(DeploymentResult result, String step, boolean expected) {
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals(step) && event.succeeded() == expected),
                () -> "missing event " + step + "=" + expected + ": " + result.events());
    }
}

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
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdPhaseOneLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
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
@EnabledIfSystemProperty(named = "phase1.runtime.first-failure", matches = "true")
class PhaseOneUbuntuFirstFailureAcceptanceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cleansTheFirstCandidateWhenItsPublishedHttpHealthCheckFails() throws Exception {
        String sourceProperty = System.getProperty("phase1.first-failure.source");
        String host = System.getProperty("phase1.ssh.host");
        String username = System.getProperty("phase1.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("phase1.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertTrue(sourceProperty != null && !sourceProperty.isBlank(), "phase1.first-failure.source is required");
        assertTrue(host != null && !host.isBlank(), "phase1.ssh.host is required");
        assertTrue(username != null && !username.isBlank(), "phase1.ssh.user is required");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit phase1.root-build=true confirmation");
        assertTrue(password != null && !password.isBlank(), "WINDOWSTOLINUX_TEST_SSH_PASSWORD is required");
        Path source = Path.of(sourceProperty).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), "first-failure source directory is required");

        HealthCheck.Http deliberatelyMissingEndpoint = new HealthCheck.Http(
                URI.create("http://127.0.0.1:18082/health-missing"), 200, 10
        );
        char[] masterPassword = "phase1-first-failure-master".toCharArray();
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), new SshdPhaseOneLinuxGateway());
            SourcePreparation preparation = service.prepareSource(source);
            assertTrue(preparation.archive().isPresent(), "fixture must pass phase-one static analysis");

            ServerProfile profile = new ServerProfile("ubuntu-phase1-first-failure", host, 22, username,
                    "ssh/ubuntu-phase1-first-failure/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD, masterPassword, password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-first-failure-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsPhaseOne(preparation.assessment().facts().orElseThrow().usesMavenWrapper(),
                    deliberatelyMissingEndpoint), () -> "Ubuntu target must meet phase-one preconditions: " + capabilities);

            var server = service.findTrustedServer(profile.id()).orElseThrow();
            DeploymentRequest request = service.createDeploymentRequest(preparation, server, deliberatelyMissingEndpoint,
                    Optional.of(new UserAccessUrl(URI.create("http://" + host + ":18082/"))),
                    new BuildLimits(1200, 1024, 4096, 4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
            DeploymentResult result = service.deployResultWithStoredPassword(
                    request, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-first-failure-master".toCharArray(), fingerprint -> true);

            assertEquals(DeploymentStatus.FAILED_FIRST_DEPLOYMENT, result.status(), () -> result.events().toString());
            assertEvent(result, "source-upload", true);
            assertEvent(result, "remote-build", true);
            assertEvent(result, "snapshot", true);
            assertEvent(result, "publish", true);
            assertEvent(result, "candidate-health", false);
            assertEvent(result, "rollback", true);
            assertFalse(service.listManagedApplications().stream()
                    .anyMatch(application -> application.id().equals(request.application().id())),
                    "failed first deployment must not become a locally managed application");

            LifecycleActionResult refresh = service.executeLifecycleResultWithStoredPassword(request.application(),
                    LifecycleAction.REFRESH_STATUS, deliberatelyMissingEndpoint, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-first-failure-master".toCharArray());
            assertFalse(refresh.accepted(), () -> "first-failure cleanup left a managed resource: " + refresh);
            assertTrue(refresh.observation().isPresent(), "cleanup verification must return the live unverified observation");
            assertFalse(refresh.observation().orElseThrow().ownershipVerified(),
                    "first-failure cleanup must remove the candidate ownership evidence");
        }
    }

    private static void assertEvent(DeploymentResult result, String step, boolean expected) {
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals(step) && event.succeeded() == expected),
                () -> "missing event " + step + "=" + expected + ": " + result.events());
    }
}

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live verification of build failure and the bounded build-output path.
 *
 * <p>针对构建失败和有界构建输出路径的可选实时验证。
 */
@EnabledIfSystemProperty(named = "managed.runtime.build-limits", matches = "true")
class UbuntuManagedBuildLimitAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesTheOldReleaseForCompilerAndOutputLimitFailures() throws Exception {
        String baselineProperty = System.getProperty("managed.build-limits.v1.source");
        String failingProperty = System.getProperty("managed.build-limits.failure.source");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("managed.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertPresent(baselineProperty, "managed.build-limits.v1.source");
        assertPresent(failingProperty, "managed.build-limits.failure.source");
        assertPresent(host, "managed.ssh.host");
        assertPresent(username, "managed.ssh.user");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit managed.root-build=true confirmation");
        assertPresent(password, "WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        Path baseline = source(baselineProperty, "baseline source");
        Path failing = source(failingProperty, "failing source");

        int proofPort = Integer.getInteger("managed.build-limits.port", 19094);
        HealthCheck.Http health = new HealthCheck.Http(URI.create("http://127.0.0.1:" + proofPort + "/health"), 200, 30);
        UserAccessUrl userAccessUrl = businessUrl(host, proofPort);
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            ReviewedSourcePreparation baselinePreparation = ReviewedMavenAcceptanceFixture.prepare(service, baseline);
            ReviewedSourcePreparation failingPreparation = ReviewedMavenAcceptanceFixture.prepare(service, failing);
            assertTrue(baselinePreparation.archive().isPresent(), "baseline must pass static analysis");
            assertTrue(failingPreparation.archive().isPresent(), "failing source must reach remote compilation");
            assertEquals(baselinePreparation.assessment().facts().orElseThrow().applicationId(),
                    failingPreparation.assessment().facts().orElseThrow().applicationId());

            ServerProfile profile = new ServerProfile("ubuntu-managed-build-limits", host, 22, username,
                    "ssh/ubuntu-managed-build-limits/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-build-limits-master".toCharArray(), password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-build-limits-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(
                    baselinePreparation.assessment().facts().orElseThrow().buildTool()
                            == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.MAVEN_WRAPPER,
                    health),
                    () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();

            ReviewedDeploymentRequest baselineRequest = request(service, baselinePreparation, server, health, userAccessUrl,
                    normalLimits(rootBuild), rootBuild);
            DeploymentResult baselineResult = deploy(service, baselineRequest, profile);
            assertEquals(DeploymentStatus.SUCCEEDED, baselineResult.status(), () -> baselineResult.events().toString());

            ReviewedDeploymentRequest compilerFailureRequest = request(service, failingPreparation, server, health, userAccessUrl,
                    normalLimits(rootBuild), rootBuild);
            assertEquals(baselineRequest.facts().applicationId(), compilerFailureRequest.facts().applicationId());
            DeploymentResult compilerFailure = deploy(service, compilerFailureRequest, profile);
            assertEquals(DeploymentStatus.FAILED_BUILD, compilerFailure.status(), () -> compilerFailure.events().toString());
            assertEvent(compilerFailure, "source-upload", true);
            assertEvent(compilerFailure, "remote-build", false);
            assertFalse(hasEvent(compilerFailure, "snapshot"), "build failure must not begin a release transaction");
            assertFalse(hasEvent(compilerFailure, "publish"), "build failure must not publish a candidate");
            assertBaselineStillRuns(service, baselineRequest, health, profile);

            ReviewedDeploymentRequest outputLimitRequest = request(service, failingPreparation, server, health, userAccessUrl,
                    new BuildLimitConfiguration(1200, 1024, 4096, 4096, 2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
            DeploymentResult outputLimitFailure = deploy(service, outputLimitRequest, profile);
            assertEquals(DeploymentStatus.FAILED_BUILD, outputLimitFailure.status(), () -> outputLimitFailure.events().toString());
            assertEvent(outputLimitFailure, "remote-build", false);
            assertTrue(outputLimitFailure.events().stream().anyMatch(event -> event.step().equals("remote-build")
                            && event.evidence().contains("Target build output exceeded the confirmed output limit")),
                    () -> "bounded output must be reported as a limit failure: " + outputLimitFailure.events());
            assertFalse(hasEvent(outputLimitFailure, "snapshot"), "output limit must stop before publication");
            assertBaselineStillRuns(service, baselineRequest, health, profile);
        }
    }

    private static BuildLimitConfiguration normalLimits(boolean rootBuild) {
        return new BuildLimitConfiguration(1200, 1024, 4096, 4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, rootBuild);
    }

    private static ReviewedDeploymentRequest request(
            DesktopApplicationFacade service,
            ReviewedSourcePreparation preparation,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity server,
            HealthCheck health,
            UserAccessUrl userAccessUrl,
            BuildLimitConfiguration limits,
            boolean rootBuild
    ) throws Exception {
        return ReviewedMavenAcceptanceFixture.request(service, preparation, server, health,
                Optional.of(userAccessUrl), limits, rootBuild);
    }

    private static UserAccessUrl businessUrl(String host, int port) {
        return new UserAccessUrl(URI.create("http://" + host + ":" + port + "/"));
    }

    private static DeploymentResult deploy(DesktopApplicationFacade service, ReviewedDeploymentRequest request, ServerProfile profile)
            throws Exception {
        return service.deployReviewedWithStoredPassword(request, profile, CredentialStorageMode.MASTER_PASSWORD,
                "managed-build-limits-master".toCharArray(), fingerprint -> true).result();
    }

    private static void assertBaselineStillRuns(
            DesktopApplicationFacade service,
            ReviewedDeploymentRequest baseline,
            HealthCheck health,
            ServerProfile profile
    ) throws Exception {
        LifecycleActionResult refresh = service.executePersistedLifecycleResultWithStoredPassword(
                baseline.facts().applicationId(), LifecycleAction.REFRESH_STATUS,
                "managed-build-limits-master".toCharArray());
        assertTrue(refresh.accepted(), refresh::toString);
        assertEquals(RuntimeState.RUNNING, refresh.observation().orElseThrow().runtimeState());
    }

    private static boolean hasEvent(DeploymentResult result, String step) {
        return result.events().stream().anyMatch(event -> event.step().equals(step));
    }

    private static void assertEvent(DeploymentResult result, String step, boolean expected) {
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals(step) && event.succeeded() == expected),
                () -> "missing event " + step + "=" + expected + ": " + result.events());
    }

    private static Path source(String value, String name) {
        Path source = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), name + " directory is required");
        return source;
    }

    private static void assertPresent(String value, String name) {
        assertTrue(value != null && !value.isBlank(), name + " is required");
    }
}

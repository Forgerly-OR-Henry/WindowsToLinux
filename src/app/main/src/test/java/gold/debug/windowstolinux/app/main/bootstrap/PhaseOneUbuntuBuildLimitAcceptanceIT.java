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
@EnabledIfSystemProperty(named = "phase1.runtime.build-limits", matches = "true")
class PhaseOneUbuntuBuildLimitAcceptanceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesTheOldReleaseForCompilerAndOutputLimitFailures() throws Exception {
        String baselineProperty = System.getProperty("phase1.build-limits.v1.source");
        String failingProperty = System.getProperty("phase1.build-limits.failure.source");
        String host = System.getProperty("phase1.ssh.host");
        String username = System.getProperty("phase1.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("phase1.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertPresent(baselineProperty, "phase1.build-limits.v1.source");
        assertPresent(failingProperty, "phase1.build-limits.failure.source");
        assertPresent(host, "phase1.ssh.host");
        assertPresent(username, "phase1.ssh.user");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit phase1.root-build=true confirmation");
        assertPresent(password, "WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        Path baseline = source(baselineProperty, "baseline source");
        Path failing = source(failingProperty, "failing source");

        HealthCheck.Http health = new HealthCheck.Http(URI.create("http://127.0.0.1:19088/health"), 200, 30);
        UserAccessUrl userAccessUrl = businessUrl(host, 19088);
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationService service = new DesktopApplicationService(
                    database, temporaryDirectory.resolve("work"), new SshdPhaseOneLinuxGateway());
            SourcePreparation baselinePreparation = service.prepareSource(baseline);
            SourcePreparation failingPreparation = service.prepareSource(failing);
            assertTrue(baselinePreparation.archive().isPresent(), "baseline must pass static analysis");
            assertTrue(failingPreparation.archive().isPresent(), "failing source must reach remote compilation");
            assertEquals(baselinePreparation.assessment().facts().orElseThrow().applicationName(),
                    failingPreparation.assessment().facts().orElseThrow().applicationName());

            ServerProfile profile = new ServerProfile("ubuntu-phase1-build-limits", host, 22, username,
                    "ssh/ubuntu-phase1-build-limits/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-build-limits-master".toCharArray(), password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "phase1-build-limits-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsPhaseOne(baselinePreparation.assessment().facts().orElseThrow().usesMavenWrapper(), health),
                    () -> "Ubuntu target must meet phase-one preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();

            DeploymentRequest baselineRequest = request(service, baselinePreparation, server, health, userAccessUrl,
                    normalLimits(rootBuild), rootBuild);
            DeploymentResult baselineResult = deploy(service, baselineRequest, profile);
            assertEquals(DeploymentStatus.SUCCEEDED, baselineResult.status(), () -> baselineResult.events().toString());

            DeploymentRequest compilerFailureRequest = request(service, failingPreparation, server, health, userAccessUrl,
                    normalLimits(rootBuild), rootBuild);
            assertEquals(baselineRequest.application(), compilerFailureRequest.application());
            DeploymentResult compilerFailure = deploy(service, compilerFailureRequest, profile);
            assertEquals(DeploymentStatus.FAILED_BUILD, compilerFailure.status(), () -> compilerFailure.events().toString());
            assertEvent(compilerFailure, "source-upload", true);
            assertEvent(compilerFailure, "remote-build", false);
            assertFalse(hasEvent(compilerFailure, "snapshot"), "build failure must not begin a release transaction");
            assertFalse(hasEvent(compilerFailure, "publish"), "build failure must not publish a candidate");
            assertBaselineStillRuns(service, baselineRequest, health, profile);

            DeploymentRequest outputLimitRequest = request(service, failingPreparation, server, health, userAccessUrl,
                    new BuildLimits(1200, 1024, 4096, 4096, 2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
            DeploymentResult outputLimitFailure = deploy(service, outputLimitRequest, profile);
            assertEquals(DeploymentStatus.FAILED_BUILD, outputLimitFailure.status(), () -> outputLimitFailure.events().toString());
            assertEvent(outputLimitFailure, "remote-build", false);
            assertTrue(outputLimitFailure.events().stream().anyMatch(event -> event.step().equals("remote-build")
                            && event.evidence().contains("输出超过")),
                    () -> "bounded output must be reported as a limit failure: " + outputLimitFailure.events());
            assertFalse(hasEvent(outputLimitFailure, "snapshot"), "output limit must stop before publication");
            assertBaselineStillRuns(service, baselineRequest, health, profile);
        }
    }

    private static BuildLimits normalLimits(boolean rootBuild) {
        return new BuildLimits(1200, 1024, 4096, 4L * 1024 * 1024, 2L * 1024 * 1024 * 1024, rootBuild);
    }

    private static DeploymentRequest request(
            DesktopApplicationService service,
            SourcePreparation preparation,
            gold.debug.windowstolinux.shared.model.server.ServerIdentity server,
            HealthCheck health,
            UserAccessUrl userAccessUrl,
            BuildLimits limits,
            boolean rootBuild
    ) {
        return service.createDeploymentRequest(preparation, server, health, Optional.of(userAccessUrl), limits, rootBuild);
    }

    private static UserAccessUrl businessUrl(String host, int port) {
        return new UserAccessUrl(URI.create("http://" + host + ":" + port + "/"));
    }

    private static DeploymentResult deploy(DesktopApplicationService service, DeploymentRequest request, ServerProfile profile)
            throws Exception {
        return service.deployResultWithStoredPassword(request, profile, CredentialStorageMode.MASTER_PASSWORD,
                "phase1-build-limits-master".toCharArray(), fingerprint -> true);
    }

    private static void assertBaselineStillRuns(
            DesktopApplicationService service,
            DeploymentRequest baseline,
            HealthCheck health,
            ServerProfile profile
    ) throws Exception {
        LifecycleActionResult refresh = service.executeLifecycleResultWithStoredPassword(baseline.application(),
                LifecycleAction.REFRESH_STATUS, health, profile, CredentialStorageMode.MASTER_PASSWORD,
                "phase1-build-limits-master".toCharArray());
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

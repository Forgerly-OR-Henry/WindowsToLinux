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
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
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
 * Opt-in live read-only checks for ownership drift, missing resources and query failures.
 *
 * <p>针对资源归属漂移、资源缺失和查询失败的可选实时只读检查。
 */
@EnabledIfSystemProperty(named = "managed.runtime.ownership-safety", matches = "true")
class UbuntuManagedOwnershipSafetyAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void neverTreatsForeignMissingOrUnqueryableResourcesAsTheSavedLiveState() throws Exception {
        String sourceProperty = System.getProperty("managed.ownership-safety.source");
        String host = System.getProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "ubuntu");
        boolean rootBuild = Boolean.getBoolean("managed.root-build");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertPresent(sourceProperty, "managed.ownership-safety.source");
        assertPresent(host, "managed.ssh.host");
        assertPresent(username, "managed.ssh.user");
        assertTrue(!"root".equals(username) || rootBuild,
                "a root SSH session requires the explicit managed.root-build=true confirmation");
        assertPresent(password, "WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        Path source = Path.of(sourceProperty).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(source), "ownership safety source directory is required");

        int proofPort = Integer.getInteger("managed.ownership-safety.port", 19095);
        HealthCheck.Http health = new HealthCheck.Http(URI.create("http://127.0.0.1:" + proofPort + "/health"), 200, 20);
        UserAccessUrl userAccessUrl = new UserAccessUrl(URI.create("http://" + host + ":" + proofPort + "/"));
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("desktop-data"))) {
            DesktopApplicationFacade service = new DesktopApplicationFacade(
                    database, temporaryDirectory.resolve("work"), new SshdLinuxGateway());
            ReviewedSourcePreparation preparation = ReviewedMavenAcceptanceFixture.prepare(service, source);
            assertTrue(preparation.archive().isPresent(), "fixture must pass static analysis");
            ServerProfile profile = new ServerProfile("ubuntu-managed-ownership", host, 22, username,
                    "ssh/ubuntu-managed-ownership/password", CredentialStorageMode.MASTER_PASSWORD);
            service.saveServerProfile(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-ownership-master".toCharArray(), password.toCharArray());
            var capabilities = service.verifyServer(profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-ownership-master".toCharArray(), fingerprint -> true);
            assertTrue(capabilities.supportsManagedDeployment(
                    preparation.assessment().facts().orElseThrow().buildTool()
                            == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.MAVEN_WRAPPER,
                    health),
                    () -> "Ubuntu target must meet managed-deployment preconditions: " + capabilities);
            var server = service.findTrustedServer(profile.id()).orElseThrow();
            ReviewedDeploymentRequest request = ReviewedMavenAcceptanceFixture.request(service, preparation, server,
                    health, Optional.of(userAccessUrl),
                    new BuildLimitConfiguration(1200, 1024, 4096, 4L * 1024 * 1024,
                            2L * 1024 * 1024 * 1024, rootBuild), rootBuild);
            DeploymentResult deployment = service.deployReviewedWithStoredPassword(
                    request, profile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-ownership-master".toCharArray(), fingerprint -> true).result();
            assertEquals(DeploymentStatus.SUCCEEDED, deployment.status(), () -> deployment.events().toString());
            ManagedApplication saved = service.listManagedApplications().stream()
                    .filter(application -> application.id().equals(request.facts().applicationId()))
                    .findFirst().orElseThrow();

            LifecycleActionResult savedRefresh = refresh(service, saved, health, profile);
            assertTrue(savedRefresh.accepted(), savedRefresh::toString);
            assertEquals(RuntimeState.RUNNING, savedRefresh.observation().orElseThrow().runtimeState());

            ManagedApplication foreignClaim = ManagedApplication.forManaged(saved.id(), saved.server(), "f".repeat(64));
            LifecycleActionResult foreignRefresh = refresh(service, foreignClaim, health, profile);
            assertFalse(foreignRefresh.accepted(), foreignRefresh::toString);
            assertTrue(foreignRefresh.observation().isEmpty(),
                    "a controlled owner mismatch must not produce a live observation");

            ManagedApplication missing = ManagedApplication.forManaged("managed-ownership-missing", saved.server(), "e".repeat(64));
            LifecycleActionResult missingRefresh = refresh(service, missing, health, profile);
            assertFalse(missingRefresh.accepted(), missingRefresh::toString);
            assertTrue(missingRefresh.observation().isEmpty(),
                    "a missing managed resource must not produce a live observation");

            ServerProfile unreachableProfile = new ServerProfile(profile.id(), host, 1, username,
                    profile.credentialKey(), CredentialStorageMode.MASTER_PASSWORD);
            LifecycleActionResult queryFailure = service.executeLifecycleResultWithStoredPassword(
                    saved, LifecycleAction.REFRESH_STATUS,
                    health, unreachableProfile, CredentialStorageMode.MASTER_PASSWORD,
                    "managed-ownership-master".toCharArray());
            assertFalse(queryFailure.accepted());
            assertTrue(queryFailure.observation().isEmpty(),
                    "a transport failure must not return a locally cached running observation");
        }
    }

    private static LifecycleActionResult refresh(
            DesktopApplicationFacade service,
            ManagedApplication application,
            HealthCheck health,
            ServerProfile profile
    ) throws Exception {
        return service.executeLifecycleResultWithStoredPassword(
                application, LifecycleAction.REFRESH_STATUS, health, profile,
                CredentialStorageMode.MASTER_PASSWORD, "managed-ownership-master".toCharArray());
    }

    private static void assertPresent(String value, String name) {
        assertTrue(value != null && !value.isBlank(), name + " is required");
    }
}

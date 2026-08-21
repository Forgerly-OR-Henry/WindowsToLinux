package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests complete single-component success persistence without opening SSH. / 测试不打开 SSH 的完整单组件成功持久化。 */
class SingleComponentDeploymentPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyPersistsExactReviewedRuntimeEmptyDataPathsConfigurationAndSecrets() throws Exception {
        ManagedApplication application = application();
        HealthCheck.Tcp health = new HealthCheck.Tcp(18080, 20, 1);
        DeploymentRuntimeSpecification reviewedRuntime = new DeploymentRuntimeSpecification.NodeService(22, health);
        ManagedApplicationRuntimeConfiguration runtime = new ManagedApplicationRuntimeConfiguration(health,
                Optional.empty());
        CurrentRelease release = new CurrentRelease(application.id(), "c".repeat(64),
                Instant.parse("2026-08-22T00:00:00Z"));
        ConfigurationSnapshot configuration = configuration(application.id());
        SecretReference secret = new SecretReference("database-password", 1);

        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory.resolve("complete"))) {
            persistence.applicationSecrets().saveRevision(new StoredApplicationSecretRevision(secret,
                    "application-secret/database-password/1", CredentialStorageMode.MASTER_PASSWORD,
                    Instant.parse("2026-08-22T00:00:00Z")));
            ReviewedDeploymentUseCase.recordSuccessful(persistence.managedApplicationGraphs(), application, runtime,
                    release, reviewedRuntime, configuration, List.of(secret));

            var graph = persistence.managedApplicationGraphs().find(application.id()).orElseThrow();
            assertEquals(application.id(), graph.applicationId());
            assertEquals(application.id(), graph.healthComponentId());
            assertEquals(1, graph.components().size());
            var component = graph.components().getFirst();
            assertEquals(application.id(), component.componentId());
            assertEquals(List.of(), component.dependencies());
            assertEquals(Optional.of(reviewedRuntime), component.reviewedRuntime());
            assertEquals(Optional.of(List.of()), component.reviewedDataPaths());
            assertEquals(runtime, persistence.managedApplications().findRuntime(application.id()).orElseThrow());
            assertEquals(release, persistence.managedApplications().findRelease(application.id()).orElseThrow());
            assertEquals(configuration, persistence.configurations()
                    .findRelease(application.id(), release.releaseSha256()).orElseThrow());
            assertEquals(List.of(secret), persistence.applicationSecrets()
                    .findRelease(application.id(), release.releaseSha256()).orElseThrow());
        }
    }

    @Test
    void leavesNoPartialApplicationGraphOrReleaseWhenASecretReferenceIsMissing() throws Exception {
        ManagedApplication application = application();
        HealthCheck.Tcp health = new HealthCheck.Tcp(18080, 20, 1);
        DeploymentRuntimeSpecification reviewedRuntime = new DeploymentRuntimeSpecification.NodeService(22, health);
        ManagedApplicationRuntimeConfiguration runtime = new ManagedApplicationRuntimeConfiguration(health,
                Optional.empty());
        CurrentRelease release = new CurrentRelease(application.id(), "d".repeat(64),
                Instant.parse("2026-08-22T00:00:00Z"));

        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory.resolve("rollback"))) {
            assertThrows(SQLException.class, () -> ReviewedDeploymentUseCase.recordSuccessful(
                    persistence.managedApplicationGraphs(), application, runtime, release, reviewedRuntime,
                    configuration(application.id()), List.of(new SecretReference("missing", 1))));

            assertTrue(persistence.managedApplications().find(application.id()).isEmpty());
            assertTrue(persistence.managedApplications().findRelease(application.id()).isEmpty());
            assertTrue(persistence.managedApplicationGraphs().find(application.id()).isEmpty());
        }
    }

    private static ManagedApplication application() {
        ServerIdentity server = new ServerIdentity("server-one", "192.0.2.10", 22,
                "SHA256:AAAAAAAAAAAA");
        return ManagedApplication.forManaged("demo", server, "a".repeat(64));
    }

    private static ConfigurationSnapshot configuration(String applicationId) {
        return ConfigurationSnapshot.create(applicationId, 1, "v1", Instant.parse("2026-08-22T00:00:00Z"),
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Number(18080))));
    }
}

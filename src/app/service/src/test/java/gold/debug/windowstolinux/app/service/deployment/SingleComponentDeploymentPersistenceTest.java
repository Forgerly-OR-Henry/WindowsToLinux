package gold.debug.windowstolinux.app.service.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
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

/** Tests complete single-component success persistence without opening SSH. / 测试不打开 SSH 的完整单组件成功持久化。 */
class SingleComponentDeploymentPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyPersistsExactReviewedRuntimeResourcesConfigurationAndSecrets() throws Exception {
        ManagedApplication application = application();
        HealthCheck.Tcp health = new HealthCheck.Tcp(18080, 20, 1);
        DeploymentRuntimeSpecification reviewedRuntime = new DeploymentRuntimeSpecification.NodeService(22, health);
        ManagedApplicationRuntimeConfiguration runtime = new ManagedApplicationRuntimeConfiguration(health,
                Optional.empty());
        CurrentRelease release = new CurrentRelease(application.id(), "c".repeat(64),
                Instant.parse("2026-08-22T00:00:00Z"));
        ConfigurationSnapshot configuration = configuration(application.id());
        SecretReference secret = new SecretReference("database-password", 1);
        ManagedDatabaseBinding databaseBinding = new ManagedDatabaseBinding("application",
                new ManagedDatabaseConnection.Sqlite("application.db"));

        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory.resolve("complete"))) {
            persistence.applicationSecrets()
                    .saveRevision(new StoredApplicationSecretRevision(secret, "application-secret/database-password/1",
                            CredentialStorageMode.MASTER_PASSWORD, Instant.parse("2026-08-22T00:00:00Z")));
            ReviewedDeploymentUseCase.recordSuccessful(persistence.managedApplicationGraphs(), application, runtime,
                    release, reviewedRuntime, configuration, List.of(secret), Optional.of(List.of(databaseBinding)));

            var graph = persistence.managedApplicationGraphs().find(application.id()).orElseThrow();
            assertEquals(application.id(), graph.applicationId());
            assertEquals(application.id(), graph.healthComponentId());
            assertEquals(1, graph.components().size());
            var component = graph.components().getFirst();
            assertEquals(application.id(), component.componentId());
            assertEquals(List.of(), component.dependencies());
            assertEquals(Optional.of(reviewedRuntime), component.reviewedRuntime());
            assertEquals(Optional.of(List.of()), component.reviewedDataPaths());
            assertEquals(Optional.of(List.of(databaseBinding)),
                    component.reviewedResourceBindings().orElseThrow().databaseBindings());
            assertEquals(runtime, persistence.managedApplications().findRuntime(application.id()).orElseThrow());
            assertEquals(release, persistence.managedApplications().findRelease(application.id()).orElseThrow());
            assertEquals(configuration,
                    persistence.configurations().findRelease(application.id(), release.releaseSha256()).orElseThrow());
            assertEquals(List.of(secret), persistence.applicationSecrets()
                    .findRelease(application.id(), release.releaseSha256()).orElseThrow());
        }
    }

    @Test
    void bindsAPresavedConfigurationWithSubmillisecondTimeAndUnsortedEntries() throws Exception {
        ManagedApplication application = application();
        HealthCheck.Tcp health = new HealthCheck.Tcp(18080, 20, 1);
        var configuration = ConfigurationSnapshot.create(application.id(), 1, "v1",
                Instant.parse("2026-09-08T07:00:00.123456789Z"),
                List.of(new ConfigurationEntry("Z_LAST", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Text("last")),
                        new ConfigurationEntry("A_FIRST", ConfigurationScope.RUNTIME,
                                new ConfigurationValue.Text("first"))));
        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory.resolve("presaved"))) {
            persistence.configurations().save(configuration);
            ReviewedDeploymentUseCase.recordSuccessful(persistence.managedApplicationGraphs(), application,
                    new ManagedApplicationRuntimeConfiguration(health, Optional.empty()),
                    new CurrentRelease(application.id(), "e".repeat(64), Instant.now()),
                    new DeploymentRuntimeSpecification.NodeService(18, health), configuration, List.of(),
                    Optional.of(List.of()));
            assertTrue(persistence.managedApplications().find(application.id()).isPresent());
            assertTrue(persistence.managedApplicationGraphs().find(application.id()).isPresent());
            var stored = persistence.configurations().findRelease(application.id(), "e".repeat(64)).orElseThrow();
            assertEquals(configuration.sha256(), stored.sha256());
            assertEquals(Instant.parse("2026-09-08T07:00:00.123Z"), stored.createdAt());
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
            assertThrows(SQLException.class,
                    () -> ReviewedDeploymentUseCase.recordSuccessful(persistence.managedApplicationGraphs(),
                            application, runtime, release, reviewedRuntime, configuration(application.id()),
                            List.of(new SecretReference("missing", 1)), Optional.of(List.of())));

            assertTrue(persistence.managedApplications().find(application.id()).isEmpty());
            assertTrue(persistence.managedApplications().findRelease(application.id()).isEmpty());
            assertTrue(persistence.managedApplicationGraphs().find(application.id()).isEmpty());
        }
    }

    private static ManagedApplication application() {
        ServerIdentity server = new ServerIdentity("server-one", "192.0.2.10", 22, "SHA256:AAAAAAAAAAAA");
        return ManagedApplication.forManaged("demo", server, "a".repeat(64));
    }

    private static ConfigurationSnapshot configuration(String applicationId) {
        return ConfigurationSnapshot.create(applicationId, 1, "v1", Instant.parse("2026-08-22T00:00:00Z"), List
                .of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(18080))));
    }
}

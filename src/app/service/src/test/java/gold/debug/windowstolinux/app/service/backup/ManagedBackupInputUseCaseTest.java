package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
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
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment.MissingInputType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests structured persisted-input readiness without remote access. / 测试不访问远端的结构化持久化输入准入。 */
class ManagedBackupInputUseCaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsCompleteExactInputsThroughTheDesktopProductFacade() throws Exception {
        Path data = temporaryDirectory.resolve("complete");
        AtomicInteger connections = new AtomicInteger();
        try (DesktopPersistence persistence = DesktopPersistence.open(data)) {
            PersistedInput input = persistComplete(persistence, "demo", "c".repeat(64));
            DesktopApplicationFacade service = new DesktopApplicationFacade(persistence,
                    temporaryDirectory.resolve("work"), (endpoint, credential, verifier) -> {
                        connections.incrementAndGet();
                        throw new AssertionError("persisted-input assessment must not connect to a server");
                    });

            ManagedBackupInputAssessment assessment = service.assessManagedBackupInputs("demo");

            assertTrue(assessment.persistedInputsComplete());
            assertEquals(List.of("demo"), assessment.componentIds());
            assertEquals(java.util.Map.of("demo", input.release().releaseSha256()),
                    assessment.currentReleaseIdentities());
            assertEquals(List.of(), assessment.applicationMissingInputs());
            assertEquals(java.util.Map.of(), assessment.componentMissingInputs());
            assertEquals(0, connections.get());
        }
    }

    @Test
    void reportsLegacyAndUnboundFieldsWithoutInventingValues() throws Exception {
        Path data = temporaryDirectory.resolve("incomplete");
        try (DesktopPersistence persistence = DesktopPersistence.open(data)) {
            PersistedInput input = persistComplete(persistence, "demo", "d".repeat(64));
            removeRequiredInputs(data.resolve("windowstolinux.db"), input);
            ManagedBackupInputAssessment assessment = new ManagedBackupInputUseCase(
                    persistence.managedApplicationGraphs(), persistence.managedApplications(),
                    persistence.configurations(), persistence.applicationSecrets()).assess("demo");

            assertFalse(assessment.persistedInputsComplete());
            assertEquals(List.of(), assessment.applicationMissingInputs());
            assertEquals(List.of(MissingInputType.REVIEWED_RUNTIME, MissingInputType.REVIEWED_DATA_PATHS,
                            MissingInputType.REVIEWED_RESOURCE_BINDINGS,
                            MissingInputType.RELEASE_CONFIGURATION, MissingInputType.RELEASE_SECRET_REFERENCES),
                    assessment.componentMissingInputs().get("demo"));
            assertEquals(input.release().releaseSha256(), assessment.currentReleaseIdentities().get("demo"));
        }
    }

    @Test
    void distinguishesUnreviewedDatabaseScopeFromAnExplicitlyDatabaseFreeRelease() throws Exception {
        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory.resolve("database-unknown"))) {
            persist(persistence, "demo", "e".repeat(64), Optional.empty());

            ManagedBackupInputAssessment assessment = new ManagedBackupInputUseCase(
                    persistence.managedApplicationGraphs(), persistence.managedApplications(),
                    persistence.configurations(), persistence.applicationSecrets()).assess("demo");

            assertFalse(assessment.persistedInputsComplete());
            assertEquals(List.of(MissingInputType.REVIEWED_DATABASE_BINDINGS),
                    assessment.componentMissingInputs().get("demo"));
        }
    }

    @Test
    void reportsAMissingGraphAsAnApplicationLevelBlocker() throws Exception {
        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory.resolve("missing"))) {
            ManagedBackupInputAssessment assessment = new ManagedBackupInputUseCase(
                    persistence.managedApplicationGraphs(), persistence.managedApplications(),
                    persistence.configurations(), persistence.applicationSecrets()).assess("unknown");

            assertFalse(assessment.persistedInputsComplete());
            assertEquals(List.of(), assessment.componentIds());
            assertEquals(List.of(MissingInputType.MANAGED_APPLICATION_GRAPH),
                    assessment.applicationMissingInputs());
        }
    }

    private static PersistedInput persistComplete(DesktopPersistence persistence, String applicationId,
                                                   String releaseIdentity) throws Exception {
        return persist(persistence, applicationId, releaseIdentity, Optional.of(List.of()));
    }

    private static PersistedInput persist(DesktopPersistence persistence, String applicationId,
                                          String releaseIdentity,
                                          Optional<List<gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding>>
                                                  databaseBindings) throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "192.0.2.10", 22,
                "SHA256:AAAAAAAAAAAA");
        ManagedApplication application = ManagedApplication.forManaged(applicationId, server, "a".repeat(64));
        HealthCheck.Tcp health = new HealthCheck.Tcp(18080, 20, 1);
        DeploymentRuntimeSpecification reviewedRuntime = new DeploymentRuntimeSpecification.NodeService(22, health);
        ManagedApplicationRuntimeConfiguration runtime = new ManagedApplicationRuntimeConfiguration(health,
                Optional.empty());
        CurrentRelease release = new CurrentRelease(applicationId, releaseIdentity,
                Instant.parse("2026-08-22T00:00:00Z"));
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create(applicationId, 1, "v1",
                Instant.parse("2026-08-22T00:00:00Z"), List.of(new ConfigurationEntry("PORT",
                        ConfigurationScope.RUNTIME, new ConfigurationValue.Number(18080))));
        SecretReference secret = new SecretReference("database-password", 1);
        persistence.applicationSecrets().saveRevision(new StoredApplicationSecretRevision(secret,
                "application-secret/database-password/1", CredentialStorageMode.MASTER_PASSWORD,
                Instant.parse("2026-08-22T00:00:00Z")));
        var component = new ManagedApplicationGraph.Component(applicationId, application, runtime, List.of(),
                Optional.of(reviewedRuntime), Optional.of(List.of()),
                Optional.of(new ManagedComponentResourceBindings(List.of(), databaseBindings)));
        persistence.managedApplicationGraphs().recordSuccessfulApplication(
                new ManagedApplicationGraph(applicationId, applicationId, Optional.of(health), List.of(component)),
                List.of(new SuccessfulManagedDeployment(application, runtime, release, configuration,
                        List.of(secret))));
        return new PersistedInput(release);
    }

    private static void removeRequiredInputs(Path database, PersistedInput input) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE managed_application_graph_component "
                    + "SET reviewed_runtime=NULL, reviewed_data_paths=NULL, reviewed_resource_bindings=NULL "
                    + "WHERE application_id='demo'");
            statement.executeUpdate("DELETE FROM application_release_configuration_binding "
                    + "WHERE application_id='demo' AND release_identity='" + input.release().releaseSha256() + "'");
            statement.executeUpdate("DELETE FROM application_release_secret_reference "
                    + "WHERE application_id='demo' AND release_identity='" + input.release().releaseSha256() + "'");
            statement.executeUpdate("DELETE FROM application_release_secret_binding "
                    + "WHERE application_id='demo' AND release_identity='" + input.release().releaseSha256() + "'");
        }
    }

    private record PersistedInput(CurrentRelease release) {
    }
}

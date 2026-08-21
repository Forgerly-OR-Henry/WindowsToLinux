package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException;
import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiRoleAssignment;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPersistenceIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOnlyTheLastRemoteObservationAsHistory() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "example.test", 22, "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("demo", server, "a".repeat(64));
        LifecycleObservation observation = new LifecycleObservation(
                application, RuntimeState.RUNNING, AutostartState.ENABLED, true, Instant.now(), "remote observation"
        );

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory)) {
            database.managedApplications().save(application);
            database.managedApplications().saveRelease(new CurrentRelease("demo", "b".repeat(64), Instant.now()));
            database.managedApplications().saveObservation(observation);
            database.encryptedSecrets().save(new OpaqueSecret("ssh/server-one", "test", new byte[]{1}, new byte[]{2}, new byte[]{3}));
            database.servers().saveServerProfile(new StoredServerProfile("server-one", "example.test", 22, "deployer", "ssh/server-one/password", "MASTER_PASSWORD"));
            database.aiProfiles().saveDefault(new StoredAiProfile("https://example.test/v1/chat/completions", "gpt-5", "ai/default/api-key", "MASTER_PASSWORD"));

            assertEquals(server, database.servers().findServer("server-one").orElseThrow());
            assertEquals(RuntimeState.RUNNING, database.managedApplications().findObservation(application).orElseThrow().runtimeState());
            assertTrue(database.encryptedSecrets().find("ssh/server-one").isPresent());
            assertEquals("deployer", database.servers().findServerProfile("server-one").orElseThrow().username());
            assertEquals("gpt-5", database.aiProfiles().findDefault().orElseThrow().model());
            assertEquals(application, database.managedApplications().find("demo").orElseThrow());
            assertEquals("b".repeat(64), database.managedApplications().findRelease("demo").orElseThrow().releaseSha256());
            assertEquals(1, database.managedApplications().list().size());
        }
    }

    @Test
    void storesNonSecretDesktopPreferences() throws Exception {
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory)) {
            assertTrue(database.preferences().find(DesktopPersistence.UI_LOCALE_SETTING).isEmpty());
            assertTrue(database.preferences().find(DesktopPersistence.UI_THEME_SETTING).isEmpty());

            database.preferences().save(DesktopPersistence.UI_LOCALE_SETTING, "zh-CN");
            database.preferences().save(DesktopPersistence.UI_THEME_SETTING, "SYSTEM");

            assertEquals("zh-CN", database.preferences().find(DesktopPersistence.UI_LOCALE_SETTING).orElseThrow());
            assertEquals("SYSTEM", database.preferences().find(DesktopPersistence.UI_THEME_SETTING).orElseThrow());
        }
    }

    @Test
    void classifiesUnavailableDirectoryCorruptionLockAndRollbackFailure() throws Exception {
        Path fileInsteadOfDirectory = Files.writeString(temporaryDirectory.resolve("not-a-directory"), "fixture");
        assertEquals(DesktopPersistenceFailureType.DATA_DIRECTORY_UNAVAILABLE,
                assertThrows(DesktopPersistenceException.class,
                        () -> DesktopPersistence.open(fileInsteadOfDirectory)).failure().definition());

        Path corruptDirectory = Files.createDirectories(temporaryDirectory.resolve("corrupt"));
        Files.writeString(corruptDirectory.resolve("windowstolinux.db"), "not a sqlite database");
        assertEquals(DesktopPersistenceFailureType.DATABASE_CORRUPTED,
                assertThrows(DesktopPersistenceException.class,
                        () -> DesktopPersistence.open(corruptDirectory)).failure().definition());

        Path lockedDirectory = Files.createDirectories(temporaryDirectory.resolve("locked"));
        Path lockedDatabase = lockedDirectory.resolve("windowstolinux.db");
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + lockedDatabase);
             Statement statement = lock.createStatement()) {
            statement.execute("PRAGMA locking_mode = EXCLUSIVE");
            statement.execute("BEGIN EXCLUSIVE");
            statement.execute("CREATE TABLE lock_probe (value INTEGER)");
            assertEquals(DesktopPersistenceFailureType.DATABASE_LOCKED,
                    assertThrows(DesktopPersistenceException.class,
                            () -> DesktopPersistence.open(lockedDirectory)).failure().definition());
            statement.execute("ROLLBACK");
        }

        java.sql.SQLException primary = new java.sql.SQLException("fixture transaction failure");
        primary.addSuppressed(new java.sql.SQLException("fixture rollback failure"));
        assertEquals(DesktopPersistenceFailureType.ROLLBACK_FAILED,
                DesktopPersistence.map(primary).failure().definition());
    }

    @Test
    void migratesVersionTwoDatabaseToPreferenceStorage() throws Exception {
        Path dataDirectory = Files.createDirectories(temporaryDirectory.resolve("version-two"));
        Path databaseFile = dataDirectory.resolve("windowstolinux.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 2");
        }

        try (DesktopPersistence database = DesktopPersistence.open(dataDirectory)) {
            database.preferences().save(DesktopPersistence.UI_THEME_SETTING, "DARK");
            assertEquals("DARK", database.preferences().find(DesktopPersistence.UI_THEME_SETTING).orElseThrow());
        }
    }

    @Test
    void recordsTheSuccessfulRuntimeContractAndReleaseAtomically() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22, "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("demo", server, "a".repeat(64));
        ManagedApplicationRuntimeConfiguration http = new ManagedApplicationRuntimeConfiguration(
                new HealthCheck.Http(URI.create("http://127.0.0.1:18080/actuator/health"), 200, 20),
                Optional.of(new UserAccessUrl(URI.create("http://198.51.100.24:18080/")))
        );
        Instant initialTime = Instant.parse("2026-08-10T00:00:00Z");

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory)) {
            database.managedApplications().recordSuccessfulDeployment(application, http,
                    new CurrentRelease(application.id(), "b".repeat(64), initialTime));

            assertEquals(http, database.managedApplications().findRuntime(application.id()).orElseThrow());
            assertEquals("b".repeat(64), database.managedApplications().findRelease(application.id()).orElseThrow().releaseSha256());

            ManagedApplicationRuntimeConfiguration tcp = new ManagedApplicationRuntimeConfiguration(
                    new HealthCheck.Tcp(19092, 15, 2), Optional.empty()
            );
            database.managedApplications().recordSuccessfulDeployment(application, tcp,
                    new CurrentRelease(application.id(), "c".repeat(64), initialTime.plusSeconds(1)));

            assertEquals(tcp, database.managedApplications().findRuntime(application.id()).orElseThrow());
            assertEquals("c".repeat(64), database.managedApplications().findRelease(application.id()).orElseThrow().releaseSha256());
        }
    }

    @Test
    void rollsBackEveryComponentWhenWholeApplicationPersistenceFails() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22,
                "SHA256:exampleFingerprint");
        ManagedApplication api = ManagedApplication.forManaged("shop-api", server, "a".repeat(64));
        ManagedApplication web = ManagedApplication.forManaged("shop-web", server, "b".repeat(64));
        var runtime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(18081, 15, 1),
                Optional.empty());
        Instant publishedAt = Instant.parse("2026-08-13T00:00:00Z");
        SecretReference missing = new SecretReference("missing", 1);

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("atomic-components"))) {
            ManagedApplicationGraph graph = new ManagedApplicationGraph("shop", "web", List.of(
                    new ManagedApplicationGraph.Component("api", api, runtime, List.of(),
                            Optional.of(new DeploymentRuntimeSpecification.NodeService(22, runtime.healthCheck())),
                            Optional.of(List.of())),
                    new ManagedApplicationGraph.Component("web", web, runtime, List.of("api"),
                            Optional.of(new DeploymentRuntimeSpecification.NodeService(22, runtime.healthCheck())),
                            Optional.of(List.of()))));
            assertThrows(java.sql.SQLException.class, () -> database.managedApplicationGraphs()
                    .recordSuccessfulApplication(graph, List.of(
                            new SuccessfulManagedDeployment(api, runtime,
                                    new CurrentRelease(api.id(), "c".repeat(64), publishedAt),
                                    configuration(api.id()), List.of()),
                            new SuccessfulManagedDeployment(web, runtime,
                                    new CurrentRelease(web.id(), "d".repeat(64), publishedAt),
                                    configuration(web.id()), List.of(missing)))));

            assertTrue(database.managedApplications().find(api.id()).isEmpty());
            assertTrue(database.managedApplications().find(web.id()).isEmpty());
            assertTrue(database.managedApplications().findRelease(api.id()).isEmpty());
            assertTrue(database.managedApplicationGraphs().find("shop").isEmpty());
        }
    }

    @Test
    void restoresDurableWholeApplicationGraphWithoutBuildOrSecretValues() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22,
                "SHA256:exampleFingerprint");
        ManagedApplication api = ManagedApplication.forManaged("shop-api", server, "a".repeat(64));
        ManagedApplication web = ManagedApplication.forManaged("shop-web", server, "b".repeat(64));
        var apiRuntime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(18081, 15, 1),
                Optional.empty());
        var webRuntime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Http(
                URI.create("http://127.0.0.1:18082/health"), 200, 15),
                Optional.of(new UserAccessUrl(URI.create("http://198.51.100.24:18082/"))));
        Instant publishedAt = Instant.parse("2026-08-13T00:00:00Z");
        ManagedApplicationGraph graph = new ManagedApplicationGraph("shop", "web", List.of(
                new ManagedApplicationGraph.Component("api", api, apiRuntime, List.of(),
                        Optional.of(new DeploymentRuntimeSpecification.NodeService(22, apiRuntime.healthCheck())),
                        Optional.of(List.of(new ComponentDataPath("uploads", ComponentDataPath.AccessMode.READ_WRITE,
                                "uploads-v1", false)))),
                new ManagedApplicationGraph.Component("web", web, webRuntime, List.of("api"),
                        Optional.of(new DeploymentRuntimeSpecification.StaticSite("dist",
                                (HealthCheck.Http) webRuntime.healthCheck())), Optional.of(List.of()))));

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("durable-graph"))) {
            database.managedApplicationGraphs().recordSuccessfulApplication(graph, List.of(
                    new SuccessfulManagedDeployment(api, apiRuntime,
                            new CurrentRelease(api.id(), "c".repeat(64), publishedAt),
                            configuration(api.id()), List.of()),
                    new SuccessfulManagedDeployment(web, webRuntime,
                            new CurrentRelease(web.id(), "d".repeat(64), publishedAt),
                            configuration(web.id()), List.of())));

            assertEquals(graph, database.managedApplicationGraphs().find("shop").orElseThrow());
            assertEquals(configuration(api.id()),
                    database.configurations().findRelease(api.id(), "c".repeat(64)).orElseThrow());
            assertEquals(configuration(web.id()),
                    database.configurations().findRelease(web.id(), "d".repeat(64)).orElseThrow());
        }
    }

    @Test
    void migratesVersionSevenGraphsWithoutInventingReviewedRuntimeDefinitions() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("version-seven-graph");
        Path databaseFile = dataDirectory.resolve("windowstolinux.db");
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22,
                "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("legacy-app", server, "a".repeat(64));
        var runtime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(18081, 15, 1),
                Optional.empty());
        var graph = new ManagedApplicationGraph("legacy", "app", List.of(
                new ManagedApplicationGraph.Component("app", application, runtime, List.of(),
                        Optional.of(new DeploymentRuntimeSpecification.NodeService(22, runtime.healthCheck())),
                        Optional.of(List.of(new ComponentDataPath("data", ComponentDataPath.AccessMode.READ_WRITE,
                                "data-v1", true))))));
        try (DesktopPersistence database = DesktopPersistence.open(dataDirectory)) {
            database.managedApplicationGraphs().recordSuccessfulApplication(graph, List.of(
                    new SuccessfulManagedDeployment(application, runtime,
                            new CurrentRelease(application.id(), "b".repeat(64), Instant.now()),
                            configuration(application.id()), List.of())));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE managed_application_graph_component SET reviewed_runtime=NULL");
            statement.execute("UPDATE managed_application_graph_component SET reviewed_data_paths=NULL");
            statement.execute("DELETE FROM application_release_configuration_binding");
            statement.execute("PRAGMA user_version = 7");
        }

        try (DesktopPersistence database = DesktopPersistence.open(dataDirectory)) {
            assertTrue(database.managedApplicationGraphs().find("legacy").orElseThrow()
                    .components().getFirst().reviewedRuntime().isEmpty(),
                    "v7 rows must remain explicitly unavailable instead of being reconstructed");
            assertTrue(database.managedApplicationGraphs().find("legacy").orElseThrow()
                    .components().getFirst().reviewedDataPaths().isEmpty(),
                    "v7 rows must not invent reviewed data paths");
            assertTrue(database.configurations().findRelease(application.id(), "b".repeat(64)).isEmpty(),
                    "v7 rows must not invent an exact release configuration binding");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement();
             var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(9, version.getInt(1));
        }
    }

    @Test
    void migratesVersionEightGraphsWithoutInventingReviewedDataPaths() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("version-eight-graph");
        Path databaseFile = dataDirectory.resolve("windowstolinux.db");
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22,
                "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("legacy-app", server, "a".repeat(64));
        var runtime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(18081, 15, 1),
                Optional.empty());
        var reviewedRuntime = new DeploymentRuntimeSpecification.NodeService(22, runtime.healthCheck());
        var graph = new ManagedApplicationGraph("legacy", "app", List.of(
                new ManagedApplicationGraph.Component("app", application, runtime, List.of(),
                        Optional.of(reviewedRuntime), Optional.of(List.of()))));
        try (DesktopPersistence database = DesktopPersistence.open(dataDirectory)) {
            database.managedApplicationGraphs().recordSuccessfulApplication(graph, List.of(
                    new SuccessfulManagedDeployment(application, runtime,
                            new CurrentRelease(application.id(), "b".repeat(64), Instant.now()),
                            configuration(application.id()), List.of())));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE managed_application_graph_component SET reviewed_data_paths=NULL");
            statement.execute("DELETE FROM application_release_configuration_binding");
            statement.execute("PRAGMA user_version = 8");
        }

        try (DesktopPersistence database = DesktopPersistence.open(dataDirectory)) {
            var restored = database.managedApplicationGraphs().find("legacy").orElseThrow()
                    .components().getFirst();
            assertEquals(Optional.of(reviewedRuntime), restored.reviewedRuntime());
            assertTrue(restored.reviewedDataPaths().isEmpty(),
                    "v8 rows must remain explicitly unavailable instead of inventing reviewed data paths");
            assertTrue(database.configurations().findRelease(application.id(), "b".repeat(64)).isEmpty(),
                    "v8 rows must not invent an exact release configuration binding");
        }
    }

    @Test
    void rejectsNewSuccessfulGraphsWithoutReviewedRuntimeDefinitions() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22,
                "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("missing-runtime", server, "a".repeat(64));
        var runtime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(18081, 15, 1),
                Optional.empty());
        var graph = new ManagedApplicationGraph("missing", "app", List.of(
                new ManagedApplicationGraph.Component("app", application, runtime, List.of())));
        var deployment = new SuccessfulManagedDeployment(application, runtime,
                new CurrentRelease(application.id(), "b".repeat(64), Instant.now()),
                configuration(application.id()), List.of());
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("missing-runtime"))) {
            assertThrows(IllegalArgumentException.class, () -> database.managedApplicationGraphs()
                    .recordSuccessfulApplication(graph, List.of(deployment)));
            assertTrue(database.managedApplications().find(application.id()).isEmpty());
        }
    }

    @Test
    void rejectsNewSuccessfulGraphsWithoutReviewedDataPathDefinitions() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22,
                "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("missing-data-paths", server, "a".repeat(64));
        var runtime = new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(18081, 15, 1),
                Optional.empty());
        var graph = new ManagedApplicationGraph("missing", "app", List.of(
                new ManagedApplicationGraph.Component("app", application, runtime, List.of(),
                        Optional.of(new DeploymentRuntimeSpecification.NodeService(22, runtime.healthCheck())))));
        var deployment = new SuccessfulManagedDeployment(application, runtime,
                new CurrentRelease(application.id(), "b".repeat(64), Instant.now()),
                configuration(application.id()), List.of());
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("missing-data-paths"))) {
            assertThrows(IllegalArgumentException.class, () -> database.managedApplicationGraphs()
                    .recordSuccessfulApplication(graph, List.of(deployment)));
            assertTrue(database.managedApplications().find(application.id()).isEmpty());
        }
    }

    @Test
    void migratesVersionFourArtifactIdentityToVersionFiveReleaseIdentityWithoutDataLoss() throws Exception {
        Path dataDirectory = Files.createDirectories(temporaryDirectory.resolve("version-four"));
        Path databaseFile = dataDirectory.resolve("windowstolinux.db");
        String preservedIdentity = "d".repeat(64);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE server (id TEXT PRIMARY KEY, host TEXT NOT NULL, ssh_port INTEGER NOT NULL, host_key_sha256 TEXT NOT NULL)");
            statement.execute("CREATE TABLE managed_application (id TEXT PRIMARY KEY, server_id TEXT NOT NULL REFERENCES server(id), systemd_unit TEXT NOT NULL, release_root TEXT NOT NULL, ownership_manifest_sha256 TEXT NOT NULL)");
            statement.execute("CREATE TABLE managed_application_release (application_id TEXT PRIMARY KEY REFERENCES managed_application(id), artifact_sha256 TEXT NOT NULL, published_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO server VALUES ('server-one', 'example.test', 22, 'SHA256:fixture')");
            statement.execute("INSERT INTO managed_application VALUES ('demo', 'server-one', 'windowstolinux-demo.service', '/var/lib/windowstolinux/apps/demo', '" + "a".repeat(64) + "')");
            statement.execute("INSERT INTO managed_application_release VALUES ('demo', '" + preservedIdentity + "', 1)");
            statement.execute("PRAGMA user_version = 4");
        }

        try (DesktopPersistence database = DesktopPersistence.open(dataDirectory)) {
            assertEquals(preservedIdentity,
                    database.managedApplications().findRelease("demo").orElseThrow().releaseSha256());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            try (var columns = statement.executeQuery("PRAGMA table_info(managed_application_release)")) {
                java.util.Set<String> names = new java.util.HashSet<>();
                while (columns.next()) {
                    names.add(columns.getString("name"));
                }
                assertTrue(names.contains("release_sha256"));
                assertTrue(!names.contains("artifact_sha256"));
            }
            try (var version = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(9, version.getInt(1));
            }
        }
    }

    @Test
    void migratesLegacyApplicationsWithoutInventingRuntimeConfiguration() throws Exception {
        Path dataDirectory = Files.createDirectories(temporaryDirectory.resolve("legacy"));
        Path databaseFile = dataDirectory.resolve("windowstolinux.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE server (id TEXT PRIMARY KEY, host TEXT NOT NULL, ssh_port INTEGER NOT NULL, host_key_sha256 TEXT NOT NULL)");
            statement.execute("CREATE TABLE managed_application (id TEXT PRIMARY KEY, server_id TEXT NOT NULL REFERENCES server(id), systemd_unit TEXT NOT NULL, release_root TEXT NOT NULL, ownership_manifest_sha256 TEXT NOT NULL)");
            try (PreparedStatement server = connection.prepareStatement(
                    "INSERT INTO server (id, host, ssh_port, host_key_sha256) VALUES (?, ?, ?, ?)" );
                 PreparedStatement application = connection.prepareStatement(
                         "INSERT INTO managed_application (id, server_id, systemd_unit, release_root, ownership_manifest_sha256) VALUES (?, ?, ?, ?, ?)")) {
                server.setString(1, "server-one");
                server.setString(2, "198.51.100.24");
                server.setInt(3, 22);
                server.setString(4, "SHA256:exampleFingerprint");
                server.executeUpdate();
                application.setString(1, "demo");
                application.setString(2, "server-one");
                application.setString(3, "windowstolinux-demo.service");
                application.setString(4, "/var/lib/windowstolinux/apps/demo");
                application.setString(5, "a".repeat(64));
                application.executeUpdate();
            }
            statement.execute("PRAGMA user_version = 1");
        }

        try (DesktopPersistence database = DesktopPersistence.open(dataDirectory)) {
            assertTrue(database.managedApplications().find("demo").isPresent());
            assertTrue(database.managedApplications().findRuntime("demo").isEmpty(),
                    "旧记录不允许根据观测或 UI 默认值伪造运行配置");
        }
    }

    @Test
    void persistsImmutableConfigurationAndSecretRevisionBindingsWithoutSecretValues() throws Exception {
        ConfigurationSnapshot first = ConfigurationSnapshot.create("demo", 1, "v1", Instant.parse("2026-08-12T00:00:00Z"),
                java.util.List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(18080))));
        SecretReference databasePassword = new SecretReference("database-password", 1);
        SecretReference rotatedPassword = new SecretReference("database-password", 2);
        StoredApplicationSecretRevision firstSecret = new StoredApplicationSecretRevision(databasePassword,
                "application-secret/database-password/1", CredentialStorageMode.MASTER_PASSWORD,
                Instant.parse("2026-08-12T00:00:00Z"));

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory)) {
            database.configurations().save(first);
            database.configurations().save(first);
            assertEquals(first, database.configurations().find("demo", 1).orElseThrow());

            ConfigurationSnapshot replacement = ConfigurationSnapshot.create("demo", 1, "v1",
                    Instant.parse("2026-08-12T00:00:01Z"),
                    java.util.List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(19090))));
            assertThrows(java.sql.SQLException.class, () -> database.configurations().save(replacement));

            database.applicationSecrets().saveRevision(firstSecret);
            database.applicationSecrets().saveRevision(firstSecret);
            database.applicationSecrets().saveRevision(new StoredApplicationSecretRevision(rotatedPassword,
                    "application-secret/database-password/2", CredentialStorageMode.MASTER_PASSWORD,
                    Instant.parse("2026-08-12T00:00:01Z")));
            assertTrue(database.applicationSecrets().findRelease("demo", "release-a").isEmpty());
            database.applicationSecrets().bindRelease("demo", "release-a", java.util.List.of(databasePassword));
            database.applicationSecrets().bindRelease("demo", "release-empty", List.of());

            assertEquals(firstSecret, database.applicationSecrets().findRevision(databasePassword).orElseThrow());
            assertEquals(List.of(databasePassword), database.applicationSecrets()
                    .findRelease("demo", "release-a").orElseThrow());
            assertEquals(List.of(), database.applicationSecrets()
                    .findRelease("demo", "release-empty").orElseThrow());
            assertTrue(database.applicationSecrets().isReferenced(databasePassword));
            assertThrows(java.sql.SQLException.class, () -> database.applicationSecrets().bindRelease(
                    "demo", "release-a", java.util.List.of(rotatedPassword)));
            assertEquals(List.of(databasePassword), database.applicationSecrets()
                    .findRelease("demo", "release-a").orElseThrow());
        }
    }

    @Test
    void rollsBackAllSuccessfulReleaseStateWhenASecretBindingCannotBeRecorded() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22, "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("atomic-demo", server, "a".repeat(64));
        ManagedApplicationRuntimeConfiguration runtime = new ManagedApplicationRuntimeConfiguration(
                new HealthCheck.Tcp(19080, 15, 2), Optional.empty());
        CurrentRelease release = new CurrentRelease(application.id(), "b".repeat(64), Instant.now());
        SecretReference missing = new SecretReference("unregistered-secret", 1);

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("atomic-failure"))) {
            assertThrows(java.sql.SQLException.class, () -> database.managedApplications()
                    .recordSuccessfulDeployment(application, runtime, release,
                            configuration(application.id()), java.util.List.of(missing)));

            assertTrue(database.managedApplications().find(application.id()).isEmpty());
            assertTrue(database.managedApplications().findRuntime(application.id()).isEmpty());
            assertTrue(database.managedApplications().findRelease(application.id()).isEmpty());
            assertTrue(database.configurations().find(application.id(), 1).isEmpty());
            assertTrue(database.configurations().findRelease(application.id(), release.releaseSha256()).isEmpty());
            assertTrue(database.applicationSecrets()
                    .findRelease(application.id(), release.releaseSha256()).isEmpty());
            assertTrue(!database.applicationSecrets().isReferenced(missing));
        }
    }

    @Test
    void keepsSuccessfulReleaseConfigurationBindingImmutable() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22,
                "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forManaged("configured-demo", server, "a".repeat(64));
        ManagedApplicationRuntimeConfiguration runtime = new ManagedApplicationRuntimeConfiguration(
                new HealthCheck.Tcp(19080, 15, 2), Optional.empty());
        CurrentRelease release = new CurrentRelease(application.id(), "b".repeat(64), Instant.now());
        ConfigurationSnapshot first = configuration(application.id());
        ConfigurationSnapshot replacement = ConfigurationSnapshot.create(application.id(), 2, "v1",
                Instant.parse("2026-08-22T00:00:01Z"), List.of(
                        new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                                new ConfigurationValue.Number(19090))));

        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory.resolve("configuration-binding"))) {
            database.managedApplications().recordSuccessfulDeployment(
                    application, runtime, release, first, List.of());
            assertEquals(first, database.configurations()
                    .findRelease(application.id(), release.releaseSha256()).orElseThrow());

            assertThrows(java.sql.SQLException.class, () -> database.managedApplications()
                    .recordSuccessfulDeployment(application, runtime, release, replacement, List.of()));
            assertEquals(first, database.configurations()
                    .findRelease(application.id(), release.releaseSha256()).orElseThrow());
            assertTrue(database.configurations().find(application.id(), 2).isEmpty());
        }
    }

    @Test
    void keepsNamedAiProviderProfilesIndependentFromTheLegacyDefaultProfile() throws Exception {
        try (DesktopPersistence database = DesktopPersistence.open(temporaryDirectory)) {
            database.aiProfiles().saveDefault(new StoredAiProfile("https://default.example.test/v1", "default-model",
                    "ai/default", "MASTER_PASSWORD"));
            database.aiProfiles().saveNamed(new StoredAiProviderProfile("analysis", "https://analysis.example.test/v1",
                    "analysis-model", "ai/analysis", "WINDOWS_CREDENTIAL_MANAGER"));
            database.aiProfiles().saveNamed(new StoredAiProviderProfile("review", "https://review.example.test/v1",
                    "review-model", "ai/review", "MASTER_PASSWORD"));
            database.aiProfiles().saveRoleAssignment(new StoredAiRoleAssignment("PROJECT_ANALYSIS", "analysis"));
            database.aiProfiles().saveRoleAssignment(new StoredAiRoleAssignment("DEPLOYMENT_RISK_REVIEW", "review"));

            assertEquals("default-model", database.aiProfiles().findDefault().orElseThrow().model());
            assertEquals(java.util.List.of("analysis", "review"), database.aiProfiles().listNamed().stream()
                    .map(StoredAiProviderProfile::id).toList());
            assertEquals("analysis", database.aiProfiles().findNamed("analysis").orElseThrow().id());
            assertEquals(java.util.List.of("review", "analysis"), database.aiProfiles().listRoleAssignments().stream()
                    .map(StoredAiRoleAssignment::profileId).toList());
            assertThrows(java.sql.SQLException.class, () -> database.aiProfiles()
                    .saveRoleAssignment(new StoredAiRoleAssignment("ERROR_EXPLANATION", "missing")));
        }
    }

    private static ConfigurationSnapshot configuration(String applicationId) {
        return ConfigurationSnapshot.create(applicationId, 1, "v1",
                Instant.parse("2026-08-22T00:00:00Z"), List.of(
                        new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                                new ConfigurationValue.Number(18080))));
    }
}

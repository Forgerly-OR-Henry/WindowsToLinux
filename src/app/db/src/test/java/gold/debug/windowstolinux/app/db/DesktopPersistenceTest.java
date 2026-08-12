package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPersistenceTest {
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
            assertEquals("b".repeat(64), database.managedApplications().findRelease("demo").orElseThrow().artifactSha256());
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
            assertEquals("b".repeat(64), database.managedApplications().findRelease(application.id()).orElseThrow().artifactSha256());

            ManagedApplicationRuntimeConfiguration tcp = new ManagedApplicationRuntimeConfiguration(
                    new HealthCheck.Tcp(19092, 15, 2), Optional.empty()
            );
            database.managedApplications().recordSuccessfulDeployment(application, tcp,
                    new CurrentRelease(application.id(), "c".repeat(64), initialTime.plusSeconds(1)));

            assertEquals(tcp, database.managedApplications().findRuntime(application.id()).orElseThrow());
            assertEquals("c".repeat(64), database.managedApplications().findRelease(application.id()).orElseThrow().artifactSha256());
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
            database.applicationSecrets().bindRelease("demo", "release-a", java.util.List.of(databasePassword));

            assertEquals(firstSecret, database.applicationSecrets().findRevision(databasePassword).orElseThrow());
            assertTrue(database.applicationSecrets().isReferenced(databasePassword));
            assertThrows(java.sql.SQLException.class, () -> database.applicationSecrets().bindRelease(
                    "demo", "release-a", java.util.List.of(rotatedPassword)));
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

            assertEquals("default-model", database.aiProfiles().findDefault().orElseThrow().model());
            assertEquals(java.util.List.of("analysis", "review"), database.aiProfiles().listNamed().stream()
                    .map(StoredAiProviderProfile::id).toList());
        }
    }
}

package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;

import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopDatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOnlyTheLastRemoteObservationAsHistory() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "example.test", 22, "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forPhaseOne("demo", server, "a".repeat(64));
        LifecycleObservation observation = new LifecycleObservation(
                application, RuntimeState.RUNNING, AutostartState.ENABLED, true, Instant.now(), "remote observation"
        );

        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory)) {
            database.saveManagedApplication(application);
            database.saveCurrentRelease(new CurrentRelease("demo", "b".repeat(64), Instant.now()));
            database.saveLastObservation(observation);
            database.saveOpaqueSecret(new OpaqueSecret("ssh/server-one", "test", new byte[]{1}, new byte[]{2}, new byte[]{3}));
            database.saveServerProfile(new StoredServerProfile("server-one", "example.test", 22, "deployer", "ssh/server-one/password", "MASTER_PASSWORD"));
            database.saveAiProfile(new StoredAiProfile("https://example.test/v1/chat/completions", "gpt-5", "ai/default/api-key", "MASTER_PASSWORD"));

            assertEquals(server, database.findServer("server-one").orElseThrow());
            assertEquals(RuntimeState.RUNNING, database.findLastObservation(application).orElseThrow().runtimeState());
            assertTrue(database.findOpaqueSecret("ssh/server-one").isPresent());
            assertEquals("deployer", database.findServerProfile("server-one").orElseThrow().username());
            assertEquals("gpt-5", database.findAiProfile().orElseThrow().model());
            assertEquals(application, database.findManagedApplication("demo").orElseThrow());
            assertEquals("b".repeat(64), database.findCurrentRelease("demo").orElseThrow().artifactSha256());
            assertEquals(1, database.listManagedApplications().size());
        }
    }

    @Test
    void storesNonSecretDesktopPreferences() throws Exception {
        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory)) {
            assertTrue(database.findDesktopPreference(DesktopDatabase.UI_LOCALE_SETTING).isEmpty());
            assertTrue(database.findDesktopPreference(DesktopDatabase.UI_THEME_SETTING).isEmpty());

            database.saveDesktopPreference(DesktopDatabase.UI_LOCALE_SETTING, "zh-CN");
            database.saveDesktopPreference(DesktopDatabase.UI_THEME_SETTING, "SYSTEM");

            assertEquals("zh-CN", database.findDesktopPreference(DesktopDatabase.UI_LOCALE_SETTING).orElseThrow());
            assertEquals("SYSTEM", database.findDesktopPreference(DesktopDatabase.UI_THEME_SETTING).orElseThrow());
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

        try (DesktopDatabase database = DesktopDatabase.open(dataDirectory)) {
            database.saveDesktopPreference(DesktopDatabase.UI_THEME_SETTING, "DARK");
            assertEquals("DARK", database.findDesktopPreference(DesktopDatabase.UI_THEME_SETTING).orElseThrow());
        }
    }

    @Test
    void recordsTheSuccessfulRuntimeContractAndReleaseAtomically() throws Exception {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22, "SHA256:exampleFingerprint");
        ManagedApplication application = ManagedApplication.forPhaseOne("demo", server, "a".repeat(64));
        ManagedApplicationRuntimeConfiguration http = new ManagedApplicationRuntimeConfiguration(
                new HealthCheck.Http(URI.create("http://127.0.0.1:18080/actuator/health"), 200, 20),
                Optional.of(new UserAccessUrl(URI.create("http://198.51.100.24:18080/")))
        );
        Instant initialTime = Instant.parse("2026-08-10T00:00:00Z");

        try (DesktopDatabase database = DesktopDatabase.open(temporaryDirectory)) {
            database.recordSuccessfulDeployment(application, http,
                    new CurrentRelease(application.id(), "b".repeat(64), initialTime));

            assertEquals(http, database.findManagedApplicationRuntimeConfiguration(application.id()).orElseThrow());
            assertEquals("b".repeat(64), database.findCurrentRelease(application.id()).orElseThrow().artifactSha256());

            ManagedApplicationRuntimeConfiguration tcp = new ManagedApplicationRuntimeConfiguration(
                    new HealthCheck.Tcp(19092, 15, 2), Optional.empty()
            );
            database.recordSuccessfulDeployment(application, tcp,
                    new CurrentRelease(application.id(), "c".repeat(64), initialTime.plusSeconds(1)));

            assertEquals(tcp, database.findManagedApplicationRuntimeConfiguration(application.id()).orElseThrow());
            assertEquals("c".repeat(64), database.findCurrentRelease(application.id()).orElseThrow().artifactSha256());
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

        try (DesktopDatabase database = DesktopDatabase.open(dataDirectory)) {
            assertTrue(database.findManagedApplication("demo").isPresent());
            assertTrue(database.findManagedApplicationRuntimeConfiguration("demo").isEmpty(),
                    "旧记录不允许根据观测或 UI 默认值伪造运行配置");
        }
    }
}

package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSchemaTest {
    @TempDir
    Path directory;

    private Connection connection() throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("windowstolinux.db"));
        connection.createStatement().execute("PRAGMA foreign_keys=ON");
        return connection;
    }

    @Test
    void createsExactlyNineteenTablesAndPreservesPreferencesOnReopen() throws Exception {
        Set<String> expected = Set.of("security_profile", "credential", "server_profile", "deployment_unit",
                "managed_application", "managed_application_component", "managed_application_dependency",
                "external_application", "deployment_unit_configuration_snapshot", "deployment_unit_configuration_entry",
                "deployment_unit_release_binding", "secret_revision", "deployment_unit_release_secret_reference",
                "ai_model", "browser_recovery_task", "browser_recovery_event", "deployment_agent_task",
                "deployment_agent_event", "desktop_preference");
        try (var db = DesktopPersistence.open(directory); var c = connection(); var s = c.createStatement()) {
            Set<String> actual = new HashSet<>();
            try (var r = s
                    .executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                while (r.next())
                    actual.add(r.getString(1));
            }
            assertEquals(expected, actual);
            try (var r = s.executeQuery("PRAGMA user_version")) {
                assertTrue(r.next());
                assertEquals(21, r.getInt(1));
            }
            db.preferences().save("ui_locale", "zh-CN");
        }
        try (var db = DesktopPersistence.open(directory)) {
            assertEquals("zh-CN", db.preferences().find("ui_locale").orElseThrow());
        }
    }

    @Test
    void rejectsOldFutureAndAlteredDatabasesWithoutDeletingData() throws Exception {
        for (int version : List.of(0, 20, 22)) {
            Path path = directory.resolve("v" + version);
            java.nio.file.Files.createDirectories(path);
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + path.resolve("windowstolinux.db"));
                    var s = c.createStatement()) {
                s.execute("CREATE TABLE sentinel(value TEXT)");
                s.execute("INSERT INTO sentinel VALUES('retained')");
                s.execute("PRAGMA user_version=" + version);
            }
            assertThrows(SQLException.class, () -> DesktopPersistence.open(path));
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + path.resolve("windowstolinux.db"));
                    var s = c.createStatement();
                    var r = s.executeQuery("SELECT value FROM sentinel")) {
                assertTrue(r.next());
                assertEquals("retained", r.getString(1));
            }
        }
        try (var ignored = DesktopPersistence.open(directory); var c = connection(); var s = c.createStatement()) {
            s.execute("ALTER TABLE ai_model ADD COLUMN unexpected TEXT");
        }
        assertThrows(SQLException.class, () -> DesktopPersistence.open(directory));
    }

    @Test
    void enforcesCredentialReferencesNonceUniquenessAndNullGroups() throws Exception {
        try (var db = DesktopPersistence.open(directory); var c = connection(); var s = c.createStatement()) {
            DatabaseTestFixture.server(db);
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM credential"));
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM security_profile"));
            assertThrows(SQLException.class, () -> s.execute("UPDATE server_profile SET host_key_format=NULL"));
            assertThrows(SQLException.class, () -> s.execute("UPDATE security_profile SET is_encryption_enabled=2"));
            s.execute("INSERT INTO credential VALUES('encrypted-a',1,'AES256_GCM_V1',zeroblob(24),zeroblob(12))");
            assertThrows(SQLException.class, () -> s.execute(
                    "INSERT INTO credential VALUES('encrypted-b',1,'AES256_GCM_V1',zeroblob(24),zeroblob(12))"));
            assertThrows(SQLException.class, () -> s
                    .execute("INSERT INTO credential VALUES('unknown',1,'AES_FUTURE',zeroblob(24),randomblob(12))"));
            assertThrows(SQLException.class, () -> s.execute(
                    "INSERT INTO browser_recovery_event(task_id,task_state,event_code,created_at) VALUES('missing','PAUSED','paused',1)"));
        }
    }

    @Test
    void initializesOnlyMissingDeploymentPreferencesAndRejectsInvalidValues() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            assertEquals(Map.of("deploy_mode", "ASSISTED", "deploy_approval", "AUTOMATIC"),
                    db.preferences().deploymentSelection());
            db.preferences().saveAll(Map.of("deploy_mode", "STATIC", "deploy_approval", "FULL_CONTROL"));
            assertEquals("STATIC", db.preferences().deploymentSelection().get("deploy_mode"));
            db.preferences().save("deploy_mode", "INVALID");
            assertThrows(SQLException.class, () -> db.preferences().deploymentSelection());
            assertEquals("INVALID", db.preferences().find("deploy_mode").orElseThrow());
        }
    }

    @Test
    void wholePreferenceUpdateRollsBackOnFailure() throws Exception {
        try (var db = DesktopPersistence.open(directory); var c = connection(); var s = c.createStatement()) {
            var original = db.preferences().deploymentSelection();
            s.execute(
                    "CREATE TRIGGER reject_approval BEFORE UPDATE ON desktop_preference WHEN NEW.preference_key='deploy_approval' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            var changed = new LinkedHashMap<String, String>();
            changed.put("deploy_mode", "STATIC");
            changed.put("deploy_approval", "FULL_CONTROL");
            assertThrows(SQLException.class, () -> db.preferences().saveAll(changed));
            assertEquals(original, db.preferences().deploymentSelection());
            s.execute("DROP TRIGGER reject_approval");
        }
    }

    @Test
    void deferredHealthReferenceAllowsTopologyReplacementAndProtectsHistory() throws Exception {
        try (var db = DesktopPersistence.open(directory); var c = connection(); var sql = c.createStatement()) {
            DatabaseTestFixture.server(db);
            for (String id : List.of("first", "second"))
                sql.execute(
                        "INSERT INTO deployment_unit(deployment_unit_id,server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256) VALUES('"
                                + id + "','server','" + id + ".service','/srv/" + id + "','digest')");
            c.setAutoCommit(false);
            sql.execute("INSERT INTO managed_application VALUES('app','server','old',NULL,1)");
            sql.execute(
                    "INSERT INTO managed_application_component(application_id,component_id,deployment_unit_id) VALUES('app','old','first')");
            c.commit();
            sql.execute("DELETE FROM managed_application_component WHERE application_id='app'");
            sql.execute("UPDATE managed_application SET health_component_id='replacement' WHERE application_id='app'");
            sql.execute(
                    "INSERT INTO managed_application_component(application_id,component_id,deployment_unit_id) VALUES('app','replacement','second')");
            c.commit();
            sql.execute("UPDATE managed_application SET health_component_id='missing' WHERE application_id='app'");
            assertThrows(SQLException.class, c::commit);
            c.rollback();
            try (var rows = sql.executeQuery("SELECT health_component_id FROM managed_application")) {
                assertTrue(rows.next());
                assertEquals("replacement", rows.getString(1));
            }
            assertThrows(SQLException.class,
                    () -> sql.execute("DELETE FROM server_profile WHERE server_profile_id='server'"));
            c.rollback();
            assertThrows(SQLException.class,
                    () -> sql.execute("DELETE FROM deployment_unit WHERE deployment_unit_id='second'"));
            c.rollback();
        }
    }
}

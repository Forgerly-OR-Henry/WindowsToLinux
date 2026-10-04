package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.error.DesktopStorageException;
import gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSchemaTest {
    @TempDir
    Path directory;

    @Test
    void createsExactlyTwentyPlatformTablesAndPreservesPreferencesOnReopen() throws Exception {
        var expected = Set.of("security_profile", "credential", "server_profile", "ai_model",
            "browser_recovery_task", "browser_recovery_event", "desktop_preference", "app_state",
            "file_link", "file_operation", "file_operation_step",
            "mod_registry", "mod_version", "mod_schema", "mod_operation", "mod_migration_step",
            "platform_task", "platform_task_resource", "mod_credential_reference", "mod_server_reference",
            "app_schema_history", "log_category", "runtime_log");
        try (var db = DatabaseTestFixture.open(directory); var c = db.database().openConnection(); var s = c.createStatement()) {
            var actual = new HashSet<String>();
            try (var r = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='app' AND table_type='BASE TABLE'")) {
                while (r.next()) actual.add(r.getString(1));
            }
            assertEquals(expected, actual);
            try (var r = s.executeQuery("SELECT schema_version,state FROM app_schema_history")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt(1));
                assertEquals("READY", r.getString(2));
                assertFalse(r.next());
            }
            db.preferences().save("ui_locale", "zh-CN");
        }
        try (var db = DatabaseTestFixture.open(directory)) {
            assertEquals("zh-CN", db.preferences().find("ui_locale").orElseThrow());
        }
    }

    @Test
    void rejectsOldFutureAndAlteredDatabasesWithoutDeletingData() throws Exception {
        for (int version : List.of(0, 2)) {
            var path = directory.resolve("v" + version);
            try (var db = DatabaseTestFixture.open(path); var c = db.database().openConnection(); var s = c.createStatement()) {
                s.execute("INSERT INTO desktop_preference VALUES('sentinel','retained')");
                if (version == 0) s.execute("DELETE FROM app_schema_history");
                else s.execute("UPDATE app_schema_history SET schema_version=" + version);
            }
            assertEquals(DesktopStorageFailureType.SCHEMA_INCOMPATIBLE.code(),
                assertThrows(DesktopStorageException.class, () -> DatabaseTestFixture.open(path)).problem().eventCode());
            try (var material = gold.debug.windowstolinux.app.db.init.DatabaseBootstrap.load(path, PlatformDatabaseTest.TEST_PROTECTION);
                 var c = java.sql.DriverManager.getConnection("jdbc:h2:" + path.resolve("windowstolinux") + ";DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE", "app_internal", material.password());
                 var s = c.createStatement(); var r = s.executeQuery("SELECT preference_value FROM app.desktop_preference WHERE preference_key='sentinel'")) {
                assertTrue(r.next());
                assertEquals("retained", r.getString(1));
            }
        }
        try (var db = DatabaseTestFixture.open(directory); var c = db.database().openConnection(); var s = c.createStatement()) {
            s.execute("ALTER TABLE ai_model ADD COLUMN unexpected VARCHAR");
        }
        assertEquals(DesktopStorageFailureType.SCHEMA_INCOMPATIBLE.code(),
            assertThrows(DesktopStorageException.class, () -> DatabaseTestFixture.open(directory)).problem().eventCode());
    }

    @Test
    void enforcesCredentialReferencesNonceUniquenessAndNullGroups() throws Exception {
        try (var db = DatabaseTestFixture.open(directory); var c = db.database().openConnection(); var s = c.createStatement()) {
            DatabaseTestFixture.server(db);
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM credential"));
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM security_profile"));
            assertThrows(SQLException.class, () -> s.execute("UPDATE server_profile SET host_key_format=NULL"));
            assertThrows(SQLException.class, () -> s.execute("UPDATE security_profile SET is_encryption_enabled=2"));
            try (var p = c.prepareStatement("INSERT INTO credential VALUES(?,1,?,?,?)")) {
                p.setString(1, "encrypted-a");
                p.setString(2, "AES256_GCM_V1");
                p.setBytes(3, new byte[24]);
                p.setBytes(4, new byte[12]);
                assertEquals(1, p.executeUpdate());
                p.setString(1, "encrypted-b");
                assertThrows(SQLException.class, p::executeUpdate);
                p.setString(1, "unknown");
                p.setString(2, "AES_FUTURE");
                p.setBytes(4, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
                assertThrows(SQLException.class, p::executeUpdate);
            }
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO browser_recovery_event(task_id,task_state,event_code,created_at) VALUES('missing','PAUSED','paused',1)"));
        }
    }

    @Test
    void wholePreferenceUpdateRollsBackOnFailure() throws Exception {
        try (var db = DatabaseTestFixture.open(directory); var c = db.database().openConnection(); var s = c.createStatement()) {
            db.preferences().saveAll(Map.of("ui_locale", "zh-CN", "ui_theme", "SYSTEM"));
            s.execute("ALTER TABLE desktop_preference ADD CONSTRAINT fixture_reject_theme CHECK(preference_key<>'ui_theme' OR preference_value<>'DARK')");
            var changed = new LinkedHashMap<String, String>();
            changed.put("ui_locale", "en");
            changed.put("ui_theme", "DARK");
            assertThrows(SQLException.class, () -> db.preferences().saveAll(changed));
            assertEquals("zh-CN", db.preferences().find("ui_locale").orElseThrow());
            assertEquals("SYSTEM", db.preferences().find("ui_theme").orElseThrow());
            s.execute("ALTER TABLE desktop_preference DROP CONSTRAINT fixture_reject_theme");
        }
    }

}

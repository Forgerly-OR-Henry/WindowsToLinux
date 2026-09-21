package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class ServerProfileRepositoryTest {
    @TempDir Path directory;
    private final StoredServerProfile old = new StoredServerProfile("first", "example.test", 22, "tester", "ssh/first/password", "MASTER_PASSWORD");

    @Test void migratesVersionTwelveAndPreservesNamesChecksAndStableIdentityAcrossReopen() throws Exception {
        try (var db = DesktopPersistence.open(directory)) { db.servers().saveServerProfile(old); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("windowstolinux.db")); var statement = connection.createStatement()) {
            for (String column : java.util.List.of("display_name", "last_checked", "connected", "operating_system"))
                statement.execute("ALTER TABLE server_profile DROP COLUMN " + column);
            for(String table:java.util.List.of("deployment_agent_event","deployment_agent_task","ai_model_purpose","ai_model_verification","ai_model_inventory"))
                statement.execute("DROP TABLE "+table);
            statement.execute("PRAGMA user_version=12");
        }
        StoredServerProfile renamed = new StoredServerProfile(old.id(), old.host(), 22, old.username(), old.credentialKey(), old.credentialMode(), "Production EU");
        try (var db = DesktopPersistence.open(directory)) {
            assertEquals(old, db.servers().findServerProfile("first").orElseThrow());
            assertTrue(db.servers().observation("first").checkedAt().isEmpty());
            db.servers().recordObservation(old, true, "Ubuntu / amd64");
            db.servers().saveServerProfile(renamed);
        }
        try (var db = DesktopPersistence.open(directory)) {
            assertEquals(renamed, db.servers().listServerProfiles().getFirst());
            assertTrue(db.servers().observation("first").connected());
            assertTrue(db.servers().observation("first").checkedAt().isPresent());
            StoredServerProfile changed = new StoredServerProfile(old.id(), "other.test", 2222, old.username(), old.credentialKey(), old.credentialMode(), renamed.displayName());
            db.servers().saveServerProfile(changed);
            db.servers().recordObservation(old, false, "");
            assertTrue(db.servers().observation("first").checkedAt().isEmpty(), "late check must not attach to edited endpoint");
            db.servers().recordObservation(changed, false, "");
            assertFalse(db.servers().observation("first").connected());
            assertTrue(db.servers().observation("first").checkedAt().isPresent());
        }
    }
}

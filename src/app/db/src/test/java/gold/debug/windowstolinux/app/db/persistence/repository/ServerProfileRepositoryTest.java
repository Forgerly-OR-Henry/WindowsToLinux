package gold.debug.windowstolinux.app.db.persistence.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProfileRepositoryTest {
    @TempDir
    Path directory;

    private final StoredServerProfile old = new StoredServerProfile("first", "example.test", 22, "tester",
            "ssh/first/password");

    @Test
    void preservesNamesChecksAndStableIdentityAcrossReopen() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            gold.debug.windowstolinux.app.db.DatabaseTestFixture.credential(db, old.credentialKey());
            db.servers().saveServerProfile(old);
        }
        StoredServerProfile renamed = new StoredServerProfile(old.id(), old.host(), 22, old.username(),
                old.credentialKey(), "Production EU");
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
            StoredServerProfile changed = new StoredServerProfile(old.id(), "other.test", 2222, old.username(),
                    old.credentialKey(), renamed.displayName());
            db.servers().saveServerProfile(changed);
            db.servers().recordObservation(old, false, "");
            assertTrue(db.servers().observation("first").checkedAt().isEmpty(),
                    "late check must not attach to edited endpoint");
            db.servers().recordObservation(changed, false, "");
            assertFalse(db.servers().observation("first").connected());
            assertTrue(db.servers().observation("first").checkedAt().isPresent());
        }
    }
}

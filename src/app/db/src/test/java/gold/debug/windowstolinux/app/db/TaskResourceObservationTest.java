package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.db.task.TaskResourceObservation;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskResourceObservationTest {
    @TempDir
    Path root;

    @Test
    void onlyReleasedOrTaskEndLeasesAreIgnoredRegardlessOfKindAndOwner() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var observation = new TaskResourceObservation(db);
            var id = ledger.enqueue(UUID.randomUUID(), "owner", "1.0.0",
                new TaskRequest("fixture", "fixture", List.of("server"), true),
                List.of(new TaskResource("MOD", "owner", "owner", false),
                    new TaskResource("SERVER", "server", "owner", true)));
            assertThrows(SQLException.class, () -> observation.released(UUID.randomUUID()));
            assertTrue(ledger.tryStart(id));
            assertTrue(observation.released(id));

            var foreign = ledger.retain(id, new TaskResource("CUSTOM", "foreign-resource", "other-owner", false));
            assertFalse(observation.released(id));
            ledger.release(id, foreign);
            assertTrue(observation.released(id));

            var file = ledger.retain(id, new TaskResource("FILE", "local-file", "owner", false));
            setMetadata(db, file, "{\"release\":\"UNKNOWN\"}");
            assertFalse(observation.released(id));
            setMetadata(db, file, null);
            assertFalse(observation.released(id));
            try (var connection = db.openConnection();
                 var update = connection
                     .prepareStatement("UPDATE platform_task_resource SET released_at=1 WHERE lease_id=?")) {
                update.setObject(1, file);
                assertEquals(1, update.executeUpdate());
            }
            assertTrue(observation.released(id));
        }
    }

    private static void setMetadata(PlatformDatabase db, UUID leaseId, String metadata) throws SQLException {
        try (var connection = db.openConnection()) {
            if (metadata == null)
                try (var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE platform_task_resource ALTER COLUMN recovery_json SET NULL");
                }
            try (var update = connection
                .prepareStatement("UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=?")) {
                update.setString(1, metadata);
                update.setObject(2, leaseId);
                assertEquals(1, update.executeUpdate());
            }
        }
    }
}

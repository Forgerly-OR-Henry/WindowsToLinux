package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.db.task.TaskAuthorCheckpoint;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskAuthorCheckpointTest {
    private static final String OWNER = "example.owner";

    private static final String VERSION = "1.0.0";

    @TempDir
    Path root;

    private static UUID started(PlatformDatabase db) throws SQLException {
        var ledger = new TaskLedger(db);
        var id = ledger.enqueue(UUID.randomUUID(), OWNER, VERSION,
            new TaskRequest("fixture", "Author checkpoint", List.of(), true),
            List.of(new TaskResource("MOD", OWNER, OWNER, false)));
        assertTrue(ledger.tryStart(id));
        return id;
    }

    @Test
    void recoveryCheckpointRetainsDistinctProtocolAndExactCasAcrossReopen() throws Exception {
        UUID task;
        UUID authorLease;
        UUID recoveryLease;
        var json = new ObjectMapper();
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            task = started(db);
            var author = new TaskAuthorCheckpoint(db);
            var recovery = TaskAuthorCheckpoint.recovery(db);
            authorLease = author.begin(task, OWNER, VERSION, "progress", "{\"origin\":\"author\"}").lease();
            assertTrue(recovery.find(task, OWNER, VERSION, "progress").isEmpty());
            var entry = recovery.begin(task, OWNER, VERSION, "progress", "{\"phase\":\"INTENT\"}");
            recoveryLease = entry.lease();
            assertNotEquals(authorLease, recoveryLease);
            var committed = recovery.advance(task, OWNER, VERSION, "progress", recoveryLease, entry.snapshot(), "{\"phase\":\"DISPATCHED\"}");
            assertEquals(1, committed.revision());
            assertThrows(TaskAuthorCheckpoint.RejectedException.class,
                () -> recovery.advance(task, OWNER, VERSION, "progress", recoveryLease, entry.snapshot(), "{}"));
            assertThrows(SQLException.class,
                () -> recovery.advance(task, OWNER, VERSION, "progress", authorLease, committed, "{}"));
            assertThrows(SQLException.class, () -> recovery.find(task, OWNER, "2.0.0", "progress"));
            assertThrows(SQLException.class, () -> recovery.begin(task, "foreign.owner", VERSION, "progress", "{}"));
            assertThrows(SQLException.class, () -> recovery.release(task, OWNER, VERSION, "progress", authorLease));
            for (String invalid : List.of("{\"x\":1,\"x\":2}", "{} {}", "{\"x\":\"" + "中".repeat(4001) + "\"}"))
                assertThrows(TaskAuthorCheckpoint.RejectedException.class,
                    () -> recovery.begin(task, OWNER, VERSION, "invalid", invalid));
            try (var connection = db.openConnection();
                 var query = connection.prepareStatement("SELECT resource_id,recovery_json FROM platform_task_resource WHERE lease_id=?")) {
                for (UUID lease : List.of(authorLease, recoveryLease)) {
                    query.setObject(1, lease);
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next());
                        boolean original = lease.equals(authorLease);
                        assertEquals(OWNER + (original ? "/checkpoint/" : "/recovery-checkpoint/") + "progress", row.getString(1));
                        assertEquals(original ? "author-checkpoint.v1" : "recovery-checkpoint.v1", ResourceRecovery.decode(row.getString(2)).protocol());
                    }
                }
            }
            new TaskLedger(db).retain(task, new TaskResource("CUSTOM", OWNER + "/recovery-checkpoint/impostor", OWNER, false));
            assertThrows(SQLException.class, () -> recovery.find(task, OWNER, VERSION, "impostor"), "Raw EXPLICIT is not a host envelope or absence");
        }
        try (var reopened = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var author = new TaskAuthorCheckpoint(reopened);
            var recovery = TaskAuthorCheckpoint.recovery(reopened);
            var entry = recovery.find(task, OWNER, VERSION, "progress").orElseThrow();
            assertEquals(recoveryLease, entry.lease());
            assertEquals(new TaskCheckpoint.Snapshot(1, "{\"phase\":\"DISPATCHED\"}", false), entry.snapshot());
            assertEquals(authorLease, author.find(task, OWNER, VERSION, "progress").orElseThrow().lease());
            assertEquals("{\"origin\":\"author\"}", author.find(task, OWNER, VERSION, "progress").orElseThrow().snapshot().payload());
            String encoded;
            try (var connection = reopened.openConnection(); var query = connection.prepareStatement("SELECT recovery_json FROM platform_task_resource WHERE lease_id=?")) {
                query.setObject(1, recoveryLease);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    encoded = row.getString(1);
                }
            }
            var extra = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(encoded);
            ((com.fasterxml.jackson.databind.node.ObjectNode) extra.path("fields")).put("authority", "APPROVE");
            for (String invalid : List.of(encoded.replace("recovery-checkpoint.v1", "author-checkpoint.v1"),
                "{\"release\":\"EXPLICIT\"}", encoded + " {}", encoded.replace("\"release\":", "\"release\":\"EXPLICIT\",\"release\":"), extra.toString())) {
                try (var connection = reopened.openConnection(); var update = connection.prepareStatement("UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=?")) {
                    update.setString(1, invalid);
                    update.setObject(2, recoveryLease);
                    assertEquals(1, update.executeUpdate());
                }
                assertThrows(SQLException.class, () -> recovery.find(task, OWNER, VERSION, "progress"));
                assertThrows(SQLException.class, () -> recovery.release(task, OWNER, VERSION, "progress", recoveryLease));
            }
            try (var connection = reopened.openConnection(); var update = connection.prepareStatement("UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=?")) {
                update.setString(1, encoded);
                update.setObject(2, recoveryLease);
                assertEquals(1, update.executeUpdate());
            }
            recovery.release(task, OWNER, VERSION, "progress", recoveryLease);
            var released = recovery.find(task, OWNER, VERSION, "progress").orElseThrow().snapshot();
            assertTrue(released.released());
            assertThrows(SQLException.class, () -> recovery.advance(task, OWNER, VERSION, "progress", recoveryLease, released, "{}"));
            assertThrows(SQLException.class, () -> recovery.begin(task, OWNER, VERSION, "progress", "{}"));
            assertFalse(author.find(task, OWNER, VERSION, "progress").orElseThrow().snapshot().released());
        }
    }

    @Test
    void exactValueCasAndSingleUseKeySurviveRelease() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            UUID task = started(db);
            var checkpoints = new TaskAuthorCheckpoint(db);
            var initial = checkpoints.begin(task, OWNER, VERSION, "publication", "{\"phase\":\"prepared\"}");
            assertEquals(new TaskCheckpoint.Snapshot(0, "{\"phase\":\"prepared\"}", false), initial.snapshot());
            assertEquals(initial, checkpoints.find(task, OWNER, VERSION, "publication").orElseThrow());
            assertThrows(SQLException.class,
                () -> checkpoints.begin(task, OWNER, VERSION, "publication", "{\"phase\":\"duplicate\"}"));
            assertThrows(SQLException.class,
                () -> checkpoints.advance(task, OWNER, VERSION, "publication", initial.lease(),
                    new TaskCheckpoint.Snapshot(0, "{\"phase\":\"forged\"}", false),
                    "{\"phase\":\"committed\"}"));
            var committed = checkpoints.advance(task, OWNER, VERSION, "publication", initial.lease(),
                initial.snapshot(), "{\"phase\":\"committed\"}");
            assertEquals(new TaskCheckpoint.Snapshot(1, "{\"phase\":\"committed\"}", false), committed);
            assertThrows(SQLException.class, () -> checkpoints.advance(task, OWNER, VERSION, "publication",
                initial.lease(), initial.snapshot(), "{\"phase\":\"stale\"}"));
            assertEquals(committed, checkpoints.find(task, OWNER, VERSION, "publication").orElseThrow().snapshot());

            checkpoints.release(task, OWNER, VERSION, "publication", initial.lease());
            assertEquals(new TaskCheckpoint.Snapshot(1, committed.payload(), true),
                checkpoints.find(task, OWNER, VERSION, "publication").orElseThrow().snapshot());
            assertThrows(SQLException.class, () -> checkpoints.advance(task, OWNER, VERSION, "publication",
                initial.lease(), committed, "{\"phase\":\"after-release\"}"));
            assertThrows(SQLException.class, () -> checkpoints.begin(task, OWNER, VERSION, "publication", "{}"));
        }
    }

    @Test
    void allOperationsRequireOriginalTaskOwnerVersionAndLease() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            UUID task = started(db);
            var checkpoints = new TaskAuthorCheckpoint(db);
            assertThrows(SQLException.class, () -> checkpoints.begin(task, "other-owner", VERSION, "foreign", "{}"));
            assertThrows(SQLException.class, () -> checkpoints.begin(task, OWNER, "2.0.0", "wrong-version", "{}"));
            assertTrue(checkpoints.find(task, OWNER, VERSION, "foreign").isEmpty());
            var original = checkpoints.begin(task, OWNER, VERSION, "publication", "{}");
            assertThrows(SQLException.class, () -> checkpoints.find(task, "other-owner", VERSION, "publication"));
            assertThrows(SQLException.class, () -> checkpoints.find(task, OWNER, "2.0.0", "publication"));
            assertThrows(SQLException.class, () -> checkpoints.advance(task, "other-owner", VERSION, "publication",
                original.lease(), original.snapshot(), "{\"step\":1}"));
            assertThrows(SQLException.class, () -> checkpoints.advance(task, OWNER, "2.0.0", "publication",
                original.lease(), original.snapshot(), "{\"step\":1}"));
            assertThrows(SQLException.class,
                () -> checkpoints.release(task, OWNER, VERSION, "publication", UUID.randomUUID()));
            assertThrows(SQLException.class,
                () -> checkpoints.release(task, "other-owner", VERSION, "publication", original.lease()));
            assertFalse(checkpoints.find(task, OWNER, VERSION, "publication").orElseThrow().snapshot().released());
            checkpoints.release(task, OWNER, VERSION, "publication", original.lease());
        }
    }

    @Test
    void releasedCheckpointRemainsReadableAcrossH2ReopenUntilTaskArchive() throws Exception {
        UUID task;
        UUID lease;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            task = started(db);
            var checkpoints = new TaskAuthorCheckpoint(db);
            var initial = checkpoints.begin(task, OWNER, VERSION, "publication", "{\"state\":0}");
            lease = initial.lease();
            checkpoints.advance(task, OWNER, VERSION, "publication", lease, initial.snapshot(), "{\"state\":1}");
            checkpoints.release(task, OWNER, VERSION, "publication", lease);
        }
        try (var reopened = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var checkpoints = new TaskAuthorCheckpoint(reopened);
            var stored = checkpoints.find(task, OWNER, VERSION, "publication").orElseThrow();
            assertEquals(lease, stored.lease());
            assertEquals(new TaskCheckpoint.Snapshot(1, "{\"state\":1}", true), stored.snapshot());
            assertThrows(SQLException.class, () -> checkpoints.advance(task, OWNER, VERSION, "publication", lease,
                stored.snapshot(), "{\"state\":2}"));
        }
    }

    @Test
    void reorderedPersistedEnvelopeAdvancesAcrossH2ReopenWithoutWeakeningExactCas() throws Exception {
        UUID task;
        UUID lease;
        String initial = "{\"phase\":\"prepared\"}";
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            task = started(db);
            lease = new TaskAuthorCheckpoint(db).begin(task, OWNER, VERSION, "publication", initial).lease();
            try (var connection = db.openConnection();
                 var query = connection
                     .prepareStatement("SELECT recovery_json FROM platform_task_resource WHERE lease_id=?")) {
                query.setObject(1, lease);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    var stored = rows.getString(1);
                    var fields = new ObjectMapper().readTree(stored).path("fields");
                    var alternative = new LinkedHashMap<String, Object>();
                    alternative.put("fields", fields);
                    alternative.put("protocol", "author-checkpoint.v1");
                    alternative.put("version", 1);
                    alternative.put("release", "EXPLICIT");
                    var reordered = new ObjectMapper().writeValueAsString(alternative);
                    assertNotEquals(stored, reordered);
                    try (var update = connection
                        .prepareStatement("UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=?")) {
                        update.setString(1, reordered);
                        update.setObject(2, lease);
                        assertEquals(1, update.executeUpdate());
                    }
                }
            }
        }
        try (var reopened = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var checkpoints = new TaskAuthorCheckpoint(reopened);
            var original = checkpoints.find(task, OWNER, VERSION, "publication").orElseThrow();
            assertEquals(new TaskCheckpoint.Snapshot(0, initial, false), original.snapshot());
            assertThrows(TaskAuthorCheckpoint.RejectedException.class,
                () -> checkpoints.advance(task, OWNER, VERSION, "publication", lease,
                    new TaskCheckpoint.Snapshot(0, "{\"phase\":\"forged\"}", false), "{\"phase\":\"done\"}"));
            var advanced = checkpoints.advance(task, OWNER, VERSION, "publication", lease, original.snapshot(),
                "{\"phase\":\"done\"}");
            assertEquals(1, advanced.revision());
            assertEquals(advanced, checkpoints.find(task, OWNER, VERSION, "publication").orElseThrow().snapshot());
        }
    }

    @Test
    void boundedAuthorJsonCannotReplaceHostFactsAndMalformedStoredEnvelopeFailsClosed() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            UUID task = started(db);
            var checkpoints = new TaskAuthorCheckpoint(db);
            assertThrows(SQLException.class, () -> checkpoints.begin(task, OWNER, VERSION, "bad", "{\"x\":1,\"x\":2}"));
            assertThrows(SQLException.class, () -> checkpoints.begin(task, OWNER, VERSION, "bad", "{} {}"));
            assertThrows(SQLException.class,
                () -> checkpoints.begin(task, OWNER, VERSION, "bad", "{\"x\":\"" + "中".repeat(4001) + "\"}"));
            assertTrue(checkpoints.find(task, OWNER, VERSION, "bad").isEmpty());

            String payload = "{\"protocol\":\"forged\",\"ownerId\":\"other-owner\"}";
            var entry = checkpoints.begin(task, OWNER, VERSION, "publication", payload);
            String encoded;
            try (var connection = db.openConnection();
                 var query = connection
                     .prepareStatement("SELECT recovery_json FROM platform_task_resource WHERE lease_id=?")) {
                query.setObject(1, entry.lease());
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    encoded = row.getString(1);
                }
            }
            var host = ResourceRecovery.decode(encoded);
            assertEquals("author-checkpoint.v1", host.protocol());
            assertEquals(task.toString(), host.fields().get("taskId"));
            assertEquals(OWNER, host.fields().get("ownerId"));
            assertEquals(VERSION, host.fields().get("ownerVersion"));
            assertEquals(payload, host.fields().get("payload"));

            try (var connection = db.openConnection();
                 var update = connection
                     .prepareStatement("UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=?")) {
                update.setString(1, "{\"release\":\"EXPLICIT\"}");
                update.setObject(2, entry.lease());
                assertEquals(1, update.executeUpdate());
            }
            assertThrows(SQLException.class, () -> checkpoints.find(task, OWNER, VERSION, "publication"));
            assertThrows(SQLException.class,
                () -> checkpoints.release(task, OWNER, VERSION, "publication", entry.lease()));
        }
    }
}

package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.Set;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.db.task.TaskResourceObservation;
import gold.debug.windowstolinux.app.db.task.TaskAuthorCheckpoint;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskResourceObservationTest {
    @TempDir
    Path root;

    @Test
    void completeAuthorSetIncludesReleasedOperationsAndNeverIgnoresCorruptOrSubstitutedRows() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var observation = new TaskResourceObservation(db);
            var checkpoints = new TaskAuthorCheckpoint(db);
            var excluded = Set.of("setup", "policy");
            var expected = Set.of("setup", "policy", "diagnostic");
            for (String scenario : List.of("EXTRA_OPERATION", "EXTRA_RESOURCE", "FAKE_NAME", "PROTOCOL", "OWNER", "VERSION", "TASK", "DUPLICATE")) {
                var task = ledger.enqueue(UUID.randomUUID(), "owner", "1.0.0", new TaskRequest("fixture", "fixture", List.of(), true),
                    List.of(new TaskResource("MOD", "owner", "owner", false)));
                assertTrue(ledger.tryStart(task));
                var setup = checkpoints.begin(task, "owner", "1.0.0", "setup", "{}");
                checkpoints.begin(task, "owner", "1.0.0", "policy", "{}");
                var diagnostic = checkpoints.begin(task, "owner", "1.0.0", "diagnostic", "{}");
                checkpoints.release(task, "owner", "1.0.0", "diagnostic", diagnostic.lease());
                assertTrue(observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, expected));
                assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, Set.of("policy")));
                assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(task, "other", "1.0.0", excluded, expected));
                assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(task, "owner", "2.0.0", excluded, expected));
                if (scenario.equals("EXTRA_OPERATION")) {
                    var extra = checkpoints.begin(task, "owner", "1.0.0", "second-diagnostic", "{}");
                    checkpoints.release(task, "owner", "1.0.0", "second-diagnostic", extra.lease());
                    assertTrue(observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded), "Old resource-only predicate cannot see an additional released operation");
                    assertFalse(observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, expected));
                } else if (scenario.equals("EXTRA_RESOURCE")) {
                    for (String kind : List.of("FILE", "PROCESS", "CONNECTION", "CUSTOM")) {
                        var extra = ledger.retain(task, new TaskResource(kind, "extra-" + kind, "owner", false));
                        assertFalse(observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, expected), kind);
                        ledger.release(task, extra);
                    }
                    assertTrue(observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, expected));
                } else if (scenario.equals("FAKE_NAME")) {
                    var fake = ledger.retain(task, new TaskResource("CUSTOM", "owner/checkpoint/impostor", "owner", false));
                    ledger.release(task, fake);
                    assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, expected));
                } else if (scenario.equals("DUPLICATE")) {
                    try (var connection = db.openConnection(); var copy = connection.prepareStatement("INSERT INTO platform_task_resource(lease_id,task_id,resource_kind,resource_id,owner_id,access_mode,acquired_at,recovery_json,released_at) SELECT ?,task_id,resource_kind,resource_id,owner_id,access_mode,acquired_at,recovery_json,released_at FROM platform_task_resource WHERE lease_id=?")) {
                        copy.setObject(1, UUID.randomUUID()); copy.setObject(2, diagnostic.lease()); assertEquals(1, copy.executeUpdate());
                    }
                    assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, expected));
                } else {
                    String metadata;
                    try (var connection = db.openConnection(); var query = connection.prepareStatement("SELECT recovery_json FROM platform_task_resource WHERE lease_id=?")) {
                        query.setObject(1, setup.lease());
                        try (var rows = query.executeQuery()) { assertTrue(rows.next()); metadata = rows.getString(1); }
                    }
                    if (scenario.equals("PROTOCOL")) metadata = metadata.replace("author-checkpoint.v1", "recovery-checkpoint.v1");
                    if (scenario.equals("VERSION")) metadata = metadata.replace("1.0.0", "2.0.0");
                    if (scenario.equals("TASK")) metadata = metadata.replace(task.toString(), UUID.randomUUID().toString());
                    setMetadata(db, setup.lease(), metadata);
                    if (scenario.equals("OWNER")) try (var connection = db.openConnection(); var update = connection.prepareStatement("UPDATE platform_task_resource SET owner_id='other' WHERE lease_id=?")) {
                        update.setObject(1, setup.lease()); assertEquals(1, update.executeUpdate());
                    }
                    assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(task, "owner", "1.0.0", excluded, expected));
                }
            }
        }
    }

    @Test
    void exclusionsRequireExactUniqueAuthorEnvelopeAndObserveEveryOtherResource() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var observation = new TaskResourceObservation(db);
            var id = ledger.enqueue(UUID.randomUUID(), "owner", "1.0.0",
                new TaskRequest("fixture", "fixture", List.of("server"), true),
                List.of(new TaskResource("MOD", "owner", "owner", false), new TaskResource("SERVER", "server", "owner", true)));
            assertTrue(ledger.tryStart(id));
            var checkpoint = new TaskAuthorCheckpoint(db).begin(id, "owner", "1.0.0", "policy", "{}");
            var keys = Set.of("policy");
            assertFalse(observation.released(id));
            assertTrue(observation.releasedExceptCheckpoints(id, "owner", "1.0.0", keys));
            assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "other", "1.0.0", keys));
            assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "owner", "2.0.0", keys));
            assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(UUID.randomUUID(), "owner", "1.0.0", keys));
            for (var invalid : List.of(Set.<String>of(), Set.of("Policy"), Set.of("absent"), Set.of("x".repeat(65))))
                assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "owner", "1.0.0", invalid));
            assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "owner", "1.0.0", null));
            var tooMany = java.util.stream.IntStream.range(0, 17).mapToObj(i -> "key" + i).collect(java.util.stream.Collectors.toSet());
            assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "owner", "1.0.0", tooMany));
            for (String kind : List.of("FILE", "PROCESS", "CONNECTION", "CUSTOM")) {
                var extra = ledger.retain(id, new TaskResource(kind, "extra-" + kind, "other", false));
                assertFalse(observation.releasedExceptCheckpoints(id, "owner", "1.0.0", keys), kind);
                ledger.release(id, extra);
            }
            var otherCheckpoint = new TaskAuthorCheckpoint(db).begin(id, "owner", "1.0.0", "business", "{}");
            assertFalse(observation.releasedExceptCheckpoints(id, "owner", "1.0.0", keys));
            ledger.release(id, otherCheckpoint.lease());
            var impostor = ledger.retain(id, new TaskResource("CUSTOM", "owner/checkpoint/impostor", "owner", false));
            assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "owner", "1.0.0", Set.of("impostor")));
            ledger.release(id, impostor);
            String original;
            try (var c = db.openConnection(); var q = c.prepareStatement("SELECT recovery_json FROM platform_task_resource WHERE lease_id=?")) {
                q.setObject(1, checkpoint.lease());
                try (var rows = q.executeQuery()) { assertTrue(rows.next()); original = rows.getString(1); }
            }
            for (String damaged : List.of("{}", original.replace("author-checkpoint.v1", "recovery-checkpoint.v1"),
                original.replace("1.0.0", "2.0.0"), original.replace(id.toString(), UUID.randomUUID().toString()),
                original + " {}")) {
                setMetadata(db, checkpoint.lease(), damaged);
                assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "owner", "1.0.0", keys));
            }
            setMetadata(db, checkpoint.lease(), original);
            try (var c = db.openConnection(); var copy = c.prepareStatement("INSERT INTO platform_task_resource(lease_id,task_id,resource_kind,resource_id,owner_id,access_mode,acquired_at,recovery_json) SELECT ?,task_id,resource_kind,resource_id,owner_id,access_mode,acquired_at,recovery_json FROM platform_task_resource WHERE lease_id=?")) {
                copy.setObject(1, UUID.randomUUID()); copy.setObject(2, checkpoint.lease()); assertEquals(1, copy.executeUpdate());
            }
            assertThrows(SQLException.class, () -> observation.releasedExceptCheckpoints(id, "owner", "1.0.0", keys));
        }
    }

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

package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskRecoveryLedgerTest {
    @TempDir
    Path root;

    @Test
    void checkpointBlocksCompletionAcrossRestartAndCanOnlyBeReattachedWithOriginalFacts() throws Exception {
        UUID task, lease;
        var resource = new TaskResource("FILE", "sftp:original", "one", true);
        var facts = new ResourceRecovery("sftp.v1", Map.of("temporaryPath", "/.wtl-transfer-original.part"));
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            lease = ledger.retain(task, resource, facts, false);
            assertFalse(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
            assertEquals(List.of("FILE: sftp:original"), ledger.queries().snapshot(task).blockers());
            assertThrows(SQLException.class, () -> ledger.retain(task, resource));
            assertThrows(SQLException.class, () -> ledger.retain(task, resource,
                    new ResourceRecovery("sftp.v1", Map.of("temporaryPath", "/other")), false));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(task).state());
            assertEquals(lease, ledger.retain(task, resource, facts, false));
            var waiting = new TaskLedgerTest().queue(ledger, "two", "A");
            assertFalse(ledger.tryStart(waiting));
            assertThrows(SQLException.class, () -> ledger.release(waiting, lease));
            ledger.release(task, lease);
            ledger.release(task, lease);
            assertTrue(ledger.complete(task, Outcome.SUCCEEDED, "{}", true));
            assertTrue(ledger.tryStart(waiting));
        }
    }

    @Test
    void malformedUnknownAndDuplicateMetadataStayVisibleAndCannotBeReleased() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var task = new TaskLedgerTest().queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            var resource = new TaskResource("FILE", "sftp:corrupt", "one", true);
            var lease = ledger.retain(task, resource);
            for (String malformed : List.of("{}", "broken", "{\"release\":\"FUTURE\"}",
                    "{\"release\":\"EXPLICIT\",\"version\":2,\"protocol\":\"sftp.v1\",\"fields\":{\"path\":\"x\"}}",
                    "{\"release\":\"EXPLICIT\",\"release\":\"EXPLICIT\",\"version\":1,\"protocol\":\"sftp.v1\",\"fields\":{\"path\":\"x\"}}",
                    "{\"release\":\"EXPLICIT\"} {}")) {
                try (var c = db.openConnection();
                        var p = c.prepareStatement(
                                "UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=?")) {
                    p.setString(1, malformed);
                    p.setObject(2, lease);
                    p.executeUpdate();
                }
                assertFalse(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
                assertEquals(List.of("FILE: sftp:corrupt"), ledger.queries().snapshot(task).blockers());
                assertThrows(SQLException.class, () -> ledger.release(task, lease), malformed);
                assertThrows(SQLException.class, () -> ledger.retain(task, resource), malformed);
            }
            try (var c = db.openConnection();
                    var p = c.prepareStatement(
                            "SELECT lease_id FROM platform_task_resource WHERE task_id=? AND resource_kind='SERVER'")) {
                p.setObject(1, task);
                try (var rows = p.executeQuery()) {
                    assertTrue(rows.next());
                    var automatic = rows.getObject(1, UUID.class);
                    assertThrows(SQLException.class, () -> ledger.release(task, automatic));
                }
            }
        }
    }

    @Test
    void registrationFailureLeavesNoPartialFileLeaseAndOversizedFactsNeverWrite() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var task = new TaskLedgerTest().queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource ADD CONSTRAINT reject_file CHECK(resource_kind<>'FILE')");
            }
            var file = new TaskResource("FILE", "sftp:rejected", "one", true);
            assertThrows(SQLException.class, () -> ledger.retain(task, file,
                    new ResourceRecovery("sftp.v1", Map.of("path", "/temporary")), false));
            assertEquals(2, new TaskLedgerTest().active(db, task));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT reject_file");
            }
            var huge = new ResourceRecovery("sftp.v1", Map.of("one", "x".repeat(16384), "two", "x".repeat(16384),
                    "three", "x".repeat(16384), "four", "x".repeat(16384)));
            assertThrows(SQLException.class, () -> ledger.retain(task, file, huge, false));
            assertEquals(2, new TaskLedgerTest().active(db, task));
            assertTrue(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
        }
    }
}

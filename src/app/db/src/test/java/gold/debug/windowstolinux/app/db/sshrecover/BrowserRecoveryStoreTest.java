package gold.debug.windowstolinux.app.db.sshrecover;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformStoreTestFixture;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.sshrecover.BrowserRecoveryArchival;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserRecoveryStoreTest {
    @TempDir
    Path directory;

    @Test
    void restartInterruptsWithoutReplayingAndJournalHasOnlyDigests() throws Exception {
        UUID original;
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, "ssh/server/password");
            db.servers().saveServerProfile(
                new StoredServerProfile("server", "example.test", 22, "tester", "ssh/server/password"));
            var ledger = new TaskLedger(db.database());
            original = PlatformStoreTestFixture.queue(db, "@platform", BrowserRecoveryArchival.TASK_TYPE,
                "server");
            assertThrows(SQLException.class,
                () -> db.recovery().savePlatform(original, "server", "EXECUTING", "a".repeat(64), "submitted"));
            assertTrue(ledger.tryStart(original));
            assertThrows(SQLException.class,
                () -> db.recovery().savePlatform(original, "different", "EXECUTING", "a".repeat(64), "submitted"));
            db.recovery().savePlatform(original, "server", "EXECUTING", "a".repeat(64), "submitted");
            assertThrows(IllegalArgumentException.class,
                () -> db.recovery().savePlatform(original, "server", "PAUSED", "password", "paused"));
            try (var connection = db.database().openConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE browser_recovery_event ADD CONSTRAINT reject_browser_event CHECK(event_code<>'rejected')");
                try {
                    assertThrows(SQLException.class,
                        () -> db.recovery().savePlatform(original, "server", "PAUSED", "b".repeat(64), "rejected"));
                } finally {
                    statement.executeUpdate("ALTER TABLE browser_recovery_event DROP CONSTRAINT reject_browser_event");
                }
            }
        }
        try (var db = PlatformStoreTestFixture.open(directory); var c = db.database().openConnection();
             var s = c.createStatement()) {
            var ledger = new TaskLedger(db.database());
            var retained = ledger.queries().snapshot(original);
            assertEquals(original, retained.id());
            assertEquals("@platform", retained.ownerId());
            assertEquals(BrowserRecoveryArchival.TASK_TYPE, retained.type());
            assertEquals(TaskState.UNKNOWN, retained.state());
            assertTrue(retained.outcome().isEmpty());
            assertTrue(ledger.queries().holds(original, new TaskResource("SERVER", "server", "@platform", true)));
            assertThrows(SQLException.class, () -> ledger.tryStart(original));
            try (var row = s.executeQuery("SELECT task_state FROM browser_recovery_task")) {
                assertTrue(row.next());
                assertEquals("EXECUTING", row.getString(1), "Opening H2 retains the original controller evidence");
                assertFalse(row.next());
            }
            // The explicit repository interruption contract is separate from central UNKNOWN boot admission.
            // 仓库显式中断合同与中央任务启动时的UNKNOWN准入分开。
            db.recovery().interruptUnfinished();
            try (var row = s.executeQuery("SELECT * FROM browser_recovery_task")) {
                assertTrue(row.next());
                assertEquals(original.toString(), row.getString("task_id"));
                assertEquals("server", row.getString("server_profile_id"));
                assertEquals(BrowserRecoveryArchival.TASK_TYPE, row.getString("operation_id"));
                assertEquals("INTERRUPTED", row.getString("task_state"));
                assertEquals("app-restarted", row.getString("last_event_code"));
                assertEquals("a".repeat(64), row.getString("last_action_digest"));
                assertFalse(row.next());
            }
            try (var row = s.executeQuery("SELECT count(*) FROM browser_recovery_event")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
            try (var row = s.executeQuery("SELECT * FROM browser_recovery_event")) {
                assertTrue(row.next());
                assertEquals(original.toString(), row.getString("task_id"));
                assertEquals("EXECUTING", row.getString("task_state"));
                assertEquals("submitted", row.getString("event_code"));
                assertEquals("a".repeat(64), row.getString("action_digest"));
                assertFalse(row.next());
            }
        }
    }
}

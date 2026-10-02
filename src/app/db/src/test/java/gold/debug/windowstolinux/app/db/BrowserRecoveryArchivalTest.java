package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static gold.debug.windowstolinux.app.db.TaskArchivalLedgerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.RepositoryConnections;
import gold.debug.windowstolinux.app.db.sshrecover.BrowserRecoveryRepository;
import gold.debug.windowstolinux.app.db.sshrecover.BrowserRecoveryArchival;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserRecoveryArchivalTest {
    @TempDir
    Path root;

    static BrowserRecoveryRepository journal(PlatformDatabase db) {
        return new BrowserRecoveryRepository(new RepositoryConnections(db::openConnection));
    }

    static UUID queue(TaskLedger ledger, String owner) throws Exception {
        return ledger.enqueue(UUID.randomUUID(), owner, "1.0.0",
            new TaskRequest(BrowserRecoveryArchival.TASK_TYPE, "rescue", List.of("server"), true),
            List.of(new TaskResource("MOD", owner, owner, false),
                new TaskResource("SERVER", "server", owner, true)));
    }

    static LogRepository.Target target(LogRepository logs, String category) throws Exception {
        return logs.targets().stream().filter(t -> t.category().equals(category)).findFirst().orElseThrow();
    }

    static void event(PlatformDatabase db, UUID id) throws Exception {
        journal(db).savePlatform(id, "server", "EXECUTING", "a".repeat(64), "submitted");
    }

    @Test
    void knownRescueArchivesItsSummaryEventsResourcesAndTaskAtomically() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger, "@platform");
            assertTrue(ledger.tryStart(id));
            event(db, id);
            journal(db).savePlatform(id, "server", "RECOVERED", "a".repeat(64), "recovered");
            String original;
            try (var c = db.openConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery("SELECT archive_log_id FROM platform_task")) {
                assertTrue(r.next());
                original = r.getString(1);
            }
            assertEquals(TaskState.ENDED, ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
            }).state());
            for (var table : List.of("browser_recovery_task", "browser_recovery_event", "platform_task",
                "platform_task_resource"))
                assertEquals(0, count(db, "SELECT COUNT(*) FROM " + table));
            var logs = new LogRepository(db);
            var summary = logs.read(target(logs, "browser_recovery")).getFirst();
            assertEquals(
                UUID.nameUUIDFromBytes(
                    ("WTL-BROWSER-ARCHIVE-1:" + original).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                summary.id());
            assertEquals(id, summary.taskId());
            assertEquals(Outcome.SUCCEEDED, summary.outcome());
            assertNull(summary.subjectMod());
            var details = new ObjectMapper().readTree(summary.details());
            assertEquals("RECOVERED", details.path("lastObservedState").asText());
            assertEquals(2, details.path("eventCount").asInt());
            assertEquals(1, details.path("submittedCount").asInt());
            assertEquals("a".repeat(64), details.path("lastActionSha256").asText());
            assertEquals(1, logs.read(target(logs, "platform_task")).size());
            assertThrows(SQLException.class, () -> event(db, id));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM browser_recovery_task"));
        }
    }

    @Test
    void failedRescueSummaryRetainsOriginalJournalAcrossBootWithoutRebuildingPrunedHistory() throws Exception {
        UUID id;
        TaskLedger.Finalization original;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            id = queue(ledger, "@platform");
            assertTrue(ledger.tryStart(id));
            event(db, id);
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT rescue_archive_fixture CHECK(category<>'browser_recovery')");
            original = assertThrows(TaskLedger.FinalizationException.class,
                () -> ledger.completeAndArchive(id, Outcome.FAILED, "{}", false, () -> {
                })).finalization();
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertTrue(ledger.retainFinalization(original));
            assertThrows(SQLException.class, () -> event(db, id));
            // Remove only the injected schema fault before formal boot validation. / 正式启动校验前仅移除注入的结构故障。
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT rescue_archive_fixture");
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var restored = ledger.queries().pendingFinalization(id).orElseThrow();
            assertEquals(original.snapshot(), ledger.retryFinalization(restored, () -> {
            }));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM runtime_log"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
            sql(db, "DELETE FROM runtime_log");
            ledger.retryFinalization(original, () -> {
                throw new AssertionError("must not repeat finalization");
            });
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log"));
            assertThrows(SQLException.class, () -> event(db, id));
        }
    }

    @Test
    void referencedJournalRollsBackBothSummariesAndAllExecutionCleanup() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger, "@platform");
            assertTrue(ledger.tryStart(id));
            event(db, id);
            sql(db, "CREATE TABLE rescue_reference_fixture(task_id VARCHAR REFERENCES browser_recovery_task(task_id))");
            sql(db, "INSERT INTO rescue_reference_fixture VALUES('" + id + "')");
            var original = assertThrows(TaskLedger.FinalizationException.class,
                () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
                })).finalization();
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_task"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
            assertEquals(TaskState.RUNNING, ledger.queries().snapshot(id).state());
            sql(db, "DELETE FROM rescue_reference_fixture");
            ledger.retryFinalization(original, () -> {
            });
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
        }
    }

    @Test
    void unknownAndUnreleasedBrowserRemainUntilExplicitReconciliation() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger, "@platform");
            assertTrue(ledger.tryStart(id));
            event(db, id);
            var lease = ledger.retain(id, new TaskResource("PROCESS", "browser", "@platform", false));
            assertEquals(TaskState.UNKNOWN, ledger.completeAndArchive(id, Outcome.UNKNOWN, "{}", false, () -> {
            }).state());
            assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
            assertEquals(TaskState.FINISHING, ledger.completeAndArchive(id, Outcome.FAILED, "{}", true, () -> {
            }).state());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log"));
            ledger.release(id, lease);
            ledger.completeAndArchive(id, Outcome.FAILED, "{}", true, () -> {
            });
            var logs = new LogRepository(db);
            var summary = logs.read(target(logs, "browser_recovery")).getFirst();
            assertEquals(Outcome.FAILED, summary.outcome());
            assertEquals("EXECUTING",
                new ObjectMapper().readTree(summary.details()).path("lastObservedState").asText());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
        }
    }

    @Test
    void foreignServerProtocolOrOwnerCannotBeArchivedAsOriginalRescue() throws Exception {
        for (var mismatch : List.of("server", "protocol", "owner")) {
            try (var db = PlatformDatabase.open(root.resolve(mismatch), PlatformDatabaseTest.TEST_PROTECTION)) {
                var ledger = new TaskLedger(db);
                var id = queue(ledger, mismatch.equals("owner") ? "example.mod" : "@platform");
                assertTrue(ledger.tryStart(id));
                journal(db).save(id.toString(), mismatch.equals("server") ? "different" : "server",
                    mismatch.equals("protocol") ? "wrong" : BrowserRecoveryArchival.TASK_TYPE, "RECOVERED", "",
                    "recovered");
                assertThrows(TaskLedger.FinalizationException.class,
                    () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
                    }));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_task"));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
                assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log"));
            }
        }
    }

    @Test
    void unrelatedCompletionDoesNotAdoptAnOrphanRescueJournal() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var orphan = UUID.randomUUID();
            journal(db).save(orphan.toString(), "server", BrowserRecoveryArchival.TASK_TYPE, "RECOVERED", "",
                "recovered");
            var ledger = new TaskLedger(db);
            var id = queue(ledger, "@platform");
            assertTrue(ledger.tryStart(id));
            ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
            });
            assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_task"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='browser_recovery'"));
            assertThrows(SQLException.class, () -> event(db, orphan));
        }
    }

    @Test
    void journalAdmissionRequiresStartedOriginalHostAndExactServer() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger, "@platform");
            assertThrows(SQLException.class, () -> event(db, id));
            assertTrue(ledger.tryStart(id));
            assertThrows(SQLException.class, () -> journal(db).savePlatform(id, "other", "STARTING", "", "starting"));
            event(db, id);
            assertEquals(1, count(db, "SELECT COUNT(*) FROM browser_recovery_event"));
            var foreign = queue(ledger, "example.mod");
            ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
            });
            assertTrue(ledger.tryStart(foreign));
            assertThrows(SQLException.class, () -> event(db, foreign));
        }
    }
}

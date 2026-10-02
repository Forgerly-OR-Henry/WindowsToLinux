package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskArchivalLedgerTest {
    @TempDir
    Path root;

    UUID queue(TaskLedger ledger) throws Exception {
        return new TaskLedgerTest().queue(ledger, "example.owner", "server-A");
    }

    static long count(PlatformDatabase db, String sql) throws Exception {
        try (var c = db.openConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    static void sql(PlatformDatabase db, String sql) throws Exception {
        try (var c = db.openConnection(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void resultSummaryAndReleasedRowsAreCommittedTogetherWithoutDeletingBusinessData() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            var lease = ledger.retain(id, new TaskResource("CONNECTION", "original", "example.owner", false));
            sql(db, "CREATE TABLE business_receipt(id INT PRIMARY KEY,value_text VARCHAR)");
            sql(db, "INSERT INTO business_receipt VALUES(1,'committed by the business')");
            var blocked = ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
            });
            assertEquals(TaskState.FINISHING, blocked.state());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
            ledger.release(id, lease);
            var cleaned = new AtomicInteger();
            var ended = ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{\"password\":\"do-not-log\"}", false,
                cleaned::incrementAndGet);
            assertEquals(TaskState.ENDED, ended.state());
            assertEquals(1, cleaned.get());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM business_receipt"));
            assertEquals(1, count(db,
                "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task' AND outcome='SUCCEEDED' AND subject_mod_id='example.owner'"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE details LIKE '%do-not-log%'"));
            assertThrows(SQLException.class, () -> ledger.queries().snapshot(id));
        }
    }

    @Test
    void queuedCancellationArchivesWithoutAcquiringAnyResources() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            var ended = ledger.cancelAndArchive(id, () -> {
            });
            assertEquals(Outcome.CANCELLED, ended.outcome().orElseThrow());
            assertEquals(TaskState.ENDED, ended.state());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM runtime_log WHERE event_code='PLATFORM_TASK_CANCELLED'"));
        }
    }

    @Test
    void failedSummaryRetainsOriginalReceiptAcrossBootAndDoesNotRebuildPrunedHistory() throws Exception {
        UUID id;
        TaskLedger.Finalization original;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT archive_fixture CHECK(category<>'platform_task')");
            original = assertThrows(TaskLedger.FinalizationException.class, () -> ledger.completeAndArchive(id,
                Outcome.FAILED, "{\"reason\":\"business failed\"}", false, () -> {
                })).finalization();
            assertEquals(TaskState.RUNNING, ledger.queries().snapshot(id).state());
            assertEquals(2, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertTrue(ledger.retainFinalization(original));
            assertEquals(TaskState.FINISHING, ledger.queries().snapshot(id).state());
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT archive_fixture");
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.FINISHING, ledger.queries().snapshot(id).state());
            var restored = ledger.queries().pendingFinalization(id).orElseThrow();
            assertEquals(original.snapshot(), restored.snapshot());
            assertEquals(original.snapshot(), ledger.retryFinalization(restored, () -> {
            }));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
            sql(db, "DELETE FROM runtime_log WHERE category='platform_task'");
            assertEquals(original.snapshot(), ledger.retryFinalization(original, () -> {
                throw new AssertionError("already finalized");
            }));
            assertFalse(ledger.retainFinalization(original));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
        }
    }

    @Test
    void failedExecutionRemovalRollsBackSummaryAndAllResourceChanges() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            sql(db, "CREATE TABLE retained_fixture(task_id UUID REFERENCES platform_task(task_id))");
            sql(db, "INSERT INTO retained_fixture VALUES('" + id + "')");
            var original = assertThrows(TaskLedger.FinalizationException.class,
                () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
                })).finalization();
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertEquals(TaskState.RUNNING, ledger.queries().snapshot(id).state());
            sql(db, "DELETE FROM retained_fixture");
            assertEquals(TaskState.ENDED, ledger.retryFinalization(original, () -> {
            }).state());
            assertEquals(1, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
        }
    }

    @Test
    void receiptCannotFinalizeAnotherDatabaseOrChangedTaskIdentity() throws Exception {
        try (var db = PlatformDatabase.open(root.resolve("first"), PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT archive_fixture CHECK(category<>'platform_task')");
            var original = assertThrows(TaskLedger.FinalizationException.class,
                () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
                })).finalization();
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT archive_fixture");
            try (var other = PlatformDatabase.open(root.resolve("other"), PlatformDatabaseTest.TEST_PROTECTION)) {
                assertThrows(SQLException.class, () -> new TaskLedger(other).retryFinalization(original, () -> {
                }));
                assertEquals(0, count(other, "SELECT COUNT(*) FROM runtime_log"));
            }
            sql(db, "UPDATE platform_task SET owner_version='2.0.0' WHERE task_id='" + id + "'");
            assertThrows(SQLException.class, () -> ledger.retryFinalization(original, () -> {
            }));
            assertThrows(SQLException.class, () -> ledger.retainFinalization(original));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM platform_task"));
        }
    }

    @Test
    void retainedConclusionCannotBeOverwrittenByAnOlderReceipt() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT archive_fixture CHECK(category<>'platform_task')");
            var original = assertThrows(TaskLedger.FinalizationException.class,
                () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
                })).finalization();
            assertTrue(ledger.retainFinalization(original));
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT archive_fixture");
            sql(db, "UPDATE platform_task SET outcome='FAILED' WHERE task_id='" + id + "'");
            assertThrows(SQLException.class, () -> ledger.retryFinalization(original, () -> {
            }));
            assertThrows(SQLException.class, () -> ledger.retainFinalization(original));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM platform_task WHERE outcome='FAILED'"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
        }
    }

    @Test
    void boundedSummaryRetainsOriginalIdentityAndCategoryRetentionDoesNotKeepExecutions() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            UUID archive;
            try (var c = db.openConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery("SELECT archive_log_id FROM platform_task")) {
                assertTrue(r.next());
                archive = UUID.fromString(r.getString(1));
            }
            ledger.completeAndArchive(id, Outcome.SUCCEEDED, "界😀".repeat(12000), false, () -> {
            });
            var logs = new LogRepository(db);
            var target = logs.targets().stream().filter(t -> t.category().equals("platform_task")).findFirst()
                .orElseThrow();
            var original = logs.read(target).getFirst();
            assertEquals(archive, original.id());
            assertEquals(id, original.taskId());
            assertEquals("example.owner", original.subjectMod());
            assertTrue(original.truncated());
            assertTrue(LogRecordCodec.encode(target.key(), original).length <= LogRecordCodec.MAX_BYTES);
            for (int i = 0; i < 101; i++)
                ledger.cancelAndArchive(queue(ledger), () -> {
                });
            assertEquals(100, logs.read(target).size());
            logs.store(target, List.of(), 10);
            assertEquals(10, logs.read(target).size());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource"));
        }
    }

    @Test
    void invalidDiagnosticEncodingRetainsOriginalFinalizationInsteadOfEscapingTheHost() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            ledger.progress(id, "\uD800broken");
            var original = assertThrows(TaskLedger.FinalizationException.class,
                () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
                })).finalization();
            assertTrue(ledger.retainFinalization(original));
            assertEquals(TaskState.FINISHING, ledger.queries().snapshot(id).state());
            assertFalse(ledger.queries().snapshot(id).outcome().isPresent());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
        }
    }

    @Test
    void unknownAndExplicitResourcesCannotBeArchivedAsKnownCompletion() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = queue(ledger);
            assertTrue(ledger.tryStart(id));
            assertEquals(TaskState.UNKNOWN, ledger.completeAndArchive(id, Outcome.UNKNOWN, "{}", false, () -> {
            }).state());
            assertTrue(ledger.queries().pendingFinalization(id).isEmpty());
            assertThrows(SQLException.class, () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
            }));
            var lease = ledger.retain(id, new TaskResource("CUSTOM", "retained", "example.owner", false));
            assertEquals(TaskState.FINISHING, ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", true, () -> {
            }).state());
            assertTrue(ledger.queries().pendingFinalization(id).isEmpty());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
            ledger.release(id, lease);
            assertEquals(TaskState.ENDED, ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", true, () -> {
            }).state());
        }
    }
}

package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskLedgerTest {
    @TempDir
    Path root;
    UUID queue(TaskLedger ledger, String owner, String... servers) throws Exception {
        var claims = new ArrayList<TaskResource>();
        claims.add(new TaskResource("MOD", owner, owner, false));
        for (var server : servers)
            claims.add(new TaskResource("SERVER", server, owner, true));
        return ledger.enqueue(UUID.randomUUID(), owner, "1.0.0",
                new TaskRequest("fixture", "fixture", List.of(servers), true), claims);
    }

    long active(PlatformDatabase db, UUID task) throws Exception {
        try (var c = db.openConnection();
                var p = c.prepareStatement(
                        "SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND released_at IS NULL")) {
            p.setObject(1, task);
            try (var r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    @Test
    void multiServerConflictLeavesNoPartialClaimsAndOtherServersCanRun() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var first = queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(first));
            var combined = queue(ledger, "two", "B", "A");
            assertFalse(ledger.tryStart(combined));
            assertEquals(0, active(db, combined));
            assertEquals(TaskState.QUEUED, ledger.snapshot(combined).state());
            var independent = queue(ledger, "three", "B");
            assertTrue(ledger.tryStart(independent));
            assertTrue(ledger.complete(first, Outcome.SUCCEEDED, "{}", false));
            assertFalse(ledger.tryStart(combined));
            assertTrue(ledger.complete(independent, Outcome.SUCCEEDED, "{}", false));
            assertTrue(ledger.tryStart(combined));
            assertEquals(3, active(db, combined));
            assertTrue(ledger.complete(combined, Outcome.SUCCEEDED, "{}", false));
            assertEquals(List.of(), ledger.unfinished());
        }
    }

    @Test
    void separateLedgerInstancesCannotBothAcquireTheSameServer() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION);
                var workers = Executors.newFixedThreadPool(2)) {
            var firstLedger = new TaskLedger(db);
            var secondLedger = new TaskLedger(db);
            var first = queue(firstLedger, "one", "A");
            var second = queue(secondLedger, "two", "A");
            var barrier = new CyclicBarrier(2);
            var a = workers.submit(() -> {
                barrier.await();
                return firstLedger.tryStart(first);
            });
            var b = workers.submit(() -> {
                barrier.await();
                return secondLedger.tryStart(second);
            });
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(2, active(db, first) + active(db, second));
        }
    }

    @Test
    void resultWaitsForActualExplicitReleaseAndCannotReleaseForeignLease() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var first = queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(first));
            var lease = ledger.retain(first, new TaskResource("CONNECTION", "physical-1", "one", false));
            var waiting = queue(ledger, "two", "A");
            assertFalse(ledger.complete(first, Outcome.SUCCEEDED, "{\"operation\":\"observed\"}", false));
            assertEquals(TaskState.FINISHING, ledger.snapshot(first).state());
            assertTrue(ledger.snapshot(first).outcome().isEmpty());
            assertEquals(1, ledger.snapshot(first).blockers().size());
            assertFalse(ledger.tryStart(waiting));
            assertThrows(SQLException.class, () -> ledger.release(waiting, lease));
            assertEquals(3, active(db, first));
            ledger.release(first, lease);
            ledger.release(first, lease);
            assertTrue(ledger.complete(first, Outcome.SUCCEEDED, "{}", false));
            assertTrue(ledger.snapshot(first).endedAt().isPresent());
            assertEquals(0, active(db, first));
            assertTrue(ledger.tryStart(waiting));
        }
    }

    @Test
    void unknownResultKeepsServerAcrossBootsUntilExplicitReconciliation() throws Exception {
        UUID unknown;
        UUID generation;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            generation = db.generation();
            var ledger = new TaskLedger(db);
            unknown = queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(unknown));
            assertFalse(ledger.complete(unknown, Outcome.UNKNOWN, "{\"event\":\"lost_acknowledgement\"}", false));
            assertThrows(SQLException.class, () -> ledger.complete(unknown, Outcome.FAILED, "{}", false));
            assertEquals(2, active(db, unknown));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            assertEquals(generation, db.generation());
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.UNKNOWN, ledger.snapshot(unknown).state());
            assertThrows(SQLException.class, () -> ledger.tryStart(unknown));
            var waiting = queue(ledger, "two", "A");
            assertFalse(ledger.tryStart(waiting));
            assertTrue(ledger.complete(unknown, Outcome.SUCCEEDED, "{\"observed\":true}", true));
            assertTrue(ledger.tryStart(waiting));
        }
    }

    @Test
    void cancellationDoesNotReleaseRunningWorkAndQueuedWorkNeverAcquiresResources() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var running = queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(running));
            assertFalse(ledger.cancel(running));
            assertEquals(TaskState.CANCEL_REQUESTED, ledger.snapshot(running).state());
            assertEquals(2, active(db, running));
            var queued = queue(ledger, "two", "A");
            assertTrue(ledger.cancel(queued));
            assertEquals(Outcome.CANCELLED, ledger.snapshot(queued).outcome().orElseThrow());
            assertFalse(ledger.tryStart(queued));
            assertEquals(0, active(db, queued));
            assertTrue(ledger.complete(running, Outcome.CANCELLED, "{}", false));
            assertEquals(0, active(db, running));
        }
    }

    @Test
    void admissionRejectsIncompleteClaimsAndCannotAppendServerLocks() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var request = new TaskRequest("fixture", "fixture", List.of("A", "B"), true);
            assertThrows(SQLException.class, () -> ledger.enqueue(UUID.randomUUID(), "one", "1.0.0", request, List
                    .of(new TaskResource("MOD", "one", "one", false), new TaskResource("SERVER", "A", "one", true))));
            assertTrue(ledger.unfinished().isEmpty());
            var task = queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            assertThrows(SQLException.class, () -> ledger.retain(task, new TaskResource("SERVER", "B", "one", true)));
            assertEquals(2, active(db, task));
        }
    }
}

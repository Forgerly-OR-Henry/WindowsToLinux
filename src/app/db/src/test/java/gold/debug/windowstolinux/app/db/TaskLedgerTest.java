package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
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
            assertEquals(TaskState.QUEUED, ledger.queries().snapshot(combined).state());
            var independent = queue(ledger, "three", "B");
            assertTrue(ledger.tryStart(independent));
            assertTrue(ledger.complete(first, TaskResult.SUCCEEDED, "{}", false));
            assertFalse(ledger.tryStart(combined));
            assertTrue(ledger.complete(independent, TaskResult.SUCCEEDED, "{}", false));
            assertTrue(ledger.tryStart(combined));
            assertEquals(3, active(db, combined));
            assertTrue(ledger.complete(combined, TaskResult.SUCCEEDED, "{}", false));
            assertEquals(List.of(), ledger.queries().unfinished());
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
            assertFalse(ledger.complete(first, TaskResult.SUCCEEDED, "{\"operation\":\"observed\"}", false));
            assertEquals(TaskState.FINISHING, ledger.queries().snapshot(first).state());
            assertTrue(ledger.queries().snapshot(first).outcome().isEmpty());
            assertEquals(1, ledger.queries().snapshot(first).blockers().size());
            assertFalse(ledger.tryStart(waiting));
            assertThrows(SQLException.class, () -> ledger.release(waiting, lease));
            assertEquals(3, active(db, first));
            ledger.release(first, lease);
            ledger.release(first, lease);
            assertTrue(ledger.complete(first, TaskResult.SUCCEEDED, "{}", false));
            assertTrue(ledger.queries().snapshot(first).endedAt().isPresent());
            assertEquals(0, active(db, first));
            assertTrue(ledger.tryStart(waiting));
        }
    }

    @Test
    void alreadyUncertainResultRetainsEvidenceAndResourcesWithoutFinalization() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var task = queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            var physical = new TaskResource("CONNECTION", "physical-1", "one", false);
            ledger.retain(task, physical);
            ledger.uncertain(task);
            var beforeEnd = new AtomicInteger();
            String evidence = "{\"outcome\":\"UNKNOWN\",\"event\":\"SSH_COMMAND_TIMEOUT\"}";
            for (int attempt = 0; attempt < 2; attempt++) {
                var retained = ledger.completeAndArchive(task, TaskResult.UNKNOWN, evidence, false,
                    beforeEnd::incrementAndGet);
                assertEquals(TaskState.UNKNOWN, retained.state());
                assertTrue(retained.outcome().isEmpty());
                assertTrue(retained.endedAt().isEmpty());
                assertTrue(ledger.queries().pendingFinalization(task).isEmpty());
                assertEquals(3, active(db, task));
                assertTrue(ledger.queries().holds(task, new TaskResource("MOD", "one", "one", false)));
                assertTrue(ledger.queries().holds(task, new TaskResource("SERVER", "A", "one", true)));
                assertTrue(ledger.queries().holds(task, physical));
            }
            for (var outcome : List.of(TaskResult.SUCCEEDED, TaskResult.FAILED, TaskResult.CANCELLED))
                assertThrows(SQLException.class, () -> ledger.completeAndArchive(task, outcome, "{}", false,
                    beforeEnd::incrementAndGet));
            assertEquals(0, beforeEnd.get());
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(task).state());
            assertEquals(3, active(db, task));
            try (var c = db.openConnection();
                 var p = c.prepareStatement("SELECT result_json FROM platform_task WHERE task_id=?")) {
                p.setObject(1, task);
                try (var r = p.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals(evidence, r.getString(1));
                }
            }
            try (var c = db.openConnection();
                 var p = c.prepareStatement("SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'");
                 var r = p.executeQuery()) {
                assertTrue(r.next());
                assertEquals(0, r.getLong(1));
            }
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
            assertFalse(ledger.complete(unknown, TaskResult.UNKNOWN, "{\"event\":\"lost_acknowledgement\"}", false));
            assertThrows(SQLException.class, () -> ledger.complete(unknown, TaskResult.FAILED, "{}", false));
            assertEquals(2, active(db, unknown));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            assertEquals(generation, db.generation());
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(unknown).state());
            assertThrows(SQLException.class, () -> ledger.tryStart(unknown));
            var waiting = queue(ledger, "two", "A");
            assertFalse(ledger.tryStart(waiting));
            assertTrue(ledger.complete(unknown, TaskResult.SUCCEEDED, "{\"observed\":true}", true));
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
            assertEquals(TaskState.CANCEL_REQUESTED, ledger.queries().snapshot(running).state());
            assertEquals(2, active(db, running));
            var queued = queue(ledger, "two", "A");
            assertTrue(ledger.cancel(queued));
            assertEquals(TaskResult.CANCELLED, ledger.queries().snapshot(queued).outcome().orElseThrow());
            assertFalse(ledger.tryStart(queued));
            assertEquals(0, active(db, queued));
            assertTrue(ledger.complete(running, TaskResult.CANCELLED, "{}", false));
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
            assertTrue(ledger.queries().unfinished().isEmpty());
            var task = queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            assertThrows(SQLException.class, () -> ledger.retain(task, new TaskResource("SERVER", "B", "one", true)));
            assertEquals(2, active(db, task));
        }
    }
}

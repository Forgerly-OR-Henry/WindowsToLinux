package gold.debug.windowstolinux.app.service.task;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskArchivalTest {
    @TempDir
    Path root;

    static TaskRequest request(String... servers) {
        return new TaskRequest("fixture", "task archival fixture", List.of(servers), true);
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

    static OperationResult<String> success(String value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    static void trap(PlatformDatabase db) throws Exception {
        sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT task_archive_fixture CHECK(category<>'platform_task')");
    }

    static void untrap(PlatformDatabase db) throws Exception {
        sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT task_archive_fixture");
    }

    @Test
    void handleSurvivesExecutionCleanupAndLaterHistoryPruning() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var task = runtime.bind("example.owner", "1.0.0", false).submit(request(),
                ignored -> success("original result"));
            assertEquals("original result", result(task).value().orElseThrow());
            assertEquals(TaskState.ENDED, task.snapshot().state());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource"));
            sql(db, "DELETE FROM runtime_log WHERE category='platform_task'");
            assertEquals(TaskState.ENDED, task.snapshot().state());
            task.requestCancel();
            assertEquals("original result", result(task).value().orElseThrow());
            assertTrue(runtime.snapshots().isEmpty());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
        }
    }

    @Test
    void summaryFailureRetriesOnlyHostArchivalAndDoesNotInvokeBusinessRecovery() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            trap(db);
            var calls = new AtomicInteger();
            var recoveryCalls = new AtomicInteger();
            var service = runtime.bind("example.owner", "1.0.0", false);
            var task = service.submit(request("A"), ignored -> {
                calls.incrementAndGet();
                return success("saved");
            });
            state(task, TaskState.FINISHING);
            assertFalse(task.completion().toCompletableFuture().isDone());
            assertEquals(1, security.securityState().activeTasks());
            var waiting = runtime.bind("example.other", "1.0.0", false).submit(request("A"),
                ignored -> success("next"));
            state(waiting, TaskState.QUEUED);
            untrap(db);
            try (var registration = service.registerRecovery("fixture", (previous, original) -> {
                recoveryCalls.incrementAndGet();
                throw new AssertionError("business recovery must not run");
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(Outcome.SUCCEEDED, result(task).outcome());
            assertTrue(result(task).value().isEmpty());
            assertEquals("next", result(waiting).value().orElseThrow());
            assertEquals(1, calls.get());
            assertEquals(0, recoveryCalls.get());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
        }
    }

    @Test
    void failedQueuedCancellationCanFinishWithoutAnyRegisteredRecovery() throws Exception {
        var release = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var running = service.submit(request("A"), ignored -> {
                assertTrue(release.await(8, TimeUnit.SECONDS));
                return success("running");
            });
            state(running, TaskState.RUNNING);
            var cancelled = service.submit(request("A"), ignored -> {
                throw new AssertionError("queued work must not run");
            });
            trap(db);
            cancelled.requestCancel();
            state(cancelled, TaskState.FINISHING);
            untrap(db);
            assertEquals(Outcome.CANCELLED, runtime.reconcile(cancelled.id()).toCompletableFuture()
                .get(8, TimeUnit.SECONDS).outcome().orElseThrow());
            assertEquals(Outcome.CANCELLED, result(cancelled).outcome());
            release.countDown();
            assertEquals("running", result(running).value().orElseThrow());
        } finally {
            release.countDown();
        }
    }

    @Test
    void bootCompletesOnlyDurablyKnownFinalizationWithoutRequiringOriginalMod() throws Exception {
        UUID id;
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var ledger = new TaskLedger(db);
            id = ledger.enqueue(UUID.randomUUID(), "example.old", "1.0.0", request(),
                List.of(new TaskResource("MOD", "example.old", "example.old", false)));
            assertTrue(ledger.tryStart(id));
            trap(db);
            var original = assertThrows(TaskLedger.FinalizationException.class,
                () -> ledger.completeAndArchive(id, Outcome.SUCCEEDED, "{}", false, () -> {
                })).finalization();
            ledger.retainFinalization(original);
            untrap(db);
        }
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            assertTrue(runtime.snapshots().isEmpty());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM runtime_log WHERE task_id='" + id + "'"));
        }
    }

    @Test
    void bootArchiveFailureRemainsVisibleAndBlocksShutdownUntilMetadataRetry() throws Exception {
        UUID id;
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var ledger = new TaskLedger(db);
            id = ledger.enqueue(UUID.randomUUID(), "example.old", "1.0.0", request(),
                List.of(new TaskResource("MOD", "example.old", "example.old", false)));
            assertTrue(ledger.tryStart(id));
            assertTrue(ledger.complete(id, Outcome.SUCCEEDED, "{}", false));
        }
        try (var db = PlatformDatabase.open(root, PROTECTION); var security = security(db)) {
            // Inject only after formal database startup; task-runtime boot still sees the archival failure. / 仅在正式数据库启动后注入，任务运行时启动仍观察归档失败。
            trap(db);
            try (var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
                assertEquals(TaskState.FINISHING, runtime.snapshots().getFirst().state());
                assertThrows(ApiException.class, runtime::close);
                untrap(db);
                assertEquals(TaskState.ENDED,
                    runtime.reconcile(id).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            }
        }
    }
}

package gold.debug.windowstolinux.app.service.task;

import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.PROTECTION;
import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.result;
import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.security;
import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.state;
import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.success;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskArchiveQueryTest {
    @TempDir
    Path root;

    private static TaskRequest request() {
        return new TaskRequest("archive-query", "archive proof", List.of(), true);
    }

    @Test
    void exactOwnerVersionCanReadArchivedProofButCannotReuseEndedContext() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var endedContext = new AtomicReference<TaskContext>();
            var original = service.submit(request(), task -> {
                endedContext.set(task);
                return success("original");
            });
            assertEquals(TaskResult.SUCCEEDED, result(original).outcome());
            var query = service.submit(request(), current -> {
                var proof = service.findArchived(original.id(), current).orElseThrow();
                assertEquals(original.id(), proof.id());
                assertEquals(TaskState.ENDED, proof.state());
                assertEquals(Optional.of(TaskResult.SUCCEEDED), proof.outcome());
                assertTrue(proof.blockers().isEmpty());
                assertTrue(service.findArchived(UUID.randomUUID(), current).isEmpty());
                assertThrows(ApiException.class, () -> service.findArchived(original.id(), endedContext.get()));
                return success(null);
            });
            assertEquals(TaskResult.SUCCEEDED, result(query).outcome());
            assertThrows(ApiException.class, () -> service.findArchived(original.id(), endedContext.get()));
            for (var binding : List.of(runtime.bind("example.other", "1.0.0", false), runtime.bind("example.owner", "2.0.0", false))) {
                var foreign = binding.submit(request(), current -> {
                    assertTrue(binding.findArchived(original.id(), current).isEmpty());
                    return success(null);
                });
                assertEquals(TaskResult.SUCCEEDED, result(foreign).outcome());
            }
        }
    }

    @Test
    void archiveProofSurvivesRestartWithoutExecutingOriginalAgain() throws Exception {
        UUID original;
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var task = runtime.bind("example.owner", "1.0.0", false).submit(request(), ignored -> success("original"));
            assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
            original = task.id();
        }
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            assertTrue(runtime.snapshots().isEmpty());
            var service = runtime.bind("example.owner", "1.0.0", false);
            var query = service.submit(request(), current -> {
                assertEquals(original, service.findArchived(original, current).orElseThrow().id());
                return success(null);
            });
            assertEquals(TaskResult.SUCCEEDED, result(query).outcome());
        }
    }

    @Test
    void unknownExecutionHasNoArchivedProof() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var original = service.submit(request(), task -> new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_UNKNOWN", "retained", Map.of(), Optional.empty()))));
            state(original, TaskState.UNKNOWN);
            var query = service.submit(request(), current -> {
                assertTrue(service.findArchived(original.id(), current).isEmpty());
                return success(null);
            });
            assertEquals(TaskResult.SUCCEEDED, result(query).outcome());
            assertEquals(TaskState.UNKNOWN, original.snapshot().state());
            try (var recovery = service.registerRecovery("archive-query", (previous, task) -> success(null))) {
                assertEquals(TaskState.ENDED,
                    runtime.reconcile(original.id()).toCompletableFuture().get(8, java.util.concurrent.TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void prunedOrTruncatedArchiveCannotAuthorizeHistoryCleanup() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var original = service.submit(request(), task -> success(null));
            result(original);
            try (var connection = db.openConnection();
                 var update = connection.prepareStatement("UPDATE runtime_log SET truncated=TRUE WHERE task_id=?")) {
                update.setObject(1, original.id());
                assertEquals(1, update.executeUpdate());
            }
            var query = service.submit(request(), current -> {
                assertTrue(service.findArchived(original.id(), current).isEmpty());
                return success(null);
            });
            assertEquals(TaskResult.SUCCEEDED, result(query).outcome());
            try (var connection = db.openConnection();
                 var remove = connection.prepareStatement("DELETE FROM runtime_log WHERE task_id=?")) {
                remove.setObject(1, original.id());
                assertEquals(1, remove.executeUpdate());
            }
            var afterPruning = service.submit(request(), current -> {
                assertTrue(service.findArchived(original.id(), current).isEmpty());
                return success(null);
            });
            assertEquals(TaskResult.SUCCEEDED, result(afterPruning).outcome());
        }
    }

    @Test
    void contradictoryArchiveIsRejectedWithoutChangingIt() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var original = service.submit(request(), task -> success(null));
            result(original);
            try (var connection = db.openConnection();
                 var update = connection.prepareStatement("UPDATE runtime_log SET event_code='PLATFORM_TASK_FAILED' WHERE task_id=?")) {
                update.setObject(1, original.id());
                assertEquals(1, update.executeUpdate());
            }
            var query = service.submit(request(), current -> {
                var failure = assertThrows(ApiException.class, () -> service.findArchived(original.id(), current));
                assertEquals("TASK_ARCHIVE_OBSERVATION", failure.problem().eventCode());
                return success(null);
            });
            assertEquals(TaskResult.SUCCEEDED, result(query).outcome());
        }
    }
}

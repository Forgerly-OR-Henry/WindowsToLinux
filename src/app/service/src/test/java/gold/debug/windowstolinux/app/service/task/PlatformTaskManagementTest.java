package gold.debug.windowstolinux.app.service.task;

import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.service.task.TaskManagement.RecoveryState;
import gold.debug.windowstolinux.app.service.task.TaskManagement;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskManagementTest {
    @TempDir
    Path root;

    static TaskRequest request(String type, boolean cancellable, String... servers) {
        return new TaskRequest(type, "task management fixture", List.of(servers), cancellable);
    }

    static OperationResult<Void> unknown() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNAVAILABLE,
            "FIXTURE_UNCONFIRMED", "Original result is unconfirmed", Map.of(), Optional.empty())));
    }

    static TaskManagement.Entry entry(TaskManagement service, UUID id) throws Exception {
        return service.list().stream().filter(item -> item.snapshot().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void queuedCancellationNeverRunsWorkAndRunningCancellationWaitsForActualReturn() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = new PlatformTaskManagement(runtime, security, id -> {
                throw new AssertionError();
            }, () -> {
            });
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var tasks = runtime.bind("example.owner", "1.0.0", false);
            var running = tasks.submit(request("fixture", true, "server"), task -> {
                entered.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
                assertTrue(task.cancelToken().requested());
                return new OperationResult<Void>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            });
            try {
                assertTrue(entered.await(8, TimeUnit.SECONDS));
                var queued = tasks.submit(request("fixture", true, "server"), task -> {
                    throw new AssertionError();
                });
                assertEquals(TaskState.QUEUED, entry(service, queued.id()).snapshot().state());
                assertEquals(TaskState.ENDED, service.requestCancel(queued.id()).state());
                assertEquals(TaskResult.CANCELLED, result(queued).outcome());
                assertTrue(entry(service, running.id()).cancellable());
                assertEquals(TaskState.CANCEL_REQUESTED, service.requestCancel(running.id()).state());
                assertFalse(running.completion().toCompletableFuture().isDone());
                assertFalse(entry(service, running.id()).cancellable());
                assertEquals(RecoveryState.NOT_READY, entry(service, running.id()).recovery());
                assertThrows(ApiException.class, () -> service.requestCancel(running.id()));
                assertThrows(ApiException.class, () -> service.reconcile(running.id(), false));
            } finally {
                release.countDown();
            }
            assertEquals(TaskResult.CANCELLED, result(running).outcome());
            assertTrue(service.list().isEmpty());
            assertEquals(0, security.securityState().activeTasks());
        }
    }

    @Test
    void nonCancellableAndFinishedTasksCannotBeCancelledThroughHostControls() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = new PlatformTaskManagement(runtime, security, id -> {
                throw new AssertionError();
            }, () -> {
            });
            var release = new CountDownLatch(1);
            var task = runtime.bind("example.owner", "1.0.0", false).submit(request("fixture", false), context -> {
                assertTrue(release.await(8, TimeUnit.SECONDS));
                return success("original");
            });
            try {
                assertFalse(entry(service, task.id()).cancellable());
                assertThrows(ApiException.class, () -> service.requestCancel(task.id()));
            } finally {
                release.countDown();
            }
            assertEquals("original", result(task).value().orElseThrow());
            assertThrows(ApiException.class, () -> service.requestCancel(task.id()));
            assertThrows(ApiException.class, () -> service.reconcile(task.id(), false));
        }
    }

    @Test
    void onlyExactOriginalRecoveryCanCloseTheRetainedTaskAndItsResource() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = new PlatformTaskManagement(runtime, security, id -> {
                throw new AssertionError();
            }, () -> {
            });
            var resource = new AtomicReference<ResourceLease>();
            var task = runtime.bind("example.owner", "1.0.0", false).submit(request("fixture", true), context -> {
                resource.set(context.retain("file", "original-file", "original resource"));
                return unknown();
            });
            state(task, TaskState.UNKNOWN);
            assertEquals(RecoveryState.UNAVAILABLE, entry(service, task.id()).recovery());
            assertTrue(entry(service, task.id()).snapshot().blockers().stream()
                .anyMatch(s -> s.contains("original-file")));
            assertThrows(ApiException.class, () -> service.requestCancel(task.id()));
            try (var wrong = runtime.bind("example.owner", "2.0.0", true).registerRecovery("fixture",
                (before, context) -> {
                    throw new AssertionError();
                })) {
                assertEquals(RecoveryState.UNAVAILABLE, entry(service, task.id()).recovery());
                assertThrows(ApiException.class, () -> service.reconcile(task.id(), false));
                var calls = new AtomicInteger();
                try (var original = runtime.bind("example.owner", "1.0.0", true).registerRecovery("fixture",
                    (before, context) -> {
                        calls.incrementAndGet();
                        assertEquals(task.id(), context.id());
                        resource.get().close();
                        return success(null);
                    })) {
                    assertEquals(RecoveryState.RECONCILE, entry(service, task.id()).recovery());
                    assertEquals(TaskState.ENDED,
                        service.reconcile(task.id(), false).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                    assertEquals(1, calls.get());
                    assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
                    assertTrue(result(task).value().isEmpty());
                }
            }
            assertTrue(service.list().isEmpty());
            assertEquals(0, PlatformTaskArchivalTest.count(db, "SELECT COUNT(*) FROM platform_task_resource"));
        }
    }

    @Test
    void metadataOnlyRescueFinalizationRequiresNoCommandConfirmationOrRecoveryCallback() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = new PlatformTaskManagement(runtime, security, id -> {
                throw new AssertionError();
            }, () -> {
            });
            PlatformTaskArchivalTest.trap(db);
            var calls = new AtomicInteger();
            var task = runtime.bind(PlatformTaskAdmission.PLATFORM_OWNER, "1.0.0", false)
                .submit(request(PlatformTaskAdmission.RESCUE_TYPE, true), context -> {
                    calls.incrementAndGet();
                    return success("original");
                });
            state(task, TaskState.FINISHING);
            assertEquals(RecoveryState.ARCHIVE_ONLY, entry(service, task.id()).recovery());
            assertFalse(entry(service, task.id()).cancellable());
            PlatformTaskArchivalTest.untrap(db);
            assertEquals(TaskResult.SUCCEEDED, service.reconcile(task.id(), false).toCompletableFuture()
                .get(8, TimeUnit.SECONDS).outcome().orElseThrow());
            assertEquals(1, calls.get());
            assertTrue(service.list().isEmpty());
        }
    }

    @Test
    void rescueRequiresConfirmationButAnotherOwnersSameTaskTypeUsesItsOriginalRecovery() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var routed = new ArrayList<UUID>();
            var service = new PlatformTaskManagement(runtime, security, id -> {
                routed.add(id);
                return runtime.reconcile(id);
            }, () -> {
            });
            for (String owner : List.of(PlatformTaskAdmission.PLATFORM_OWNER, "example.owner")) {
                var tasks = runtime.bind(owner, "1.0.0", false);
                var task = tasks.submit(request(PlatformTaskAdmission.RESCUE_TYPE, true), context -> unknown());
                state(task, TaskState.UNKNOWN);
                try (var registration = tasks.registerRecovery(PlatformTaskAdmission.RESCUE_TYPE,
                    (previous, context) -> new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNAVAILABLE, "FIXTURE_RECOVERED",
                            "Original operation was interrupted", Map.of(), Optional.empty()))))) {
                    if (owner.equals(PlatformTaskAdmission.PLATFORM_OWNER)) {
                        assertEquals(RecoveryState.CONFIRM_RESCUE, entry(service, task.id()).recovery());
                        assertThrows(ApiException.class, () -> service.reconcile(task.id(), false));
                        assertTrue(routed.isEmpty());
                        assertEquals(TaskState.ENDED, service.reconcile(task.id(), true).toCompletableFuture()
                            .get(8, TimeUnit.SECONDS).state());
                        assertEquals(List.of(task.id()), routed);
                    } else {
                        assertEquals(RecoveryState.RECONCILE, entry(service, task.id()).recovery());
                        assertEquals(TaskState.ENDED, service.reconcile(task.id(), false).toCompletableFuture()
                            .get(8, TimeUnit.SECONDS).state());
                        assertEquals(1, routed.size());
                    }
                }
            }
        }
    }

    @Test
    void lockedDesktopRejectsInventoryAndActionsWhileOriginalWorkCanFinish() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = new PlatformTaskManagement(runtime, security, id -> {
                throw new AssertionError();
            }, () -> {
            });
            var release = new CountDownLatch(1);
            var task = runtime.bind("example.owner", "1.0.0", false).submit(request("fixture", true), context -> {
                assertTrue(release.await(8, TimeUnit.SECONDS));
                return success("retained scope");
            });
            try {
                security.lock();
                assertThrows(ApiException.class, service::list);
                assertThrows(ApiException.class, () -> service.requestCancel(task.id()));
                assertThrows(ApiException.class, () -> service.reconcile(task.id(), false));
            } finally {
                release.countDown();
            }
            assertEquals("retained scope", result(task).value().orElseThrow());
            security.unlock("fixture-password".toCharArray());
            assertTrue(service.list().isEmpty());
            javax.swing.SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, service::list));
        }
    }

    @Test
    void concurrentReconciliationKeepsControlsBusyUntilOriginalAttemptReturns() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = new PlatformTaskManagement(runtime, security, id -> {
                throw new AssertionError();
            }, () -> {
            });
            var tasks = runtime.bind("example.owner", "1.0.0", false);
            var task = tasks.submit(request("fixture", true), context -> unknown());
            state(task, TaskState.UNKNOWN);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var registration = tasks.registerRecovery("fixture", (previous, context) -> {
                entered.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
                return success(null);
            })) {
                var completion = service.reconcile(task.id(), false);
                try {
                    assertTrue(entered.await(8, TimeUnit.SECONDS));
                    assertEquals(RecoveryState.NOT_READY, entry(service, task.id()).recovery());
                    assertThrows(ApiException.class, () -> service.reconcile(task.id(), false));
                    assertThrows(ApiException.class, () -> service.requestCancel(task.id()));
                    assertFalse(completion.toCompletableFuture().isDone());
                } finally {
                    release.countDown();
                }
                assertEquals(TaskState.ENDED, completion.toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertEquals(0, security.securityState().activeTasks());
            }
        }
    }
}

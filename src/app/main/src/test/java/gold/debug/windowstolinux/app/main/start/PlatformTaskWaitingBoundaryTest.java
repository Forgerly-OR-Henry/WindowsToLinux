package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskWaitingBoundaryTest {
    @TempDir
    Path root;

    static PlatformTaskManagement management(PlatformRuntime runtime) {
        return new PlatformTaskManagement(runtime.tasks(), runtime.security(), id -> {
            throw new AssertionError("Waiting is not a rescue task");
        }, () -> {
        });
    }

    @Test
    void originalWaitRejectsForgedOwnershipAndOnlyOneConcurrentContinuationWins() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var context = new AtomicReference<TaskContext>();
            var calls = new AtomicInteger();
            var resumed = new AtomicInteger();
            var finish = new CountDownLatch(1);
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                calls.incrementAndGet();
                context.set(task);
                var forged = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
                    new Class<?>[]{TaskContext.class}, (proxy, method, args) ->
                        method.getName().equals("id") ? task.id() : method.invoke(task, args));
                assertEquals("TASK_CONTEXT", assertThrows(ApiException.class,
                    () -> runtime.tasks().awaitContinue(forged, OWNER, VERSION, "forged")).problem().eventCode());
                assertEquals("TASK_CALLER", assertThrows(ApiException.class,
                    () -> runtime.tasks().awaitContinue(task, "foreign.owner", VERSION, "foreign")).problem().eventCode());
                assertTrue(runtime.tasks().awaitContinue(task, OWNER, VERSION, "等待重新确认删除"));
                resumed.incrementAndGet();
                assertTrue(finish.await(10, TimeUnit.SECONDS));
                return success(null);
            });
            var executor = Executors.newFixedThreadPool(2);
            try {
                state(handle, TaskState.WAITING);
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals("TASK_WAITING", assertThrows(ApiException.class,
                    () -> runtime.tasks().requireExecuting(context.get(), OWNER, VERSION)).problem().eventCode());
                assertThrows(ApiException.class, () -> context.get().retain("file", "new-file", "new file"));
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind IN ('CONNECTION','CUSTOM') AND released_at IS NULL"));
                var service = management(runtime);
                runtime.security().lock();
                assertEquals("TASK_SECURITY_LOCKED", assertThrows(ApiException.class,
                    () -> service.continueWaiting(handle.id())).problem().eventCode());
                runtime.security().unlock(PASSWORD.toCharArray());
                assertEquals("TASK_WAIT_UNAVAILABLE", assertThrows(ApiException.class,
                    () -> service.continueWaiting(UUID.randomUUID())).problem().eventCode());
                var start = new CountDownLatch(1);
                var ready = new CountDownLatch(2);
                Callable<Boolean> continueOnce = () -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        service.continueWaiting(handle.id());
                        return true;
                    } catch (ApiException rejected) {
                        assertEquals("TASK_WAIT_UNAVAILABLE", rejected.problem().eventCode());
                        return false;
                    }
                };
                var first = executor.submit(continueOnce);
                var second = executor.submit(continueOnce);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
                state(handle, TaskState.RUNNING);
                finish.countDown();
                assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                assertEquals(1, calls.get());
                assertEquals(1, resumed.get());
                assertThrows(ApiException.class, () -> service.continueWaiting(handle.id()));
            } finally {
                finish.countDown();
                if (!handle.completion().toCompletableFuture().isDone()) handle.requestCancel();
                handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void actualTaskPageContinuesOrTerminatesTheOriginalLiveWaitWithoutCallbackReplay() throws Exception {
        for (boolean continueTask : List.of(true, false)) {
            try (var runtime = PlatformRuntime.open(root.resolve(Boolean.toString(continueTask)), PlatformStartupTest.PROTECTION)) {
                initialize(runtime, false);
                var calls = new AtomicInteger();
                var actions = new AtomicInteger();
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    calls.incrementAndGet();
                    if (!runtime.tasks().awaitContinue(task, OWNER, VERSION, "用户拒绝删除，等待继续或终止"))
                        return new OperationResult<Void>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                    actions.incrementAndGet();
                    return success(null);
                });
                state(handle, TaskState.WAITING);
                var frame = PlatformTaskManagementWindowTest.window(runtime, management(runtime));
                try {
                    PlatformTaskManagementWindowTest.select(frame);
                    onEdt(() -> {
                        assertTrue(named(frame, "tasks.continue").isEnabled());
                        assertTrue(named(frame, "tasks.cancel").isEnabled());
                        assertFalse(named(frame, "tasks.reconcile").isEnabled());
                        assertTrue(((JTextArea) named(frame, "tasks.details")).getText().contains(handle.id().toString()));
                        return null;
                    });
                    if (continueTask) PlatformTaskManagementWindowTest.click(frame, "tasks.continue");
                    else PlatformTaskManagementWindowTest.answer(
                        PlatformTaskManagementWindowTest.confirm(frame, "tasks.cancel"), JOptionPane.OK_OPTION);
                    assertEquals(continueTask ? TaskResult.SUCCEEDED : TaskResult.CANCELLED,
                        handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                    PlatformTaskManagementWindowTest.idle(frame);
                    assertEquals(1, calls.get());
                    assertEquals(continueTask ? 1 : 0, actions.get());
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
                } finally {
                    PlatformTaskManagementWindowTest.dispose(frame);
                    if (!handle.completion().toCompletableFuture().isDone()) {
                        handle.requestCancel();
                        handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    @Test
    void disposingTheTaskPageDoesNotResumeOrCancelAcceptedWaitingWork() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var actions = new AtomicInteger();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                if (runtime.tasks().awaitContinue(task, OWNER, VERSION, "等待用户决定")) actions.incrementAndGet();
                return new OperationResult<Void>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            });
            state(handle, TaskState.WAITING);
            var frame = PlatformTaskManagementWindowTest.window(runtime, management(runtime));
            try {
                PlatformTaskManagementWindowTest.select(frame);
                PlatformTaskManagementWindowTest.dispose(frame);
                assertEquals(TaskState.WAITING, handle.snapshot().state());
                assertEquals(0, actions.get());
                assertFalse(handle.completion().toCompletableFuture().isDone());
            } finally {
                PlatformTaskManagementWindowTest.dispose(frame);
                handle.requestCancel();
                handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void persistedWaitAfterRestartRequiresOriginalRecoveryAndCannotContinueAnAbsentCallback() throws Exception {
        UUID id;
        try (var database = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION)) {
            var persistence = new PlatformStorage(database);
            try (var security = new DesktopSecurityService(persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            register(database, null);
            var ledger = new TaskLedger(database);
            id = ledger.enqueue(UUID.randomUUID(), OWNER, VERSION, request(),
                List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.tryStart(id));
            ledger.waiting(id, true, "原用户等待");
            // Historical ledger has no surviving callback; restart may only reconcile it. / 历史账本没有存活回调，重启后只能核对。
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var service = management(runtime);
            assertEquals(TaskState.UNKNOWN, service.list().getFirst().snapshot().state());
            assertEquals("TASK_WAIT_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service.continueWaiting(id)).problem().eventCode());
            var recovered = new AtomicInteger();
            try (var registration = runtime.tasks().bind(OWNER, VERSION, true).registerRecovery("fixture", (previous, task) -> {
                recovered.incrementAndGet();
                assertEquals(id, task.id());
                return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                    ProblemCode.UNAVAILABLE, "WAIT_INTERRUPTED", "The previous callback no longer exists", Map.of(), Optional.empty())));
            })) {
                assertEquals(TaskState.ENDED, service.reconcile(id, false).toCompletableFuture().get(5, TimeUnit.SECONDS).state());
                assertEquals(1, recovered.get());
                assertTrue(service.list().isEmpty());
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
            }
        }
    }
}

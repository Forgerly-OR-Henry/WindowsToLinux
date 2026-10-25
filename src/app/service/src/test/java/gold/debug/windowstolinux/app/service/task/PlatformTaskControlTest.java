package gold.debug.windowstolinux.app.service.task;

import static gold.debug.windowstolinux.app.service.task.PlatformTaskRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskControlTest {
    @TempDir Path root;

    @Test void interventionDoesNotAcceptAUserDecisionAndClosedScopesCannotControl() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION); var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var tasks = runtime.bind("example.owner", "1.0.0", false);
            var calls = new AtomicInteger();
            try (var registration = tasks.registerController("fixture", new TaskController() {
                public TaskControlSnapshot snapshot(UUID id) {
                    return new TaskControlSnapshot(TaskControlSnapshot.State.RUNNING, true, false, true, "");
                }
                public void request(UUID id, TaskCommand command) { assertEquals(TaskCommand.INTERVENE, command); calls.incrementAndGet(); }
            })) {
                var task = tasks.submit(new TaskRequest("fixture", "pending decision", List.of(), true), context -> {
                    assertTrue(runtime.awaitContinue(context, "example.owner", "1.0.0", "Choose an action"));
                    return PlatformTaskRuntimeTest.success(null);
                });
                try {
                    state(task, TaskState.WAITING);
                    var service = new PlatformTaskManagement(runtime, security, id -> { throw new AssertionError(); }, () -> {});
                    assertThrows(ApiException.class, () -> service.control(task.id(), TaskCommand.PAUSE));
                    assertThrows(ApiException.class, () -> service.scoped("example.owner", "1.0.0", () -> false)
                        .control(task.id(), TaskCommand.INTERVENE));
                    service.control(task.id(), TaskCommand.INTERVENE);
                    assertEquals(1, calls.get()); assertEquals(TaskState.WAITING, task.snapshot().state());
                    service.continueWaiting(task.id());
                    result(task);
                } finally { if (!task.completion().toCompletableFuture().isDone()) task.requestCancel(); }
            }
        }
    }

    @Test void controlsAreOptionalExactReleaseBoundAndDoNotHoldRuntimeMonitor() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION); var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var tasks = runtime.bind("example.owner", "1.0.0", false);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var state = new AtomicReference<>(TaskControlSnapshot.State.RUNNING);
            var intervention = new AtomicInteger();
            var id = new AtomicReference<UUID>();
            var service = new PlatformTaskManagement(runtime, security, ignored -> { throw new AssertionError(); }, () -> {});
            var controller = new TaskController() {
                public TaskControlSnapshot snapshot(UUID task) {
                    assertFalse(Thread.holdsLock(runtime));
                    assertEquals(id.get(), task);
                    return new TaskControlSnapshot(state.get(), state.get() == TaskControlSnapshot.State.RUNNING,
                        state.get() == TaskControlSnapshot.State.PAUSED, true, "");
                }
                public void request(UUID task, TaskCommand command) {
                    assertFalse(Thread.holdsLock(runtime));
                    if (command == TaskCommand.INTERVENE) intervention.incrementAndGet();
                    else state.set(command == TaskCommand.PAUSE ? TaskControlSnapshot.State.PAUSE_REQUESTED : TaskControlSnapshot.State.RUNNING);
                }
            };
            var task = tasks.submit(new TaskRequest("fixture", "original task", List.of(), true), context -> {
                context.progress("known stage", .4);
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            id.set(task.id());
            try {
                assertTrue(entered.await(8, TimeUnit.SECONDS));
                assertFalse(service.list().getFirst().view().controls().pause());
                try (var registration = tasks.registerController("fixture", controller)) {
                    var scoped = service.scoped("example.owner", "1.0.0", () -> true);
                    var foreign = service.scoped("example.owner", "2.0.0", () -> true);
                    assertTrue(foreign.list().isEmpty());
                    assertThrows(ApiException.class, () -> foreign.control(task.id(), TaskCommand.PAUSE));
                    assertThrows(ApiException.class, () -> foreign.readLogs(task.id()));
                    assertEquals(.4, scoped.list().getFirst().view().fraction().orElseThrow());
                    scoped.control(task.id(), TaskCommand.PAUSE);
                    assertEquals(TaskViewSnapshot.Category.RUNNING, scoped.list().getFirst().view().category());
                    assertThrows(ApiException.class, () -> scoped.control(task.id(), TaskCommand.RESUME));
                    state.set(TaskControlSnapshot.State.PAUSED);
                    assertEquals(TaskViewSnapshot.Category.WAITING_RECOVERY, scoped.list().getFirst().view().category());
                    scoped.control(task.id(), TaskCommand.INTERVENE);
                    assertEquals(1, intervention.get());
                    assertEquals(TaskControlSnapshot.State.PAUSED, state.get());
                    scoped.control(task.id(), TaskCommand.RESUME);
                    assertEquals(TaskState.RUNNING, task.snapshot().state());
                    assertThrows(ApiException.class, () -> scoped.control(task.id(), TaskCommand.RESUME));
                }
                assertFalse(service.list().getFirst().view().controls().pause());
            } finally { release.countDown(); }
            result(task);
            assertThrows(ApiException.class, () -> service.control(task.id(), TaskCommand.PAUSE));
        }
    }

    @Test void unknownProgressAndUnavailableRecoveryRemainHonest() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION); var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var task = runtime.bind("example.owner", "1.0.0", false).submit(
                new TaskRequest("fixture", "uncertain original", List.of(), true), context -> {
                    context.progress("known", .8);
                    context.progress("total unknown");
                    return PlatformTaskManagementTest.unknown();
                });
            state(task, TaskState.UNKNOWN);
            var service = new PlatformTaskManagement(runtime, security, ignored -> { throw new AssertionError(); }, () -> {});
            var entry = service.list().getFirst();
            assertTrue(entry.view().fraction().isEmpty());
            assertEquals(TaskViewSnapshot.Category.WAITING_RECOVERY, entry.view().category());
            assertEquals(TaskManagement.RecoveryState.UNAVAILABLE, entry.recovery());
            assertThrows(ApiException.class, () -> service.control(task.id(), TaskCommand.RESUME));
            assertEquals(TaskState.UNKNOWN, task.snapshot().state());
            try (var recovery = runtime.bind("example.owner", "1.0.0", true).registerRecovery("fixture",
                (previous, original) -> PlatformTaskRuntimeTest.success(null))) {
                assertEquals(TaskState.ENDED, runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
        }
    }
}

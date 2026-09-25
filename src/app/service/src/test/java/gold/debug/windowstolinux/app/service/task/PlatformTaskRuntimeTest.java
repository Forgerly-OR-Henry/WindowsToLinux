package gold.debug.windowstolinux.app.service.task;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.api.task.*;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskRuntimeTest {
    @TempDir
    Path root;

    static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            var copy = input.clone();
            for (int i = 0; i < copy.length; i++)
                copy[i] ^= 0x39;
            return copy;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };
    static List<TaskResource> claims(String owner, String version, TaskRequest request) {
        var result = new ArrayList<TaskResource>();
        result.add(new TaskResource("MOD", owner, owner, false));
        for (var server : request.serverIds())
            result.add(new TaskResource("SERVER", server, owner, true));
        return result;
    }

    TaskRequest request(String... servers) {
        return new TaskRequest("fixture", "本机任务", List.of(servers), true);
    }

    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }

    static void state(TaskHandle<?> task, TaskState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (task.snapshot().state() != expected && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertEquals(expected, task.snapshot().state(), task.snapshot().toString());
    }

    static <T> OperationResult<T> result(TaskHandle<T> task) throws Exception {
        return task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    @Test
    void handleCompletesOnlyAfterExplicitResourceReleaseAndContextCannotBeForged() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            var resource = new AtomicReference<ResourceLease>();
            var context = new AtomicReference<TaskContext>();
            var task = runtime.bind("example.owner", "1.0.0", false).submit(request("A"), active -> {
                assertFalse(javax.swing.SwingUtilities.isEventDispatchThread());
                context.set(active);
                resource.set(active.retain("file", "fixture", "本机句柄"));
                assertThrows(ApiException.class, () -> active.retain("file", "fixture", "重复身份"));
                assertThrows(ApiException.class, () -> active.progress("bad", Optional.of(Double.NaN)));
                active.progress("等待资源释放", Optional.of(1.0));
                return success("payload");
            });
            state(task, TaskState.FINISHING);
            assertFalse(task.completion().toCompletableFuture().isDone());
            assertEquals(1, task.snapshot().blockers().size());
            TaskContext forged = new TaskContext() {
                public UUID id() {
                    return task.id();
                }

                public CancellationToken cancellation() {
                    return () -> false;
                }

                public void progress(String text, Optional<Double> fraction) {
                }

                public ResourceLease retain(String kind, String id, String description) {
                    return null;
                }
            };
            assertThrows(ApiException.class, () -> runtime.requireContext(forged));
            assertEquals(task.id(), runtime.requireContext(context.get()).id());
            assertThrows(ApiException.class, runtime::close);
            resource.get().close();
            assertEquals("payload", result(task).value().orElseThrow());
            state(task, TaskState.ENDED);
            assertThrows(ApiException.class, () -> runtime.requireContext(context.get()));
            resource.get().close();
        }
    }

    @Test
    void queuedTaskHasAtomicServerAdmissionAndOtherServerWorkCanProceed() throws Exception {
        var unblock = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var started = new CountDownLatch(1);
            var first = service.submit(request("A"), task -> {
                started.countDown();
                assertTrue(unblock.await(8, TimeUnit.SECONDS));
                return success("one");
            });
            assertTrue(started.await(8, TimeUnit.SECONDS));
            var combined = service.submit(request("B", "A"), task -> success("combined"));
            state(combined, TaskState.QUEUED);
            var other = runtime.bind("example.other", "1.0.0", false).submit(request("B"), task -> success("other"));
            assertEquals("other", result(other).value().orElseThrow());
            state(combined, TaskState.QUEUED);
            var visible = service.list(Optional.empty(), 200).items();
            assertTrue(visible.stream().allMatch(s -> s.ownerId().equals("example.owner")));
            assertEquals(2, visible.size());
            unblock.countDown();
            result(first);
            assertEquals("combined", result(combined).value().orElseThrow());
            assertTrue(service.list(Optional.empty(), 200).items().isEmpty());
        } finally {
            unblock.countDown();
        }
    }

    @Test
    void cancellationIsCooperativeAndNeverRunsCancelledQueuedCallbacks() throws Exception {
        var release = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var entered = new CountDownLatch(1);
            var ran = new AtomicBoolean();
            var running = service.submit(request("A"), task -> {
                entered.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
                assertFalse(Thread.currentThread().isInterrupted());
                assertTrue(task.cancellation().requested());
                return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
            });
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            var queued = service.submit(request("A"), task -> {
                ran.set(true);
                return success(null);
            });
            queued.requestCancel();
            assertEquals(Outcome.CANCELLED, result(queued).outcome());
            assertFalse(ran.get());
            running.requestCancel();
            state(running, TaskState.CANCEL_REQUESTED);
            assertFalse(running.completion().toCompletableFuture().isDone());
            release.countDown();
            assertEquals(Outcome.CANCELLED, result(running).outcome());
        } finally {
            release.countDown();
        }
    }

    @Test
    void callbackFailureStaysUnknownAndRecoveryReusesIdentityWithoutReplay() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var invocations = new AtomicInteger();
            var originalLease = new AtomicReference<UUID>();
            var task = service.<String>submit(request("A"), active -> {
                invocations.incrementAndGet();
                originalLease.set(active.retain("file", "pending", "待核实句柄").id());
                        throw new AssertionError("fixture-secret-never-log");
            });
            state(task, TaskState.UNKNOWN);
            assertFalse(task.completion().toCompletableFuture().isDone());
            assertThrows(ApiException.class, () -> runtime.reconcile(task.id()));
            var waiting = runtime.bind("example.other", "1.0.0", false).submit(request("A"),
                    active -> success("resumed"));
            state(waiting, TaskState.QUEUED);
            try (var registration = service.registerRecovery("fixture", (previous, original) -> {
                assertEquals(task.id(), previous.id());
                assertEquals(task.id(), original.id());
                try (var resource = original.retain("file", "pending", "核实后释放")) {
                    assertEquals(originalLease.get(), resource.id());
                }
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                        runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(Outcome.SUCCEEDED, result(task).outcome());
            assertTrue(result(task).value().isEmpty());
            assertEquals(1, invocations.get());
            assertEquals("resumed", result(waiting).value().orElseThrow());
        }
    }

    @Test
    void previousBootRecoveryUsesOriginalVersionAndRejectsNewBusiness() throws Exception {
        UUID id = UUID.randomUUID();
        UUID originalLease;
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var ledger = new TaskLedger(db);
            var request = request("A");
            ledger.enqueue(id, "example.owner", "1.0.0", request, claims("example.owner", "1.0.0", request));
            assertTrue(ledger.tryStart(id));
            originalLease = ledger.retain(id,
                    new TaskResource("CUSTOM", "example.owner/file/pending", "example.owner", false));
            assertFalse(ledger.complete(id, Outcome.UNKNOWN, "{}", false));
        }
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            assertEquals(TaskState.UNKNOWN, runtime.snapshots().getFirst().state());
            var recoveryService = runtime.bind("example.owner", "1.0.0", true);
            assertThrows(ApiException.class, () -> recoveryService.submit(request(), task -> success(null)));
            try (var wrong = runtime.bind("example.owner", "2.0.0", true).registerRecovery("fixture",
                    (snapshot, task) -> success(null))) {
                assertThrows(ApiException.class, () -> runtime.reconcile(id));
            }
            try (var registered = recoveryService.registerRecovery("fixture", (snapshot, task) -> {
                assertEquals("1.0.0", snapshot.ownerVersion());
                try (var lease = task.retain("file", "pending", "已核实")) {
                    assertEquals(originalLease, lease.id());
                }
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                        runtime.reconcile(id).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void hostCanSealAdmissionAndFutureObservationCannotCompleteTheHostPromise() throws Exception {
        var release = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            runtime.seal(true);
            assertThrows(ApiException.class, () -> service.submit(request(), task -> success(null)));
            runtime.seal(false);
            var task = service.submit(request(), active -> {
                assertTrue(release.await(8, TimeUnit.SECONDS));
                return success("real");
            });
            var observer = task.completion().toCompletableFuture();
            observer.complete(success("forged"));
            assertFalse(task.completion().toCompletableFuture().isDone());
            release.countDown();
            assertEquals("real", result(task).value().orElseThrow());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedTerminalCommitRetainsUnknownAndNeverReplaysAuthorWork() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task ADD CONSTRAINT fixture_no_end CHECK(state<>'ENDED')");
            }
            var calls = new AtomicInteger();
            var service = runtime.bind("example.owner", "1.0.0", false);
            var task = service.submit(request("A"), active -> {
                calls.incrementAndGet();
                return success("committed business");
            });
            state(task, TaskState.UNKNOWN);
            var waiting = runtime.bind("example.other", "1.0.0", false).submit(request("A"),
                    active -> success("waiting"));
            state(waiting, TaskState.QUEUED);
            assertFalse(task.completion().toCompletableFuture().isDone());
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task DROP CONSTRAINT fixture_no_end");
            }
            try (var registration = service.registerRecovery("fixture", (previous, original) -> success(null))) {
                assertEquals(TaskState.ENDED,
                        runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(1, calls.get());
            assertTrue(result(task).value().isEmpty());
            assertEquals("waiting", result(waiting).value().orElseThrow());
        }
    }

    @Test
    void failedReleaseRecordPreservesFinishingUntilTheSameLeaseCanRetry() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
                var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims)) {
            var retained = new AtomicReference<ResourceLease>();
            var task = runtime.bind("example.owner", "1.0.0", false).submit(request("A"), active -> {
                retained.set(active.retain("file", "fixture", "fixture"));
                return success(null);
            });
            state(task, TaskState.FINISHING);
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute(
                        "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_no_release CHECK(released_at IS NULL)");
            }
            assertThrows(ApiException.class, retained.get()::close);
            assertEquals(TaskState.FINISHING, task.snapshot().state());
            assertFalse(task.completion().toCompletableFuture().isDone());
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_no_release");
            }
            retained.get().close();
            assertEquals(Outcome.SUCCEEDED, result(task).outcome());
        }
    }
}

package gold.debug.windowstolinux.app.service.task;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ResourceLease;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import gold.debug.windowstolinux.appapi.task.CancelToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskRuntimeTest {
    static gold.debug.windowstolinux.app.service.security.DesktopSecurityService security(PlatformDatabase db)
        throws Exception {
        var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
            new gold.debug.windowstolinux.app.db.security.SecurityRepository(
                new gold.debug.windowstolinux.app.db.connect.RepositoryConnections(
                    db::openConnection)));
        if (security.securityState().configured())
            security.unlock("fixture-password".toCharArray());
        else
            security.setupAndUnlock("fixture-password".toCharArray(), "fixture-password".toCharArray());
        return security;
    }

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

    static List<TaskResource> claims(java.sql.Connection connection, String owner, String version,
                                     TaskRequest request) {
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
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
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
            assertEquals(1, security.securityState().activeTasks());
            assertFalse(task.completion().toCompletableFuture().isDone());
            assertEquals(1, task.snapshot().blockers().size());
            TaskContext forged = new TaskContext() {
                public UUID id() {
                    return task.id();
                }

                public CancelToken cancelToken() {
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
            assertEquals(0, security.securityState().activeTasks());
            state(task, TaskState.ENDED);
            assertThrows(ApiException.class, () -> runtime.requireContext(context.get()));
            resource.get().close();
        }
    }

    @Test
    void inspectAuthenticatesOriginalOwnerOnlyDuringItsCallback() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var owner = runtime.bind("example.owner", "1.0.0", false);
            var other = runtime.bind("example.other", "1.0.0", false);
            var retained = new AtomicReference<TaskContext>();
            var handle = owner.submit(request("A"), task -> {
                retained.set(task);
                var snapshot = owner.inspect(task);
                assertEquals("example.owner", snapshot.ownerId());
                assertEquals("1.0.0", snapshot.ownerVersion());
                assertEquals(task.id(), snapshot.id());
                assertThrows(ApiException.class, () -> other.inspect(task));
                TaskContext forged = new TaskContext() {
                    public UUID id() {
                        return task.id();
                    }

                    public CancelToken cancelToken() {
                        return task.cancelToken();
                    }

                    public void progress(String text, Optional<Double> fraction) {
                    }

                    public ResourceLease retain(String kind, String id, String description) {
                        return null;
                    }
                };
                assertThrows(ApiException.class, () -> owner.inspect(forged));
                return success(null);
            });
            assertEquals(Outcome.SUCCEEDED, result(handle).outcome());
            assertThrows(ApiException.class, () -> owner.inspect(retained.get()));
        }
    }

    @Test
    void queuedTaskHasAtomicServerAdmissionAndOtherServerWorkCanProceed() throws Exception {
        var unblock = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
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
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var entered = new CountDownLatch(1);
            var ran = new AtomicBoolean();
            var running = service.submit(request("A"), task -> {
                entered.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
                assertFalse(Thread.currentThread().isInterrupted());
                assertTrue(task.cancelToken().requested());
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
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
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
            ledger.enqueue(id, "example.owner", "1.0.0", request, claims(null, "example.owner", "1.0.0", request));
            assertTrue(ledger.tryStart(id));
            originalLease = ledger.retain(id,
                new TaskResource("CUSTOM", "example.owner/file/pending", "example.owner", false));
            assertFalse(ledger.complete(id, Outcome.UNKNOWN, "{}", false));
        }
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            assertEquals(TaskState.UNKNOWN, runtime.snapshots().getFirst().state());
            assertEquals(1, security.securityState().activeTasks());
            assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                () -> security.setCredentialEncryption("fixture-password".toCharArray(), false));
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
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
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
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
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
            assertEquals(1, security.securityState().activeTasks());
            assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                () -> security.changeMasterPassword("fixture-password".toCharArray(),
                    "replacement-password".toCharArray(), "replacement-password".toCharArray()));
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
            assertEquals(0, security.securityState().activeTasks());
        }
    }

    @Test
    void failedReleaseRecordPreservesFinishingUntilTheSameLeaseCanRetry() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
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

    @Test
    void queuedAndRunningTasksKeepCredentialsAcrossLockWhileNewWorkIsRejected() throws Exception {
        var release = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            try (var operation = security.enterOperation(); var store = security.openCredentials()) {
                store.save("ssh/fixture", "wire-password".toCharArray());
            }
            var service = runtime.bind("example.owner", "1.0.0", false);
            var entered = new CountDownLatch(1);
            var running = service.submit(request("A"), context -> {
                entered.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
                try (var store = security.openCredentials()) {
                    char[] value = store.read("ssh/fixture").orElseThrow();
                    try {
                        assertArrayEquals("wire-password".toCharArray(), value);
                    } finally {
                        Arrays.fill(value, '\0');
                    }
                }
                return success(null);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = service.submit(request("A"), context -> {
                try (var store = security.openCredentials()) {
                    assertTrue(store.read("ssh/fixture").isPresent());
                }
                return success(null);
            });
            var cancelled = service.submit(request("A"), context -> {
                throw new AssertionError("Cancelled queued task ran");
            });
            assertEquals(3, security.securityState().activeTasks());
            security.lock();
            assertEquals(ProblemCode.ACCESS_DENIED,
                assertThrows(ApiException.class, () -> service.submit(request("B"), context -> success(null)))
                    .problem().code());
            cancelled.requestCancel();
            assertEquals(Outcome.CANCELLED, result(cancelled).outcome());
            assertEquals(2, security.securityState().activeTasks());
            release.countDown();
            assertEquals(Outcome.SUCCEEDED, result(running).outcome());
            assertEquals(Outcome.SUCCEEDED, result(queued).outcome());
            assertFalse(security.securityState().unlocked());
            assertEquals(0, security.securityState().activeTasks());
        } finally {
            release.countDown();
        }
    }

    @Test
    void unknownTaskKeepsAuthorizationUntilExplicitUnlockedRecoveryAndRejectsEndedCredentials() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var calls = new AtomicInteger();
            var task = service.submit(request("A"), context -> {
                calls.incrementAndGet();
                try (var store = security.openCredentials()) {
                    store.save("ssh/fixture", "retained".toCharArray());
                }
                throw new IOException("Unconfirmed business effect");
            });
            state(task, TaskState.UNKNOWN);
            assertEquals(1, security.securityState().activeTasks());
            try (var recovery = service.registerRecovery("fixture", (previous, original) -> {
                try (var store = security.openCredentials()) {
                    assertTrue(store.read("ssh/fixture").isPresent());
                }
                assertEquals(task.id(), original.id());
                return success(null);
            })) {
                security.lock();
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> runtime.reconcile(task.id())).problem().code());
                assertEquals(1, security.securityState().activeTasks());
                security.unlock("fixture-password".toCharArray());
                assertEquals(TaskState.ENDED,
                    runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(Outcome.SUCCEEDED, result(task).outcome());
            assertEquals(1, calls.get());
            assertEquals(0, security.securityState().activeTasks());
            assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                security::openCredentials);
        }
    }
}

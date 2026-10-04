package gold.debug.windowstolinux.app.service.task;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskRuntimeTest {
    static gold.debug.windowstolinux.app.service.security.DesktopSecurityService security(PlatformDatabase db)
        throws Exception {
        var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
            new gold.debug.windowstolinux.app.db.security.SecurityStore(
                new gold.debug.windowstolinux.app.db.connect.StoreConnections(
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
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
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
    void cursorSurvivesEarlierTaskCompletionAndExcludesLaterAdmissions() throws Exception {
        var releases = new ArrayList<CountDownLatch>();
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var handles = new ArrayList<TaskHandle<Void>>();
            for (int i = 0; i < 4; i++) {
                var release = new CountDownLatch(1);
                releases.add(release);
                handles.add(service.submit(request(), task -> {
                    assertTrue(release.await(8, TimeUnit.SECONDS));
                    return success(null);
                }));
            }
            var first = service.list(2);
            assertEquals(handles.subList(0, 2).stream().map(TaskHandle::id).toList(),
                first.items().stream().map(s -> s.id()).toList());
            releases.getFirst().countDown();
            result(handles.getFirst());
            var laterRelease = new CountDownLatch(1);
            releases.add(laterRelease);
            var later = service.submit(request(), task -> {
                assertTrue(laterRelease.await(8, TimeUnit.SECONDS));
                return success(null);
            });
            var second = service.list(first.nextCursor(), 2);
            assertEquals(handles.subList(2, 4).stream().map(TaskHandle::id).toList(),
                second.items().stream().map(s -> s.id()).toList());
            assertTrue(second.nextCursor().isEmpty());
            assertTrue(service.list(200).items().stream().anyMatch(s -> s.id().equals(later.id())));
            var anotherBinding = runtime.bind("example.owner", "1.0.0", false);
            assertThrows(ApiException.class, () -> anotherBinding.list(first.nextCursor(), 2));
            releases.forEach(CountDownLatch::countDown);
            for (var handle : handles) result(handle);
            result(later);
        } finally {
            releases.forEach(CountDownLatch::countDown);
        }
    }

    @Test
    void edtObservationsDoNotWaitForBlockedAdmission() throws Exception {
        var admissionEntered = new CountDownLatch(1);
        var admissionRelease = new CountDownLatch(1);
        var workRelease = new CountDownLatch(1);
        var admissions = new AtomicInteger();
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, (connection, owner, version, request) -> {
                 if (admissions.incrementAndGet() == 2) {
                     admissionEntered.countDown();
                     try {
                         if (!admissionRelease.await(8, TimeUnit.SECONDS))
                             throw new java.sql.SQLException("fixture timeout");
                     } catch (InterruptedException failure) {
                         throw new java.sql.SQLException(failure);
                     }
                 }
                 return claims(connection, owner, version, request);
             }, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var first = service.submit(request(), task -> {
                assertTrue(workRelease.await(8, TimeUnit.SECONDS));
                return success(null);
            });
            state(first, TaskState.RUNNING);
            var submitting = CompletableFuture.supplyAsync(() -> {
                try {
                    return service.submit(request(), task -> success(null));
                } catch (ApiException failure) {
                    throw new CompletionException(failure);
                }
            });
            assertTrue(admissionEntered.await(3, TimeUnit.SECONDS));
            var observed = new CompletableFuture<List<gold.debug.windowstolinux.appapi.task.model.TaskSnapshot>>();
            javax.swing.SwingUtilities.invokeLater(() -> observed.complete(runtime.snapshots()));
            assertTrue(observed.get(3, TimeUnit.SECONDS).stream().anyMatch(s -> s.id().equals(first.id())));
            admissionRelease.countDown();
            workRelease.countDown();
            result(first);
            result(submitting.get(8, TimeUnit.SECONDS));
            assertTrue(runtime.snapshots().stream().noneMatch(s -> s.id().equals(first.id())));
        } finally {
            admissionRelease.countDown();
            workRelease.countDown();
        }
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
            assertEquals(TaskResult.SUCCEEDED, result(handle).outcome());
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
                return new OperationResult<Void>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            });
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            var queued = service.submit(request("A"), task -> {
                ran.set(true);
                return success(null);
            });
            queued.requestCancel();
            assertEquals(TaskResult.CANCELLED, result(queued).outcome());
            assertFalse(ran.get());
            running.requestCancel();
            state(running, TaskState.CANCEL_REQUESTED);
            assertFalse(running.completion().toCompletableFuture().isDone());
            release.countDown();
            assertEquals(TaskResult.CANCELLED, result(running).outcome());
        } finally {
            release.countDown();
        }
    }

    @Test
    void callbackFailureStaysUnknownAndRecoveryReusesIdentityWithoutReplay() throws Exception {
        var originalFailure = new AtomicReference<Throwable>();
        var diagnostic = UUID.randomUUID();
        var recordedProblem = new AtomicReference<gold.debug.windowstolinux.appapi.task.result.Problem>();
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security, (failure, problem) -> {
                 originalFailure.set(failure);
                 recordedProblem.set(problem);
                 return Optional.of(diagnostic);
             })) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var invocations = new AtomicInteger();
            var originalLease = new AtomicReference<UUID>();
            var task = service.<String>submit(request("A"), active -> {
                invocations.incrementAndGet();
                originalLease.set(active.retain("file", "pending", "待核实句柄").id());
                throw new AssertionError("fixture-secret-never-log");
            });
            state(task, TaskState.UNKNOWN);
            assertInstanceOf(AssertionError.class, originalFailure.get());
            assertEquals(task.id().toString(), recordedProblem.get().context().get("taskId"));
            assertFalse(recordedProblem.get().message().contains("fixture-secret"));
            try (var connection = db.openConnection(); var query = connection.prepareStatement(
                "SELECT result_json FROM platform_task WHERE task_id=?")) {
                query.setObject(1, task.id());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertTrue(rows.getString(1).contains(diagnostic.toString()));
                }
            }
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
            assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
            assertTrue(result(task).value().isEmpty());
            assertEquals(1, invocations.get());
            assertEquals("resumed", result(waiting).value().orElseThrow());
        }
    }

    @Test
    void nonFatalErrorsKeepOriginalTasksRecoverableWithoutReplay() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            for (var failure : List.of(new ServiceConfigurationError("private fixture"), new Error("custom fixture"))) {
                var calls = new AtomicInteger();
                var lease = new AtomicReference<UUID>();
                var task = service.<Void>submit(request("A"), active -> {
                    calls.incrementAndGet();
                    lease.set(active.retain("file", "pending", "original resource").id());
                    throw failure;
                });
                state(task, TaskState.UNKNOWN);
                assertFalse(task.completion().toCompletableFuture().isDone());
                try (var registration = service.registerRecovery("fixture", (previous, original) -> {
                    assertEquals(task.id(), previous.id());
                    assertEquals(task.id(), original.id());
                    try (var held = original.retain("file", "pending", "original resource")) {
                        assertEquals(lease.get(), held.id());
                    }
                    return success(null);
                })) {
                    assertEquals(TaskState.ENDED,
                        runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                }
                assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
                assertEquals(1, calls.get());
            }
        }
    }

    @Test
    void recoveryErrorReleasesReconciliationGateAndAllowsExplicitRetry() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            var task = service.<Void>submit(request("A"), active -> {
                throw new Error("unknown original effect");
            });
            state(task, TaskState.UNKNOWN);
            var retries = new AtomicInteger();
            try (var registration = service.registerRecovery("fixture", (previous, original) -> {
                assertEquals(task.id(), original.id());
                if (retries.incrementAndGet() == 1)
                    throw new ServiceConfigurationError("unavailable recovery provider");
                return success(null);
            })) {
                assertEquals(TaskState.UNKNOWN,
                    runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertFalse(task.completion().toCompletableFuture().isDone());
                assertEquals(TaskState.ENDED,
                    runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(2, retries.get());
            assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
        }
    }

    @Test
    void fatalTaskErrorsPropagateAfterRetainingUnknownOriginalIdentity() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            for (var fatal : List.of(new InternalError("synthetic VM error"), new ThreadDeath())) {
                var escaped = new AtomicReference<Throwable>();
                var returned = new CountDownLatch(1);
                var task = service.<Void>submit(request("A"), active -> {
                    Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> {
                        escaped.set(failure);
                        returned.countDown();
                    });
                    throw fatal;
                });
                assertTrue(returned.await(8, TimeUnit.SECONDS));
                assertSame(fatal, escaped.get());
                state(task, TaskState.UNKNOWN);
                try (var registration = service.registerRecovery("fixture", (previous, original) -> {
                    assertEquals(task.id(), original.id());
                    return success(null);
                })) {
                    assertEquals(TaskState.ENDED,
                        runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                }
                assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
            }
        }
    }

    @Test
    void fatalRecoveryErrorsCompleteAttemptAndAllowOriginalRetry() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security)) {
            var service = runtime.bind("example.owner", "1.0.0", false);
            for (var fatal : List.of(new InternalError("synthetic VM error"), new ThreadDeath())) {
                var task = service.<Void>submit(request("A"), active -> {
                    throw new Error("unknown original effect");
                });
                state(task, TaskState.UNKNOWN);
                var attempts = new AtomicInteger();
                var escaped = new AtomicReference<Throwable>();
                var returned = new CountDownLatch(1);
                try (var registration = service.registerRecovery("fixture", (previous, original) -> {
                    if (attempts.incrementAndGet() == 1) {
                        Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> {
                            escaped.set(failure);
                            returned.countDown();
                        });
                        throw fatal;
                    }
                    return success(null);
                })) {
                    var failed = runtime.reconcile(task.id()).toCompletableFuture();
                    assertSame(fatal, assertThrows(ExecutionException.class,
                        () -> failed.get(8, TimeUnit.SECONDS)).getCause());
                    assertTrue(returned.await(8, TimeUnit.SECONDS));
                    assertSame(fatal, escaped.get());
                    state(task, TaskState.UNKNOWN);
                    assertEquals(TaskState.ENDED,
                        runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                }
                assertEquals(2, attempts.get());
            }
        }
    }

    @Test
    void diagnosticErrorsCannotLeaveReturnedTasksRunning() throws Exception {
        for (var recorderFailure : List.of(new ServiceConfigurationError("diagnostic unavailable"),
            new InternalError("synthetic diagnostic VM error"))) {
            try (var db = PlatformDatabase.open(root.resolve(recorderFailure.getClass().getSimpleName()), PROTECTION);
                 var security = security(db);
                 var runtime = new PlatformTaskRuntime(db, PlatformTaskRuntimeTest::claims, security,
                     (failure, problem) -> {
                         throw recorderFailure;
                     })) {
                var service = runtime.bind("example.owner", "1.0.0", false);
                var escaped = new AtomicReference<Throwable>();
                var returned = new CountDownLatch(1);
                var task = service.<Void>submit(request("A"), active -> {
                    Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> {
                        escaped.set(failure);
                        returned.countDown();
                    });
                    throw new ServiceConfigurationError("author callback unavailable");
                });
                state(task, TaskState.UNKNOWN);
                if (recorderFailure instanceof VirtualMachineError) {
                    assertTrue(returned.await(8, TimeUnit.SECONDS));
                    assertSame(recorderFailure, escaped.get());
                }
                try (var registration = service.registerRecovery("fixture", (previous, original) -> success(null))) {
                    assertEquals(TaskState.ENDED,
                        runtime.reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                }
                assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
            }
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
            assertFalse(ledger.complete(id, TaskResult.UNKNOWN, "{}", false));
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
            assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
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
            assertEquals(TaskResult.CANCELLED, result(cancelled).outcome());
            assertEquals(2, security.securityState().activeTasks());
            release.countDown();
            assertEquals(TaskResult.SUCCEEDED, result(running).outcome());
            assertEquals(TaskResult.SUCCEEDED, result(queued).outcome());
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
            assertEquals(TaskResult.SUCCEEDED, result(task).outcome());
            assertEquals(1, calls.get());
            assertEquals(0, security.securityState().activeTasks());
            assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                security::openCredentials);
        }
    }
}

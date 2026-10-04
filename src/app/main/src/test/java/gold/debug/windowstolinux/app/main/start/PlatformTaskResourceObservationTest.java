package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.security.SecurityStore;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskResourceObservationTest {
    @TempDir
    Path root;

    private static final String OWNER = "example.observation";

    private static final String VERSION = "1.0.0";

    private static DesktopSecurityService security(PlatformDatabase db) throws Exception {
        var security = new DesktopSecurityService(
            new SecurityStore(new StoreConnections(db::openConnection)));
        security.setupAndUnlock("fixture-password".toCharArray(), "fixture-password".toCharArray());
        return security;
    }

    private static List<TaskResource> claims(java.sql.Connection connection, String owner, String version,
                                             TaskRequest request) {
        return List.of(new TaskResource("MOD", owner, owner, false));
    }

    private static TaskRequest request() {
        return new TaskRequest("fixture", "Resource observation", List.of(), true);
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }

    private static void state(TaskHandle<?> handle, TaskState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (handle.snapshot().state() != expected && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertEquals(expected, handle.snapshot().state());
    }

    @Test
    void originalWorkerCanObserveAfterLockButForeignEndedAndRevokedBindingsCannot() throws Exception {
        var resume = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskResourceObservationTest::claims, security)) {
            var service = runtime.bind(OWNER, VERSION, false);
            var context = new AtomicReference<TaskContext>();
            var entered = new CountDownLatch(1);
            var handle = service.submit(request(), active -> {
                context.set(active);
                boolean initial = service.resourcesReleased(active);
                boolean occupied;
                boolean locked;
                try (var lease = active.retain("file", "handle", "fixture")) {
                    occupied = service.resourcesReleased(active);
                    entered.countDown();
                    if (!resume.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("Observation callback was not resumed");
                    locked = service.resourcesReleased(active);
                }
                return success(List.of(initial, occupied, locked, service.resourcesReleased(active)));
            });
            try {
                assertTrue(entered.await(8, TimeUnit.SECONDS));
                var original = context.get();
                TaskContext forged = new TaskContext() {
                    public UUID id() {
                        return original.id();
                    }

                    public CancelToken cancelToken() {
                        return original.cancelToken();
                    }

                    public void progress(String text, Optional<Double> fraction) {
                    }

                    public ResourceLease retain(String kind, String id, String description) {
                        return null;
                    }
                };
                assertEquals("TASK_CONTEXT", assertThrows(ApiException.class, () -> service.resourcesReleased(forged))
                    .problem().eventCode());
                assertEquals("TASK_CALLER",
                    assertThrows(ApiException.class,
                        () -> runtime.bind("other-owner", VERSION, false).resourcesReleased(original)).problem()
                        .eventCode());
                assertEquals("TASK_CALLER",
                    assertThrows(ApiException.class,
                        () -> runtime.bind(OWNER, "2.0.0", false).resourcesReleased(original)).problem()
                        .eventCode());
                var edtFailure = new AtomicReference<Throwable>();
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        service.resourcesReleased(original);
                    } catch (Throwable failure) {
                        edtFailure.set(failure);
                    }
                });
                assertEquals("SERVICE_WORKER",
                    assertInstanceOf(ApiException.class, edtFailure.get()).problem().eventCode());
                security.lock();
                assertThrows(ApiException.class, () -> service.submit(request(), active -> success(null)));
            } finally {
                resume.countDown();
            }
            assertEquals(List.of(true, false, false, true),
                handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals("TASK_CONTEXT",
                assertThrows(ApiException.class, () -> service.resourcesReleased(context.get())).problem()
                    .eventCode());
            runtime.revokeOwnerServices(OWNER, VERSION);
            assertEquals("MOD_TASK_BINDING_CLOSED",
                assertThrows(ApiException.class, () -> service.resourcesReleased(context.get())).problem()
                    .eventCode());
        }
    }

    @Test
    void explicitRecoveryCanObserveAndReleaseOriginalLease() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskResourceObservationTest::claims, security)) {
            var service = runtime.bind(OWNER, VERSION, false);
            var leaseId = new AtomicReference<UUID>();
            var handle = service.<Void>submit(request(), active -> {
                leaseId.set(active.retain("file", "pending", "fixture").id());
                throw new IOException("Unconfirmed operation");
            });
            state(handle, TaskState.UNKNOWN);
            var observed = new AtomicReference<List<Boolean>>();
            try (var registration = service.registerRecovery("fixture", (snapshot, original) -> {
                boolean before = service.resourcesReleased(original);
                try (var lease = original.retain("file", "pending", "fixture")) {
                    assertEquals(leaseId.get(), lease.id());
                }
                observed.set(List.of(before, service.resourcesReleased(original)));
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(List.of(false, true), observed.get());
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void returnedCallbackCannotObserveWhileItsLeaseKeepsFinalizationOpen() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, PlatformTaskResourceObservationTest::claims, security)) {
            var service = runtime.bind(OWNER, VERSION, false);
            var context = new AtomicReference<TaskContext>();
            var lease = new AtomicReference<ResourceLease>();
            var handle = service.submit(request(), active -> {
                context.set(active);
                lease.set(active.retain("file", "pending", "fixture"));
                return success(null);
            });
            state(handle, TaskState.FINISHING);
            assertEquals("TASK_NOT_EXECUTING",
                assertThrows(ApiException.class, () -> service.resourcesReleased(context.get())).problem()
                    .eventCode());
            lease.get().close();
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
        }
    }
}

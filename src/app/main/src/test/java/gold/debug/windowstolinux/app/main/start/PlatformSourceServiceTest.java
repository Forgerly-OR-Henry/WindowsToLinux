package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskState;
import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;

import static gold.debug.windowstolinux.app.main.start.PlatformServerReferenceTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.file.SelectedDirectory;
import gold.debug.windowstolinux.app.file.FrozenSourceWork;
import gold.debug.windowstolinux.appapi.file.FileEntry;
import gold.debug.windowstolinux.appapi.file.FileReadRequest;
import gold.debug.windowstolinux.appapi.file.FileSelection;
import gold.debug.windowstolinux.appapi.file.FileSnapshot;
import gold.debug.windowstolinux.appapi.file.SnapshotRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformSourceServiceTest {
    @TempDir
    Path root;

    static void rejectUnexpectedFileApproval(PlatformRuntime runtime) {
        runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts() {
            @Override
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                throw new AssertionError("Source selection, queries and owned workspaces must not request file approval");
            }
        });
    }

    Path sourceFiles() throws Exception {
        var source = Files.createDirectories(root.resolve("用户选定"));
        Files.writeString(source.resolve(".env"), "fixture");
        return source;
    }

    @Test
    void listingUsesOriginalTaskSelectionAndActivationWithoutReturningRawPaths() throws Exception {
        Path files = sourceFiles();
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setSourcePicker((title, task, cancel) -> files);
            rejectUnexpectedFileApproval(runtime);
            var source = runtime.capabilities().source(OWNER, VERSION);
            var foreign = runtime.capabilities().source("foreign.mod", VERSION);
            var another = runtime.capabilities().source(OWNER, VERSION);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var original = new AtomicReference<TaskContext>();
            var selected = completed(binding.submit(request(), task -> {
                original.set(task);
                var selection = source.selectDirectory("Source", task);
                assertEquals(files.toRealPath().toUri(), source.location(selection, task));
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                    () -> source.location(new FileSelection(selection.id(), selection.displayName()), task))
                    .problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> foreign.location(selection, task)).problem().code());
                SwingUtilities.invokeAndWait(() -> assertEquals(ProblemCode.CONTRACT_VIOLATION,
                    assertThrows(ApiException.class, () -> source.location(selection, task)).problem().code()));
                assertEquals(List.of(new FileEntry(".env", FileEntry.EntryType.FILE, 7)),
                    source.list(selection, "", 1, task));
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                    () -> source.list(new FileSelection(selection.id(), selection.displayName()), "", 1, task))
                    .problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> foreign.list(selection, "", 1, task)).problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> another.list(selection, "", 1, task)).problem().code());
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class, () -> source.list(selection, "../outside", 1, task)).problem()
                        .code());
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class, () -> source.list(selection, null, 1, task)).problem().code());
                SwingUtilities.invokeAndWait(() -> assertEquals(ProblemCode.CONTRACT_VIOLATION,
                    assertThrows(ApiException.class, () -> source.list(selection, "", 1, task)).problem().code()));
                return success(selection);
            }));
            assertEquals(ProblemCode.ACCESS_DENIED,
                assertThrows(ApiException.class, () -> source.list(selected, "", 1, original.get())).problem()
                    .code());
            assertEquals(ProblemCode.ACCESS_DENIED,
                assertThrows(ApiException.class, () -> source.location(selected, original.get())).problem().code());
            completed(binding.submit(request(), task -> {
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> source.list(selected, "", 1, task)).problem().code());
                return success(null);
            }));
        }
    }

    @Test
    void originalSelectionIsTaskAndActivationBoundWithWorkerOnlyAccess() throws Exception {
        Path files = sourceFiles();
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setSourcePicker((title, task, cancel) -> files);
            rejectUnexpectedFileApproval(runtime);
            var source = runtime.capabilities().source(OWNER, VERSION);
            var foreign = runtime.capabilities().source("foreign.mod", VERSION);
            var anotherActivation = runtime.capabilities().source(OWNER, VERSION);
            var original = new AtomicReference<TaskContext>();
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var read = new FileReadRequest(".env", 7);
            var selected = completed(binding.submit(request(), task -> {
                original.set(task);
                var selection = source.selectDirectory("选择源码", task);
                assertEquals("用户选定", selection.displayName());
                assertEquals("fixture", new String(source.read(selection, read, task).content()));
                assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                    () -> source.read(new FileSelection(selection.id(), selection.displayName()), read, task))
                    .problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> foreign.read(selection, read, task)).problem().code());
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> anotherActivation.read(selection, read, task)).problem()
                        .code());
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class,
                        () -> source.read(selection, new FileReadRequest("../outside", 20), task)).problem()
                        .code());
                assertEquals(ProblemCode.IO_FAILURE, assertThrows(ApiException.class,
                    () -> source.read(selection, new FileReadRequest(".env", 1), task)).problem().code());
                SwingUtilities.invokeAndWait(() -> assertEquals(ProblemCode.CONTRACT_VIOLATION,
                    assertThrows(ApiException.class, () -> source.read(selection, read, task)).problem().code()));
                return success(selection);
            }));
            assertEquals(ProblemCode.ACCESS_DENIED,
                assertThrows(ApiException.class, () -> source.read(selected, read, original.get())).problem()
                    .code());
            completed(binding.submit(request(), task -> {
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> source.read(selected, read, task)).problem().code());
                return success(null);
            }));
        }
    }

    @Test
    void actualSnapshotFilesKeepTaskFinishingAndShutdownBlockedUntilClosed() throws Exception {
        Path files = sourceFiles();
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setSourcePicker((title, task, cancel) -> files);
            rejectUnexpectedFileApproval(runtime);
            var source = runtime.capabilities().source(OWNER, VERSION);
            var held = new AtomicReference<FileSnapshot>();
            var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), context -> {
                var selected = source.selectDirectory("Source", context);
                held.set(source.snapshot(selected, new SnapshotRequest(List.of(".env"), 7), context));
                return success(42);
            });
            state(task, TaskState.FINISHING);
            var snapshot = held.get();
            Path payload = snapshot.directory();
            try {
                assertEquals("fixture", Files.readString(payload.resolve(".env")));
                assertEquals(64, snapshot.sha256().length());
                assertFalse(task.completion().toCompletableFuture().isDone());
                assertThrows(SQLException.class, runtime::close);
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            } finally {
                snapshot.close();
            }
            assertEquals(42, completed(task));
            assertFalse(Files.exists(payload));
            assertThrows(IllegalStateException.class, snapshot::directory);
            snapshot.close();
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void cancelledChooserAndFailedCaptureReleaseActualResources() throws Exception {
        Path files = sourceFiles();
        var selectionCalls = new AtomicInteger();
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setSourcePicker((title, task, cancel) -> selectionCalls.getAndIncrement() == 0 ? null : files);
            rejectUnexpectedFileApproval(runtime);
            var source = runtime.capabilities().source(OWNER, VERSION);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals(ProblemCode.CANCELLED,
                    assertThrows(ApiException.class, () -> source.selectDirectory("Source", task)).problem()
                        .code());
                var selected = source.selectDirectory("Source", task);
                assertEquals(ProblemCode.IO_FAILURE,
                    assertThrows(ApiException.class,
                        () -> source.snapshot(selected, new SnapshotRequest(List.of(".env"), 1), task))
                        .problem().code());
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                return success(null);
            }));
            try (var paths = Files.walk(root.resolve("data/work/source"))) {
                assertEquals(1, paths.count());
            }
        }
    }

    @Test
    void lockCrossingDiscardsOldDirectoryConsentAndRequiresFreshSelection() throws Exception {
        Path files = sourceFiles();
        Path discarded = Files.createDirectory(root.resolve("discarded"));
        var first = new CountDownLatch(1);
        var returnFirst = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setSourcePicker((title, task, cancel) -> {
                if (calls.incrementAndGet() == 1) {
                    first.countDown();
                    try {
                        assertTrue(returnFirst.await(8, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        throw new AssertionError(error);
                    }
                    return discarded;
                }
                return files;
            });
            rejectUnexpectedFileApproval(runtime);
            var source = runtime.capabilities().source(OWNER, VERSION);
            var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                context -> success(source.selectDirectory("Source", context)));
            try {
                assertTrue(first.await(8, TimeUnit.SECONDS));
                runtime.security().lock();
                returnFirst.countDown();
                assertFalse(task.completion().toCompletableFuture().isDone());
                runtime.security().unlock(PASSWORD.toCharArray());
                assertEquals("用户选定", completed(task).displayName());
                assertEquals(2, calls.get());
            } finally {
                returnFirst.countDown();
            }
        }
    }

    @Test
    void deletedFilesWithFailedLedgerReleaseStayUnknownUntilOriginalTaskReconciles() throws Exception {
        Path files = sourceFiles();
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setSourcePicker((title, task, cancel) -> files);
            rejectUnexpectedFileApproval(runtime);
            var source = runtime.capabilities().source(OWNER, VERSION);
            var db = runtime.persistence().database();
            var original = new AtomicReference<TaskContext>();
            var payload = new AtomicReference<Path>();
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_source_release CHECK(resource_kind<>'FILE' OR released_at IS NULL)");
            var handle = binding.submit(request(), task -> {
                original.set(task);
                try (var snapshot = source.snapshot(source.selectDirectory("Source", task),
                    new SnapshotRequest(List.of(".env"), 7), task)) {
                    payload.set(snapshot.directory());
                }
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertFalse(Files.exists(payload.get()));
            assertEquals(1, count(db,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_source_release");
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                source.closeTaskResources(task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void previousBootCleanupRequiresExactMarkerAndNeverRecopiesChangedSource() throws Exception {
        Path files = sourceFiles();
        Path data = root.resolve("data");
        var snapshotId = UUID.randomUUID();
        UUID task;
        Path marker;
        byte[] expectedMarker;
        try (var db = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            var persistence = new PlatformPersistence(db);
            try (var security = new DesktopSecurityService(persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            register(db, null);
            var ledger = new TaskLedger(db);
            task = UUID.randomUUID();
            ledger.enqueue(task, OWNER, VERSION, request(), List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.tryStart(task));
            ledger.retain(task, new TaskResource("FILE", "source-snapshot-v1:" + snapshotId, OWNER, false));
            var work = new FrozenSourceWork(data.resolve("work/source"), task, snapshotId, OWNER);
            work.prepare();
            work.capture(new SelectedDirectory(files), new SnapshotRequest(List.of(".env"), 7), () -> false);
            marker = work.directory().getParent().resolve("owner.bin");
            expectedMarker = Files.readAllBytes(marker);
        }
        Files.writeString(files.resolve(".env"), "changed");
        Files.writeString(marker, "foreign marker");
        try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            runtime.setSourcePicker((title, current, cancel) -> {
                throw new AssertionError("Recovery must not select again");
            });
            rejectUnexpectedFileApproval(runtime);
            var source = runtime.capabilities().source(OWNER, VERSION);
            var binding = runtime.tasks().bind(OWNER, VERSION, true);
            try (var registration = binding.registerRecovery("fixture", (previous, context) -> {
                source.closeTaskResources(context);
                return success(null);
            })) {
                assertEquals(TaskState.UNKNOWN,
                    runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertTrue(Files.exists(marker));
                Files.write(marker, expectedMarker);
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertFalse(Files.exists(marker.getParent()));
            assertEquals("changed", Files.readString(files.resolve(".env")));
        }
    }
}

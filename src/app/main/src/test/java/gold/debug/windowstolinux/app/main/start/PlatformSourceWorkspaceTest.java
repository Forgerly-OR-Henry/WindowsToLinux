package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskState;

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
import gold.debug.windowstolinux.app.file.SourceWorkDirectory;
import gold.debug.windowstolinux.appapi.file.FileReadRequest;
import gold.debug.windowstolinux.appapi.file.FileSelection;
import gold.debug.windowstolinux.appapi.file.FileWorkspace;
import gold.debug.windowstolinux.appapi.file.SnapshotRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformSourceWorkspaceTest {
    @TempDir
    Path root;

    gold.debug.windowstolinux.app.service.file.PlatformFileAccess source(PlatformRuntime runtime) {
        runtime.setSourcePicker((title, task, cancel) -> {
            throw new AssertionError("No directory selection expected");
        });
        return runtime.capabilities().source(OWNER, VERSION);
    }

    @Test
    void generatedMaterialUsesAnOriginalGrantAndAnIndependentFrozenSnapshot() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = source(runtime);
            var foreign = runtime.capabilities().source("foreign.mod", VERSION);
            var another = runtime.capabilities().source(OWNER, VERSION);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                try (var workspace = source.workspace(task)) {
                    Path directory = workspace.directory();
                    assertEquals(root.resolve("data/work/source-workspace").resolve(task.id().toString())
                        .resolve(workspace.id().toString()).resolve("files"), directory);
                    Files.createDirectories(directory.resolve("src"));
                    Files.writeString(directory.resolve("src/中文.txt"), "original");
                    var selected = workspace.selection();
                    assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class, () -> source.location(selected, task)).problem().code());
                    assertSame(selected, workspace.selection());
                    assertEquals("original",
                        new String(source.read(selected, new FileReadRequest("src/中文.txt", 8), task).content()));
                    assertEquals(1, source.list(selected, "src", 1, task).size());
                    for (var denied : List.of(foreign, another))
                        assertEquals(ProblemCode.ACCESS_DENIED,
                            assertThrows(ApiException.class,
                                () -> denied.read(selected, new FileReadRequest("src/中文.txt", 8), task))
                                .problem().code());
                    assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                        () -> source.list(new FileSelection(selected.id(), selected.displayName()), "", 1, task))
                        .problem().code());
                    SwingUtilities.invokeAndWait(() -> assertEquals(ProblemCode.CONTRACT_VIOLATION,
                        assertThrows(ApiException.class, workspace::directory).problem().code()));
                    try (var snapshot = source.snapshot(selected, new SnapshotRequest(List.of("src/中文.txt"), 8),
                        task)) {
                        Files.writeString(directory.resolve("src/中文.txt"), "modified");
                        workspace.close();
                        assertFalse(Files.exists(directory));
                        assertEquals("original", Files.readString(snapshot.directory().resolve("src/中文.txt")));
                        assertEquals(ProblemCode.ACCESS_DENIED,
                            assertThrows(ApiException.class, () -> source.list(selected, "", 1, task)).problem()
                                .code());
                        assertEquals(ProblemCode.ACCESS_DENIED,
                            assertThrows(ApiException.class, workspace::selection).problem().code());
                    }
                }
                return success(null);
            }));
        }
    }

    @Test
    void returnedCallbacksCannotReuseWritablePathsButUnclosedFilesRetainOccupancy() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = source(runtime);
            var held = new AtomicReference<FileWorkspace>();
            var path = new AtomicReference<Path>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var work = source.workspace(task);
                held.set(work);
                path.set(work.directory());
                Files.writeString(path.get().resolve("output"), "held");
                return success(42);
            });
            state(handle, TaskState.FINISHING);
            assertEquals(ProblemCode.UNAVAILABLE,
                assertThrows(ApiException.class, held.get()::directory).problem().code());
            assertThrows(SQLException.class, runtime::close);
            assertTrue(Files.exists(path.get().resolve("output")));
            held.get().close();
            assertEquals(42, completed(handle));
            assertFalse(Files.exists(path.get()));
            held.get().close();
        }
    }

    @Test
    void cancellationRevokesAccessButStillAllowsExplicitCleanup() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = source(runtime);
            var ready = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1);
            var path = new AtomicReference<Path>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                try (var work = source.workspace(task)) {
                    path.set(work.directory());
                    ready.countDown();
                    assertTrue(cancelled.await(8, TimeUnit.SECONDS));
                    assertEquals(ProblemCode.CANCELLED,
                        assertThrows(ApiException.class, work::directory).problem().code());
                    assertEquals(ProblemCode.CANCELLED,
                        assertThrows(ApiException.class, () -> source.workspace(task)).problem().code());
                }
                return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
            });
            try {
                assertTrue(ready.await(8, TimeUnit.SECONDS));
                handle.requestCancel();
            } finally {
                cancelled.countDown();
            }
            assertEquals(Outcome.CANCELLED,
                handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            assertFalse(Files.exists(path.get()));
        }
    }

    @Test
    void failedCreationReleasesLeaseWithoutTouchingAnExistingForeignFile() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            Files.createDirectories(root.resolve("data/work"));
            Path foreign = Files.writeString(root.resolve("data/work/source-workspace"), "foreign");
            var source = source(runtime);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals(ProblemCode.IO_FAILURE,
                    assertThrows(ApiException.class, () -> source.workspace(task)).problem().code());
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                return success(null);
            }));
            assertEquals("foreign", Files.readString(foreign));
        }
    }

    @Test
    void substitutedDirectoryIsPreservedAndTheOriginalHandleCanRetryCleanup() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = source(runtime);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var original = new AtomicReference<TaskContext>();
            var path = new AtomicReference<Path>();
            var saved = new AtomicReference<Path>();
            var held = new AtomicReference<FileWorkspace>();
            var handle = binding.submit(request(), task -> {
                original.set(task);
                var work = source.workspace(task);
                held.set(work);
                path.set(work.directory().getParent());
                saved.set(path.get().resolveSibling("saved"));
                Files.move(path.get(), saved.get());
                Files.createDirectory(path.get());
                Files.writeString(path.get().resolve("foreign"), "preserved");
                assertEquals(ProblemCode.IO_FAILURE,
                    assertThrows(ApiException.class, work::directory).problem().code());
                assertEquals(ProblemCode.UNKNOWN, assertThrows(ApiException.class, work::close).problem().code());
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertEquals("preserved", Files.readString(path.get().resolve("foreign")));
            Files.delete(path.get().resolve("foreign"));
            Files.delete(path.get());
            Files.move(saved.get(), path.get());
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                assertThrows(ApiException.class, held.get()::directory);
                assertEquals(ProblemCode.CONTRACT_VIOLATION,
                    assertThrows(ApiException.class, () -> source.workspace(task)).problem().code());
                source.closeTaskResources(task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertFalse(Files.exists(path.get()));
        }
    }

    @Test
    void deletedFilesWithFailedLedgerReleaseRemainUnknownUntilReconciled() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = source(runtime);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var original = new AtomicReference<TaskContext>();
            var path = new AtomicReference<Path>();
            var db = runtime.persistence().database();
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_workspace_release CHECK(resource_kind<>'FILE' OR released_at IS NULL)");
            var handle = binding.submit(request(), task -> {
                original.set(task);
                try (var work = source.workspace(task)) {
                    path.set(work.directory());
                }
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertFalse(Files.exists(path.get()));
            assertEquals(1, count(db,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_workspace_release");
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
    void priorBootCleansOnlyTheOriginalMarkerAndCannotAllocateNewWork() throws Exception {
        Path data = root.resolve("data");
        UUID id = UUID.randomUUID(), task = UUID.randomUUID();
        Path payload, marker;
        byte[] expected;
        try (var db = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            var persistence = new PlatformPersistence(db);
            try (var security = new DesktopSecurityService(persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            register(db, null);
            var ledger = new TaskLedger(db);
            ledger.enqueue(task, OWNER, VERSION, request(), List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.tryStart(task));
            ledger.retain(task, new TaskResource("FILE", "source-workspace-v1:" + id, OWNER, false));
            var work = new SourceWorkDirectory(data.resolve("work/source-workspace"), task, id, OWNER);
            work.prepare();
            payload = work.directory();
            Files.writeString(payload.resolve("incomplete.tar.gz"), "partial output");
            marker = payload.getParent().resolve("owner.bin");
            expected = Files.readAllBytes(marker);
        }
        Files.writeString(marker, "unproven");
        try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var source = source(runtime);
            var binding = runtime.tasks().bind(OWNER, VERSION, true);
            try (var registration = binding.registerRecovery("fixture", (previous, context) -> {
                assertEquals(ProblemCode.CONTRACT_VIOLATION,
                    assertThrows(ApiException.class, () -> source.workspace(context)).problem().code());
                source.closeTaskResources(context);
                return success(null);
            })) {
                assertEquals(TaskState.UNKNOWN,
                    runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
                assertEquals("partial output", Files.readString(payload.resolve("incomplete.tar.gz")));
                Files.write(marker, expected);
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertFalse(Files.exists(payload.getParent()));
        }
    }

    @Test
    void priorBootReadsOnlyBoundedOriginalWorkspaceEvidenceBeforePhysicalRelease() throws Exception {
        Path data = root.resolve("data");
        UUID id = UUID.randomUUID(), task = UUID.randomUUID();
        byte[] receipt = "verified-original-receipt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var db = PlatformDatabase.open(data, PlatformStartupTest.PROTECTION)) {
            var persistence = new PlatformPersistence(db);
            try (var security = new DesktopSecurityService(persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            register(db, null);
            var ledger = new TaskLedger(db);
            ledger.enqueue(task, OWNER, VERSION, request(), List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.tryStart(task));
            ledger.retain(task, new TaskResource("FILE", "source-workspace-v1:" + id, OWNER, false), null, true);
            var work = new SourceWorkDirectory(data.resolve("work/source-workspace"), task, id, OWNER);
            work.prepare();
            Files.write(work.directory().resolve("git-result.json"), receipt);
        }
        try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var source = source(runtime);
            var binding = runtime.tasks().bind(OWNER, VERSION, true);
            try (var registration = binding.registerRecovery("fixture", (previous, context) -> {
                assertArrayEquals(receipt,
                    source.readWorkspaceEvidence(context, id, "git-result.json", 1024).orElseThrow());
                assertTrue(source.readWorkspaceEvidence(context, id, "absent.json", 1024).isEmpty());
                assertEquals(ProblemCode.UNKNOWN,
                    assertThrows(ApiException.class,
                        () -> source.readWorkspaceEvidence(context, UUID.randomUUID(), "git-result.json", 1024))
                        .problem().code());
                assertEquals(ProblemCode.UNKNOWN,
                    assertThrows(ApiException.class,
                        () -> source.readWorkspaceEvidence(context, id, "git-result.json", 4)).problem()
                        .code());
                source.closeTaskResources(context);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(task).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
        }
        assertFalse(
            Files.exists(data.resolve("work/source-workspace").resolve(task.toString()).resolve(id.toString())));
    }

    @Test
    void preselectedWorkspaceIdentityCannotBeRecreatedAfterOriginalRelease() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var source = source(runtime);
            var id = UUID.randomUUID();
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                try (var workspace = source.workspace(task, id)) {
                    assertEquals(id, workspace.id());
                }
                assertEquals(ProblemCode.IO_FAILURE,
                    assertThrows(ApiException.class, () -> source.workspace(task, id)).problem().code());
                assertFalse(Files.exists(root.resolve("data/work/source-workspace").resolve(task.id().toString())
                    .resolve(id.toString())));
                return success(null);
            }));
        }
    }
}

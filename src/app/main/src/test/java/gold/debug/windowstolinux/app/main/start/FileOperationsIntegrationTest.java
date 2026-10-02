package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformServerReferenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationsIntegrationTest {
    @TempDir
    Path root;

    void presenters(PlatformRuntime runtime, PlatformInteractionTest.Prompts prompts) {
        runtime.setInteractionPresenter(prompts);
        runtime.setSourcePicker((title, task, cancel) -> root);
    }

    FilePath path(String name) {
        return FilePath.local(root.resolve(name));
    }

    @Test
    void genuineFileServiceWritesListsCopiesRenamesAndPersistsExactEvidence() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, new PlatformInteractionTest.Prompts());
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    assertThrows(ApiException.class, () -> file.createFile(path("missing-parent/never"), task));
                    assertTrue(file.operations(task).isEmpty());
                    assertFalse(Files.exists(root.resolve("missing-parent")));
                    assertEquals(Outcome.SUCCEEDED, file.createDirectory(path("original"), task).outcome());
                    assertEquals(Outcome.SUCCEEDED, file.createFile(path("original/empty"), task).outcome());
                    assertEquals(0, file.getFile(path("original/empty"), task).bytes());
                    assertThrows(ApiException.class, () -> file.createFile(path("original/empty"), task));
                    assertEquals(0, Files.size(root.resolve("original/empty")));
                    assertEquals(Outcome.SUCCEEDED, file.writeFile(path("original/a.txt"), "中文 data".getBytes(java.nio.charset.StandardCharsets.UTF_8), task).outcome());
                    assertEquals(List.of("a.txt"), file.list(path("original"), 10, Optional.of(FileInfo.Kind.FILE), ".txt", task).stream().map(i -> i.actual().name()).toList());
                    assertEquals(Outcome.SUCCEEDED, file.copyFile(path("original"), path("copy"), task, FileChoice.of(FileChoice.Directory.COPY, FileChoice.Conflict.COPY)).outcome());
                    assertEquals(Outcome.SUCCEEDED, file.renameFile(path("copy/a.txt"), "renamed.txt", task).outcome());
                    assertArrayEquals("中文 data".getBytes(java.nio.charset.StandardCharsets.UTF_8), file.readFile(path("copy/renamed.txt"), 64, task));
                    assertFalse(Files.exists(root.resolve("copy/a.txt")));
                    assertTrue(Files.exists(root.resolve("original/a.txt")));
                    var operations = file.operations(task);
                    assertEquals(5, operations.size());
                    assertTrue(operations.stream().allMatch(o -> o.phase().equals("COMPLETE")));
                    for (var operation : operations) assertFalse(operation.steps().isEmpty());
                    return success(null);
                }));
            }
            assertEquals(5, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation"));
            assertTrue(count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation_step WHERE phase LIKE 'BEFORE_%'") >= 4);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void refusedOrdinaryDeleteWaitsWithoutHoldingHandlesOrChangingTheObject() throws Exception {
        Path kept = Files.writeString(root.resolve("kept.txt"), "keep");
        var attempts = new AtomicInteger();
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, new PlatformInteractionTest.Prompts() {
                @Override
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                    assertEquals("file-delete", intent.type());
                    attempts.incrementAndGet();
                    return false;
                }
            });
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var report = file.deleteFile(FilePath.local(kept), task);
                    return new OperationResult<>(report.outcome(), Optional.of(report), Optional.empty());
                });
                state(handle, TaskState.WAITING);
                assertEquals("keep", Files.readString(kept));
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation WHERE phase='WAITING'"));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation_step WHERE phase='BEFORE_DELETE'"));
                Path moved = root.resolve("changed.txt");
                Files.move(kept, moved);
                Files.move(moved, kept);
                runtime.tasks().continueWaiting(handle.id());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (attempts.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
                state(handle, TaskState.WAITING);
                assertEquals(2, attempts.get());
                assertEquals("keep", Files.readString(kept));
                handle.requestCancel();
                var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(Outcome.CANCELLED, result.outcome());
                assertTrue(Files.exists(kept));
            }
        }
    }

    @Test
    void forgedHighPrivilegeGrantNeverFallsBackToAnotherHumanConfirmation() throws Exception {
        Files.writeString(root.resolve("kept"), "keep");
        var prompts = new AtomicInteger();
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, new PlatformInteractionTest.Prompts() {
                @Override
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                    prompts.incrementAndGet();
                    return true;
                }
            });
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> file.deleteFile(path("kept"), task, new ApprovalGrant(UUID.randomUUID()))).problem().code());
                    assertEquals(0, prompts.get());
                    assertEquals("keep", Files.readString(root.resolve("kept")));
                    assertTrue(file.operations(task).isEmpty());
                    return success(null);
                }));
            }
        }
    }

    @Test
    void platformLinksTrackAppMovesAndRetainOriginalTargetsOnCopies() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, new PlatformInteractionTest.Prompts());
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    file.createDirectory(path("original"), task);
                    file.writeFile(path("original/content"), new byte[]{1, 2, 3}, task);
                    assertEquals(Outcome.SUCCEEDED, file.createLink(path("managed"), root.resolve("original").toString(), task).outcome());
                    assertEquals(FileInfo.Kind.LINK, file.readLink(path("managed"), task).kind());
                    assertEquals(path("original"), file.getFile(path("managed"), task).actual());
                    assertEquals(Outcome.SUCCEEDED, file.copyFile(path("original"), path("copied"), task).outcome());
                    assertEquals(path("original"), file.getFile(path("managed"), task).actual());
                    assertEquals(Outcome.SUCCEEDED, file.moveFile(path("original"), path("moved"), task).outcome());
                    assertEquals(path("moved"), file.getFile(path("managed"), task).actual());
                    assertArrayEquals(new byte[]{1, 2, 3}, file.readFile(path("managed/content"), 8, task));
                    return success(null);
                }));
            } finally {
                Files.deleteIfExists(root.resolve("managed"));
            }
        }
    }
}

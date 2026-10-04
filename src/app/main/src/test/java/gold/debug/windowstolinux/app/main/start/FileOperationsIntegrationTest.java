package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.file.action.FileChoice;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
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
            try (var owner = runtime.capabilities().source(OWNER, VERSION)) {
                var file = new gold.debug.windowstolinux.app.service.file.PlatformFileService(owner);
                assertSame(owner, file.queries());
                assertSame(owner, file.actions());
                assertSame(owner, file.sources());
                assertSame(owner, file.snapshots());
                assertSame(owner, file.workspaces());
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    assertThrows(ApiException.class, () -> file.actions().createFile(path("missing-parent/never"), task));
                    assertTrue(file.actions().operations(task).isEmpty());
                    assertFalse(Files.exists(root.resolve("missing-parent")));
                    assertEquals(TaskResult.SUCCEEDED, file.actions().createDirectory(path("original"), task).outcome());
                    assertEquals(TaskResult.SUCCEEDED, file.actions().createFile(path("original/empty"), task).outcome());
                    assertEquals(0, file.queries().getFile(path("original/empty"), task).bytes());
                    assertThrows(ApiException.class, () -> file.actions().createFile(path("original/empty"), task));
                    assertEquals(0, Files.size(root.resolve("original/empty")));
                    assertEquals(TaskResult.SUCCEEDED, file.actions().writeFile(path("original/a.txt"), "中文 data".getBytes(java.nio.charset.StandardCharsets.UTF_8), task).outcome());
                    assertEquals(List.of("a.txt"), file.queries().list(path("original"), 10, Optional.of(FileInfo.Kind.FILE), ".txt", task).stream().map(i -> i.actual().name()).toList());
                    assertEquals(TaskResult.SUCCEEDED, file.actions().copyFile(path("original"), path("copy"), task, FileChoice.of(FileChoice.Directory.COPY, FileChoice.Conflict.COPY)).outcome());
                    assertEquals(TaskResult.SUCCEEDED, file.actions().renameFile(path("copy/a.txt"), "renamed.txt", task).outcome());
                    assertArrayEquals("中文 data".getBytes(java.nio.charset.StandardCharsets.UTF_8), file.queries().readFile(path("copy/renamed.txt"), 64, task));
                    assertFalse(Files.exists(root.resolve("copy/a.txt")));
                    assertTrue(Files.exists(root.resolve("original/a.txt")));
                    var operations = file.actions().operations(task);
                    assertEquals(5, operations.size());
                    assertTrue(operations.stream().allMatch(o -> o.phase().equals("COMPLETE")));
                    for (var operation : operations) assertFalse(operation.steps().isEmpty());
                    file.closeTaskResources(task);
                    return success(null);
                }));
            }
            assertEquals(5, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation"));
            assertTrue(count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation_step WHERE phase LIKE 'BEFORE_%'") >= 4);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void invalidRenameInputsUseTheApiFailureWithoutPromptsEffectsOrRetainedResources() throws Exception {
        Files.writeString(root.resolve("kept.txt"), "keep");
        var prompts = new AtomicInteger();
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, new PlatformInteractionTest.Prompts() {
                @Override
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                    prompts.incrementAndGet();
                    return true;
                }
            });
            try (var owner = runtime.capabilities().source(OWNER, VERSION)) {
                var file = new gold.debug.windowstolinux.app.service.file.PlatformFileService(owner);
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    long retained = count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL");
                    String drive = root.getRoot().toString().substring(0, 2);
                    String otherDrive = drive.equalsIgnoreCase("C:") ? "D:" : "C:";
                    for (String name : new String[]{null, "", ".", "..", "bad/name", "bad\\name", drive + "escape.txt", otherDrive + "escape.txt"}) {
                        assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                            () -> file.actions().renameFile(path("kept.txt"), name, task)).problem().code());
                        assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                            () -> file.actions().renameLink(path("kept.txt"), name, task)).problem().code());
                        assertEquals(retained, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    }
                    for (FilePath source : new FilePath[]{null, FilePath.local(root.getRoot())}) {
                        assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                            () -> file.actions().renameFile(source, "renamed.txt", task)).problem().code());
                        assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                            () -> file.actions().renameLink(source, "renamed.txt", task)).problem().code());
                    }
                    assertEquals(0, prompts.get());
                    assertTrue(file.actions().operations(task).isEmpty());
                    assertEquals("keep", Files.readString(root.resolve("kept.txt")));
                    assertFalse(Files.exists(root.resolve("renamed.txt")));
                    return success(null);
                }));
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void renamedLinkKeepsTheOriginalTargetAndReleasesTheTaskResources() throws Exception {
        Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve("target/content.txt"), "original target");
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, new PlatformInteractionTest.Prompts());
            try (var owner = runtime.capabilities().source(OWNER, VERSION)) {
                var file = new gold.debug.windowstolinux.app.service.file.PlatformFileService(owner);
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    assertEquals(TaskResult.SUCCEEDED, file.actions().createLink(path("original-link"), root.resolve("target").toString(), task).outcome());
                    String target = file.queries().readLink(path("original-link"), task).target().orElseThrow();
                    assertEquals(TaskResult.SUCCEEDED, file.actions().renameLink(path("original-link"), "renamed-link", task).outcome());
                    assertFalse(Files.exists(root.resolve("original-link"), LinkOption.NOFOLLOW_LINKS));
                    assertEquals(FileInfo.Kind.LINK, file.queries().readLink(path("renamed-link"), task).kind());
                    assertEquals(target, file.queries().readLink(path("renamed-link"), task).target().orElseThrow());
                    assertEquals("original target", Files.readString(root.resolve("target/content.txt")));
                    assertEquals(2, file.actions().operations(task).size());
                    assertTrue(file.actions().operations(task).stream().allMatch(operation -> operation.phase().equals("COMPLETE")));
                    return success(null);
                }));
            } finally {
                Files.deleteIfExists(root.resolve("renamed-link"));
                Files.deleteIfExists(root.resolve("original-link"));
            }
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
                assertEquals(TaskResult.CANCELLED, result.outcome());
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
                    assertEquals(TaskResult.SUCCEEDED, file.createLink(path("managed"), root.resolve("original").toString(), task).outcome());
                    assertEquals(FileInfo.Kind.LINK, file.readLink(path("managed"), task).kind());
                    assertEquals(path("original"), file.getFile(path("managed"), task).actual());
                    assertEquals(TaskResult.SUCCEEDED, file.copyFile(path("original"), path("copied"), task).outcome());
                    assertEquals(path("original"), file.getFile(path("managed"), task).actual());
                    assertEquals(TaskResult.SUCCEEDED, file.moveFile(path("original"), path("moved"), task).outcome());
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

package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.file.LocalFileBackend;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.file.action.FileActions;
import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRecycleIntegrationTest {
    @TempDir
    Path root;

    final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

    final AtomicReference<TaskContext> callbackContext = new AtomicReference<>();

    @Test
    void genuineHighPrivilegeBrokenLinkPermitRecyclesOnlyTheLinkAndIsConsumedOnce() throws Exception {
        Path missing = Files.createDirectory(root.resolve("missing-target"));
        Path link = root.resolve("broken-link");
        WindowsLinks.create(link, missing);
        Files.delete(missing);
        var humans = new AtomicInteger();
        try (var fixture = new PlatformAiServiceTest.Fixture();
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.fileSettings().save(false);
            runtime.setSourcePicker((title, task, cancel) -> root);
            runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts() {
                @Override
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                    humans.incrementAndGet();
                    return true;
                }
            });
            PlatformAiServiceTest.model(runtime, fixture, "review", "/approval", gold.debug.windowstolinux.app.ai.model.AiCapabilityType.TEXT, 512);
            PlatformAiServiceTest.route(runtime, gold.debug.windowstolinux.app.ai.model.AiPurposeType.APPROVAL, "review");
            fixture.handler.set(exchange -> PlatformAiServiceTest.Fixture.reply(exchange, 200, FileApprovalIntegrationTest.response("{\"approved\":true}")));
            try (var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, remote)) {
                finish(runtime, runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    callbackContext.set(task);
                    try {
                        var path = FilePath.local(link);
                        var info = file.readLink(path, task);
                        assertEquals(FileInfo.Kind.LINK, info.kind());
                        assertThrows(ApiException.class, () -> file.getFile(path, task));
                        var grant = remote.interaction().approve(FileActions.delete(info, true, DeleteEffect.RECYCLE), task).value().orElseThrow();
                        assertEquals(1, humans.get());
                        var report = file.deleteLink(path, task, grant);
                        assertEquals(TaskResult.SUCCEEDED, report.outcome(), report.toString());
                        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
                        var operation = file.operations(task).stream().filter(item -> item.id().equals(report.operationId())).findFirst().orElseThrow();
                        assertEquals(TaskResult.SUCCEEDED, file.checkOperation(operation.id(), task).outcome());
                        restoreOwnedReceipt(operation, link);
                        assertEquals(missing.toString(), WindowsLinks.target(link).orElseThrow());
                        assertFalse(Files.exists(missing, LinkOption.NOFOLLOW_LINKS));
                        assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> file.deleteLink(path, task, grant)).problem().code());
                        assertEquals(1, humans.get());
                        assertEquals(1, fixture.calls.get());
                        assertEquals(FileInfo.Kind.LINK, file.readLink(path, task).kind());
                        return success(null);
                    } catch (Throwable failure) {
                        callbackFailure.set(failure);
                        throw failure;
                    }
                }));
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void genuineOrdinaryFileDirectoryAndLinkDeletionPersistNativeRecoverableReceiptsAndRestoreOwnedObjects() throws Exception {
        var confirmations = new AtomicInteger();
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep"), "outside");
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.fileSettings().save(false);
            runtime.setSourcePicker((a, b, c) -> root);
            runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts() {
                @Override
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                    assertEquals("file-delete", intent.type());
                    confirmations.incrementAndGet();
                    return true;
                }
            });
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                finish(runtime, runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    callbackContext.set(task);
                    try {
                        Path ordinary = Files.writeString(root.resolve("file"), "owned file");
                        Path directory = Files.createDirectory(root.resolve("directory"));
                        Files.writeString(directory.resolve("child"), "owned directory");
                        WindowsLinks.create(directory.resolve("outside-link"), outside);
                        Path link = root.resolve("link");
                        WindowsLinks.create(link, outside);
                        try {
                            for (Path source : List.of(ordinary, directory, link)) {
                                FilePath path = FilePath.local(source);
                                var report = source.equals(link) ? file.deleteLink(path, task) : file.deleteFile(path, task);
                                assertEquals(TaskResult.SUCCEEDED, report.outcome(), report.toString());
                                assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
                                FileOperation operation = file.operations(task).stream().filter(item -> item.id().equals(report.operationId())).findFirst().orElseThrow();
                                assertEquals("COMPLETE", operation.phase());
                                assertEquals(TaskResult.SUCCEEDED, file.checkOperation(operation.id(), task).outcome());
                                restoreOwnedReceipt(operation, source);
                            }
                            assertEquals(3, confirmations.get());
                            assertEquals("owned file", Files.readString(ordinary));
                            assertEquals("owned directory", Files.readString(directory.resolve("child")));
                            assertEquals(outside.toString(), WindowsLinks.target(link).orElseThrow());
                            assertEquals(outside.toString(), WindowsLinks.target(directory.resolve("outside-link")).orElseThrow());
                            assertEquals("outside", Files.readString(outside.resolve("keep")));
                            return success(null);
                        } finally {
                            Files.deleteIfExists(link);
                            Files.deleteIfExists(directory.resolve("outside-link"));
                        }
                    } catch (Throwable failure) {
                        callbackFailure.set(failure);
                        throw failure;
                    }
                }));
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void nativeRecycleMetadataLostAfterCompletionRemainsUnknownAndCannotAuthorizeAReplay() throws Exception {
        Path original = Files.writeString(root.resolve("owned"), "retained recovery bytes");
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.fileSettings().save(false);
            runtime.setSourcePicker((a, b, c) -> root);
            runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts());
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                finish(runtime, runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    callbackContext.set(task);
                    try {
                        var report = file.deleteFile(FilePath.local(original), task);
                        assertEquals(TaskResult.SUCCEEDED, report.outcome());
                        FileOperation operation = file.operations(task).stream().filter(item -> item.id().equals(report.operationId())).findFirst().orElseThrow();
                        var receipt = operation.steps().stream().filter(step -> step.phase().equals("RECYCLED_ITEM")).findFirst().orElseThrow();
                        var metadata = operation.steps().stream().filter(step -> step.phase().equals("RECYCLE_METADATA")).findFirst().orElseThrow();
                        assertEquals(TaskResult.SUCCEEDED, file.checkOperation(operation.id(), task).outcome());
                        var backend = new LocalFileBackend();
                        Path information = Path.of(metadata.target().orElseThrow().path());
                        assertEquals(metadata.detail(), backend.stat(FilePath.local(information)).orElseThrow().identity());
                        Files.delete(information);
                        assertEquals(TaskResult.UNKNOWN, file.checkOperation(operation.id(), task).outcome());
                        assertThrows(ApiException.class, () -> file.continueOperation(operation.id(), task));
                        Path recycled = Path.of(receipt.target().orElseThrow().path());
                        assertEquals(receipt.detail(), backend.stat(FilePath.local(recycled)).orElseThrow().identity());
                        Files.move(recycled, original);
                        assertEquals("retained recovery bytes", Files.readString(original));
                        return success(null);
                    } catch (Throwable failure) {
                        callbackFailure.set(failure);
                        throw failure;
                    }
                }));
            }
        }
    }

    void finish(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!handle.completion().toCompletableFuture().isDone() && handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
            Thread.sleep(5);
        if (handle.snapshot().state() == TaskState.UNKNOWN) {
            callbackReturned(runtime, callbackContext.get());
            try (var recovery = runtime.tasks().bind(OWNER, VERSION, false).registerRecovery("fixture", (before, task) -> new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_PRESERVED_RECEIPT_FAILURE", "Owned receipt failure preserved for diagnosis", Map.of(), Optional.empty()))))) {
                assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            }
        }
        if (callbackFailure.get() != null)
            throw new AssertionError("Actual recycle callback failed", callbackFailure.get());
        assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
    }

    // Native shell restoration is tested in app/windows; this cleanup moves only this task's proven object and paired metadata.
    static void restoreOwnedReceipt(FileOperation operation, Path original) throws Exception {
        var receipt = operation.steps().stream().filter(step -> step.phase().equals("RECYCLED_ITEM")).findFirst().orElseThrow();
        var metadata = operation.steps().stream().filter(step -> step.phase().equals("RECYCLE_METADATA")).findFirst().orElseThrow();
        var backend = new LocalFileBackend();
        FilePath recycled = receipt.target().orElseThrow(), information = metadata.target().orElseThrow();
        assertEquals(receipt.detail(), backend.stat(recycled).orElseThrow().identity());
        assertEquals(metadata.detail(), backend.stat(information).orElseThrow().identity());
        Files.move(Path.of(recycled.path()), original);
        Files.delete(Path.of(information.path()));
        assertTrue(Files.exists(original, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(Path.of(recycled.path()), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(Path.of(information.path()), LinkOption.NOFOLLOW_LINKS));
    }
}

package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.callbackReturned;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.service.setting.TransferConfig;
import gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService.Launch;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.file.action.FileActions;
import gold.debug.windowstolinux.appapi.file.action.FileChoice;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FileRemoteIntegrationTest {
    @TempDir
    Path root;

    final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

    final AtomicReference<TaskContext> callbackContext = new AtomicReference<>();

    @Test
    void ordinaryNonRecyclableRemoteDeletionOffersOnlyPermanentDeletion() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.fileSettings().save(false);
            runtime.setSourcePicker((a, b, c) -> root);
            var confirmations = new AtomicInteger();
            runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts() {
                @Override
                public DeleteEffect delete(Map<DeleteEffect, ActionIntent> choices, String explanation,
                                           gold.debug.windowstolinux.appapi.task.model.TaskSnapshot task,
                                           gold.debug.windowstolinux.appapi.task.run.CancelToken cancel) {
                    assertEquals(Set.of(DeleteEffect.PERMANENT), choices.keySet());
                    confirmations.incrementAndGet();
                    return DeleteEffect.PERMANENT;
                }
            });
            Path owned = Files.writeString(remote.files.resolve("owned"), "owned fixture");
            try (var activation = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, activation)) {
                finish(runtime, runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    callbackContext.set(task);
                    try {
                        var report = file.deleteFile(FilePath.remote(SERVER, "/owned"), task);
                        assertEquals(TaskResult.SUCCEEDED, report.outcome());
                        assertFalse(Files.exists(owned));
                        assertEquals(1, confirmations.get());
                        var operation = file.operations(task).getFirst();
                        assertTrue(operation.steps().stream().anyMatch(step -> step.phase().equals("BEFORE_DELETE") && step.detail().equals("PERMANENT")));
                        assertFalse(operation.steps().stream().anyMatch(step -> step.phase().startsWith("RECYCLE")));
                        return success(null);
                    } catch (Throwable failure) {
                        callbackFailure.set(failure);
                        throw failure;
                    }
                }));
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @ParameterizedTest
    @EnumSource(AuthorizationMode.class)
    void independentHttpDeletionPermitPreservesBusinessSemanticsAndConsumesOnceInEachWorkflowMode(AuthorizationMode mode) throws Exception {
        try (var remote = new Remote(root.resolve("ssh")); var fixture = new PlatformAiServiceTest.Fixture();
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setSourcePicker((a, b, c) -> root);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            PlatformAiServiceTest.model(runtime, fixture, "approval", "/approval", AiCapabilityType.TEXT, 512);
            PlatformAiServiceTest.route(runtime, AiPurposeType.APPROVAL, "approval");
            fixture.handler.set(exchange -> PlatformAiServiceTest.Fixture.reply(exchange, 200, FileApprovalIntegrationTest.response("{\"approved\":true}")));
            Path owned = Files.writeString(remote.files.resolve("owned"), "owned fixture");
            var target = new ActionTarget(SERVER.id(), "file", "/owned");
            try (var activation = runtime.capabilities().remoteCapabilities(OWNER, VERSION); var file = runtime.capabilities().source(OWNER, VERSION, activation)) {
                var handle = activation.launcher().submit(new Launch<>(request("server"), Set.of(target), mode, task -> {
                    callbackContext.set(task);
                    try {
                        var path = FilePath.remote(SERVER, "/owned");
                        var intent = FileActions.delete(file.getFile(path, task), false, DeleteEffect.PERMANENT).withSemantics(new ActionSemantics("file.delete.v1", Set.of(target)));
                        var grant = activation.interaction().approve(intent, task).value().orElseThrow();
                        assertEquals(TaskResult.SUCCEEDED, file.deleteFile(path, task, grant).outcome());
                        assertFalse(Files.exists(owned));
                        Files.writeString(owned, "replacement must survive");
                        assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> file.deleteFile(path, task, grant)).problem().code());
                        assertEquals("replacement must survive", Files.readString(owned));
                        assertEquals(mode == AuthorizationMode.FULL ? 0 : 1, reviews.approved.stream().filter(i -> i.type().equals("file-delete")).count());
                        return success(null);
                    } catch (Throwable failure) {
                        callbackFailure.set(failure);
                        throw failure;
                    }
                }));
                finish(runtime, handle);
                assertEquals(1, fixture.calls.get());
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    void finish(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!handle.completion().toCompletableFuture().isDone() && handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
            Thread.sleep(5);
        if (handle.snapshot().state() == TaskState.UNKNOWN) {
            callbackReturned(runtime, callbackContext.get());
            try (var recovery = runtime.tasks().bind(OWNER, VERSION, false).registerRecovery("fixture", (before, task) -> new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_CAPTURED_FILE_FAILURE", "Owned file failure retained for diagnosis", Map.of(), Optional.empty()))))) {
                assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            }
        }
        if (callbackFailure.get() != null)
            throw new AssertionError("Actual file callback failed", callbackFailure.get());
        assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
    }

    @Test
    void realFileServiceRecursiveUploadAndDownloadReuseOriginalTransfersAfterStallReauthentication() throws Exception {
        Path input = Files.createDirectories(root.resolve("input/sub"));
        byte[] content = new byte[100000];
        new Random(42).nextBytes(content);
        Files.write(input.resolve("large.bin"), content);
        Files.writeString(root.resolve("input/small.txt"), "small");
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setSourcePicker((title, task, cancel) -> root);
            var reviews = new Reviews();
            var retries = new AtomicInteger();
            reviews.stallPolicy = stall -> {
                retries.incrementAndGet();
                return TransferStallChoice.RETRY;
            };
            runtime.setInteractionPresenter(reviews);
            runtime.transfers().save(new TransferConfig(5, 1));
            var offsets = new CopyOnWriteArrayList<Long>();
            PlatformTransferStallTest.stallWrites(remote, 1, offsets);
            try (var activation = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, activation)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    var destination = FilePath.remote(SERVER, "/uploaded");
                    var choices = FileChoice.of(FileChoice.Directory.COPY, FileChoice.Conflict.COPY);
                    assertEquals(TaskResult.SUCCEEDED, file.uploadFile(FilePath.local(root.resolve("input")), destination, task, choices).outcome());
                    assertEquals(1, retries.get());
                    assertTrue(remote.authentications.get() >= 2);
                    assertArrayEquals(content, file.readFile(destination.child("sub").child("large.bin"), content.length, task));
                    assertEquals(TaskResult.SUCCEEDED, file.downloadFile(destination, FilePath.local(root.resolve("downloaded")), task, choices).outcome());
                    assertArrayEquals(content, Files.readAllBytes(root.resolve("downloaded/sub/large.bin")));
                    assertEquals("small", Files.readString(root.resolve("downloaded/small.txt")));
                    assertTrue(activation.linux().pendingTransfers(task).isEmpty());
                    assertEquals(4, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE'"));
                    assertEquals(2, file.operations(task).size());
                    return success(null);
                });
                var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                assertTrue(offsets.size() > 1);
                assertArrayEquals(content, Files.readAllBytes(remote.files.resolve("uploaded/sub/large.bin")));
            }
            assertEquals(0, remote.commands.get());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void refusedRemoteDeletionClosesEveryFileAndConnectionScopeWhileWaiting() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setSourcePicker((title, task, cancel) -> root);
            runtime.fileSettings().save(false);
            Path kept = Files.writeString(remote.files.resolve("kept"), "keep");
            var reviews = new Reviews();
            reviews.policy = intent -> !intent.type().equals("file-delete");
            runtime.setInteractionPresenter(reviews);
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    var report = file.deleteFile(FilePath.remote(SERVER, "/kept"), task);
                    return new OperationResult<>(report.outcome(), Optional.of(report), Optional.empty());
                });
                try {
                    state(handle, TaskState.WAITING);
                    assertEquals("keep", Files.readString(kept));
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind IN ('FILE','CONNECTION') AND released_at IS NULL"));
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation_step WHERE phase='BEFORE_DELETE'"));
                    assertFalse(handle.completion().toCompletableFuture().isDone());
                    Path moved = remote.files.resolve("temporarily-moved");
                    Files.move(kept, moved);
                    Files.move(moved, kept);
                } finally {
                    handle.requestCancel();
                }
                assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                assertEquals("keep", Files.readString(kept));
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}

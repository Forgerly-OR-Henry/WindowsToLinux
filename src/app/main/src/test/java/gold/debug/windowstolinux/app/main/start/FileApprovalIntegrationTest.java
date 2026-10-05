package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformAiServiceTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.file.action.FileActions;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileApprovalIntegrationTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledOriginalCopyOrMoveApprovalReportsCancellationWithoutChangingFiles(boolean move) throws Exception {
        Path input = Files.writeString(root.resolve("original.txt"), "original survives");
        Path output = root.resolve("not-created.txt");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setSourcePicker((title, task, cancel) -> root);
            runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts() {
                @Override
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(20, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return true;
                }
            });
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var failure = assertThrows(ApiException.class, () -> {
                        if (move) file.moveFile(FilePath.local(input), FilePath.local(output), task);
                        else file.copyFile(FilePath.local(input), FilePath.local(output), task);
                    });
                    assertEquals(ProblemCode.CANCELLED, failure.problem().code());
                    assertEquals("FILE_OPERATION_CANCELLED", failure.problem().eventCode());
                    assertEquals("original survives", Files.readString(input));
                    assertFalse(Files.exists(output));
                    assertTrue(file.operations(task).stream().flatMap(operation -> operation.steps().stream())
                        .noneMatch(step -> step.phase().startsWith("BEFORE_TARGET") || step.phase().equals("BEFORE_SOURCE_REMOVE")));
                    return new OperationResult<Void>(TaskResult.CANCELLED, Optional.empty(), Optional.of(failure.problem()));
                });
                try {
                    assertTrue(entered.await(20, TimeUnit.SECONDS));
                    handle.requestCancel();
                } finally {
                    release.countDown();
                }
                assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
                assertEquals("original survives", Files.readString(input));
                assertFalse(Files.exists(output));
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            release.countDown();
        }
    }

    void presenters(PlatformRuntime runtime, AtomicInteger humans, boolean decision) {
        runtime.setSourcePicker((title, task, cancel) -> root);
        runtime.setInteractionPresenter(new PlatformInteractionTest.Prompts() {
            @Override
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                humans.incrementAndGet();
                return decision;
            }
        });
    }

    void approvalModel(PlatformRuntime runtime, Fixture fixture) throws Exception {
        model(runtime, fixture, "review", "/approval", AiCapabilityType.TEXT, 512);
        route(runtime, AiPurposeType.APPROVAL, "review");
    }

    static String response(String content) throws java.io.IOException {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
            + JSON.writeValueAsString(content) + "},\"finish_reason\":\"stop\"}]}";
    }

    @Test
    void genuineHttpReviewBindsSharedActivationPermitToTheExactObservedObject() throws Exception {
        Path input = Files.writeString(root.resolve("owned-permanent-fixture"), "owned only");
        var humans = new AtomicInteger();
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, humans, true);
            approvalModel(runtime, fixture);
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, response("{\"approved\":true}")));
            try (var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, remote)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var path = FilePath.local(input);
                    var intent = FileActions.delete(file.getFile(path, task), false, DeleteEffect.RECYCLE);
                    var grant = remote.interaction().approve(intent, task).value().orElseThrow();
                    assertEquals(1, humans.get());
                    Files.writeString(input, "replacement must survive");
                    assertEquals(ProblemCode.ACCESS_DENIED, assertThrows(ApiException.class,
                        () -> file.deleteFile(path, task, grant)).problem().code());
                    assertEquals("replacement must survive", Files.readString(input));
                    assertEquals(1, humans.get());
                    assertEquals(1, fixture.calls.get());
                    return success(null);
                }));
                var sent = fixture.requests.remove();
                assertEquals("/approval", sent.path());
                assertTrue(sent.body().path("messages").get(1).path("content").textValue().contains("RECYCLE"));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation WHERE operation_type='DELETE_FILE'"));
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void permanentPermitForARecyclableObjectIsRejectedBeforeAnyIndependentAiOrHumanReview() throws Exception {
        Path input = Files.writeString(root.resolve("recyclable-kept"), "must survive");
        var humans = new AtomicInteger();
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, humans, true);
            approvalModel(runtime, fixture);
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, response("{\"approved\":true}")));
            try (var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, remote)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var info = file.getFile(FilePath.local(input), task);
                    assertTrue(new gold.debug.windowstolinux.app.file.LocalFileBackend().canRecycle(info.actual()));
                    assertEquals("FILE_DELETE_LOCAL_REVIEW", assertThrows(ApiException.class,
                        () -> remote.interaction().approve(FileActions.delete(info, false, DeleteEffect.PERMANENT), task)).problem().eventCode());
                    assertEquals(0, fixture.calls.get());
                    assertEquals(0, humans.get());
                    assertEquals("must survive", Files.readString(input));
                    assertTrue(file.operations(task).isEmpty());
                    return success(null);
                }));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"approved\":false}", "not-json", "{\"approved\":true} {}",
        "{\"approved\":true,\"approved\":false}", "{\"approved\":\"true\"}", "{\"approved\":true,\"extra\":0}"})
    void rejectedOrMalformedIndependentReviewNeverIssuesAuthorityOrChangesFiles(String reply) throws Exception {
        Path input = Files.writeString(root.resolve("kept"), "keep");
        var humans = new AtomicInteger();
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, humans, true);
            approvalModel(runtime, fixture);
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, response(reply)));
            try (var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, remote)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var intent = FileActions.delete(file.getFile(FilePath.local(input), task), false, DeleteEffect.RECYCLE);
                    assertThrows(ApiException.class, () -> remote.interaction().approve(intent, task));
                    assertEquals("keep", Files.readString(input));
                    assertTrue(file.operations(task).isEmpty());
                    assertEquals(0, humans.get());
                    return success(null);
                }));
            }
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void configurationDriftAfterReviewInvalidatesTheGenuineUnconsumedPermit() throws Exception {
        Path input = Files.writeString(root.resolve("kept"), "keep");
        var humans = new AtomicInteger();
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, humans, true);
            approvalModel(runtime, fixture);
            model(runtime, fixture, "next", "/next", AiCapabilityType.TEXT, 512);
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, response("{\"approved\":true}")));
            try (var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, remote)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var path = FilePath.local(input);
                    var grant = remote.interaction().approve(FileActions.delete(file.getFile(path, task), false, DeleteEffect.RECYCLE), task).value().orElseThrow();
                    route(runtime, AiPurposeType.APPROVAL, "next");
                    assertEquals("FILE_DELETE_REVIEW_EXPIRED", assertThrows(ApiException.class,
                        () -> file.deleteFile(path, task, grant)).problem().eventCode());
                    assertEquals("keep", Files.readString(input));
                    assertTrue(file.operations(task).isEmpty());
                    assertEquals(1, humans.get());
                    assertEquals(1, fixture.calls.get());
                    return success(null);
                }));
            }
        }
    }

    @Test
    void configurationDriftDuringIndependentHttpReviewPreventsGrantIssuance() throws Exception {
        Path input = Files.writeString(root.resolve("kept"), "keep");
        var humans = new AtomicInteger();
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, humans, true);
            approvalModel(runtime, fixture);
            model(runtime, fixture, "next", "/next", AiCapabilityType.TEXT, 512);
            fixture.handler.set(exchange -> {
                try {
                    route(runtime, AiPurposeType.APPROVAL, "next");
                } catch (Exception failure) {
                    throw new java.io.IOException(failure);
                }
                Fixture.reply(exchange, 200, response("{\"approved\":true}"));
            });
            try (var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, remote)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var intent = FileActions.delete(file.getFile(FilePath.local(input), task), false, DeleteEffect.RECYCLE);
                    assertThrows(ApiException.class, () -> remote.interaction().approve(intent, task));
                    assertEquals("keep", Files.readString(input));
                    assertEquals(0, humans.get());
                    return success(null);
                }));
            }
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void genuineOrdinaryHumanGrantCannotBePresentedAsIndependentDeletionPermit() throws Exception {
        Path input = Files.writeString(root.resolve("kept"), "keep");
        var humans = new AtomicInteger();
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            presenters(runtime, humans, true);
            try (var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var file = runtime.capabilities().source(OWNER, VERSION, remote)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    var path = FilePath.local(input);
                    var intent = FileActions.delete(file.getFile(path, task), false, DeleteEffect.RECYCLE);
                    var grant = ((PlatformInteractionService) remote.interaction()).confirmDeletion(intent, file.getFile(path, task), "", task).value().orElseThrow();
                    assertEquals("FILE_DELETE_INDEPENDENT_REVIEW", assertThrows(ApiException.class,
                        () -> file.deleteFile(path, task, grant)).problem().eventCode());
                    assertEquals("keep", Files.readString(input));
                    assertEquals(1, humans.get());
                    assertEquals(0, fixture.calls.get());
                    return success(null);
                }));
            }
        }
    }

    @Test
    void explanationOffMakesZeroHttpCallsAndPersistsWhileOrdinaryRefusalWaits() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var humans = new AtomicInteger();
            presenters(runtime, humans, false);
            model(runtime, fixture, "language", "/language", AiCapabilityType.TEXT, 512);
            route(runtime, AiPurposeType.DEPLOYMENT, "language");
            assertTrue(runtime.fileSettings().enabled());
            assertFalse(runtime.fileSettings().save(false));
            waitAndCancel(runtime, humans);
            assertEquals(0, fixture.calls.get());
            assertFalse(runtime.fileSettings().enabled());
        }
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            assertFalse(runtime.fileSettings().enabled());
        }
    }

    @Test
    void firstLanguageFailureDoesNotFallBackOrBlockOrdinaryHumanDecision() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var humans = new AtomicInteger();
            presenters(runtime, humans, false);
            model(runtime, fixture, "first", "/first", AiCapabilityType.TEXT, 512);
            model(runtime, fixture, "second", "/second", AiCapabilityType.TEXT, 512);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            fixture.handler.set(exchange -> Fixture.reply(exchange, 503, "{\"error\":{\"message\":\"fixture unavailable\"}}"));
            waitAndCancel(runtime, humans);
            assertTrue(fixture.calls.get() >= 1);
            assertTrue(fixture.requests.stream().allMatch(sent -> sent.path().equals("/first")));
            assertTrue(fixture.requests.stream().noneMatch(sent -> sent.body().toString().contains("metadata only: secret content")));
        }
    }

    void waitAndCancel(PlatformRuntime runtime, AtomicInteger humans) throws Exception {
        Path input = Files.writeString(root.resolve("kept"), "metadata only: secret content must not go to AI");
        try (var file = runtime.capabilities().source(OWNER, VERSION)) {
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var report = file.deleteFile(FilePath.local(input), task);
                return new OperationResult<>(report.outcome(), Optional.of(report), Optional.empty());
            });
            try {
                state(handle, TaskState.WAITING);
                assertEquals(1, humans.get());
                assertTrue(Files.exists(input));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind IN ('FILE','CONNECTION') AND released_at IS NULL"));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_operation_step WHERE phase='BEFORE_DELETE'"));
            } finally {
                handle.requestCancel();
            }
            assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void sharedFileFactoryRejectsClosedForeignAndMismatchedActivationCapabilities() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION);
             var foreign = PlatformRuntime.open(root.resolve("foreign"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            initialize(foreign, false);
            presenters(runtime, new AtomicInteger(), true);
            presenters(foreign, new AtomicInteger(), true);
            try (var remote = foreign.capabilities().remoteCapabilities(OWNER, VERSION)) {
                assertThrows(IllegalArgumentException.class, () -> runtime.capabilities().source(OWNER, VERSION, remote));
            }
            var remote = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
            assertThrows(IllegalArgumentException.class, () -> runtime.capabilities().source(OWNER, "other", remote));
            remote.close();
            assertThrows(IllegalArgumentException.class, () -> runtime.capabilities().source(OWNER, VERSION, remote));
        }
    }
}

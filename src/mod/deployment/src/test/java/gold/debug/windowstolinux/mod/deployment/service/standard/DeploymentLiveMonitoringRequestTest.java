package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import org.junit.jupiter.api.Test;

class DeploymentLiveMonitoringRequestTest {
    @Test
    void missingNativeCorrectionHasOneSafeSuccessfulLogProjectionAndTwoVisibleCalls() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.replies.add(new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(
            gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
            Optional.of(new AiResponse("Unsubmitted controlled verdict", "fixture-model", "stop",
                new AiUsage(Optional.of(10L), Optional.of(2L), Optional.empty(), Optional.empty(), Optional.empty()))), Optional.empty()));
        fixture.nativeReply(new AiToolCall("native-denial", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled native denial\"}"));
        var context = monitoredWithLogResult(fixture, new gold.debug.windowstolinux.appapi.task.log.LogWriteResult(java.util.UUID.randomUUID(),
            gold.debug.windowstolinux.appapi.task.log.LogStorage.DATABASE, false, Optional.empty()));
        var captured = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try (var output = new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(output);
            var result = new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson(context)
                .askCommand("Review exact controlled request", java.util.Map.of("operation", "controlled-review"), "fixture-route", fixture.task,
                    gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision::malformed);
            assertEquals("DENY", result.path("decision").asText());
        } finally {
            System.setOut(original);
        }
        var lines = captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        var corrections = fixture.events.stream().filter(e -> e.eventCode().equals("DEPLOYMENT_AI_SUBMISSION_CORRECTION")).toList();
        assertEquals(1, corrections.size());
        assertEquals(List.of(DeploymentStaticLiveAcceptanceTest.liveSubmissionCorrectionProjection(corrections.getFirst()).orElseThrow()),
            lines.stream().filter(line -> line.startsWith("AI_LIVE_SUBMISSION_CORRECTION ")).toList());
        assertEquals(2, lines.stream().filter(line -> line.startsWith("AI_LIVE_REQUEST purpose=APPROVAL ")).count());
        assertEquals(2, fixture.requests.size());
        assertEquals(2, fixture.events.stream().filter(e -> e.eventCode().equals("DEPLOYMENT_AI_USAGE")).count());
        assertTrue(fixture.requests.stream().allMatch(request -> request.requiredToolName().isEmpty()));
        assertTrue(lines.stream().filter(line -> line.startsWith("AI_LIVE_REQUEST ")).allMatch(line -> line.contains("operation=controlled-review")),
            "The correction must not replace the current JSON evidence used by monitoring");
    }

    @Test
    void correctionLogFailureReturnsAreCoverageGapsNotSuccessfulStorageMarkers() throws Exception {
        var problem = new gold.debug.windowstolinux.appapi.task.result.Problem(gold.debug.windowstolinux.appapi.task.result.ProblemCode.IO_FAILURE,
            "CONTROLLED_LOG_FAILURE", "FAKE_SECRET_MARKER", java.util.Map.of(), Optional.empty());
        var id = java.util.UUID.randomUUID();
        for (var writeResult : java.util.Arrays.asList(
            new gold.debug.windowstolinux.appapi.task.log.LogWriteResult(id, gold.debug.windowstolinux.appapi.task.log.LogStorage.NOT_SAVED, false, Optional.empty()),
            new gold.debug.windowstolinux.appapi.task.log.LogWriteResult(id, gold.debug.windowstolinux.appapi.task.log.LogStorage.EMERGENCY_FILE, false, Optional.empty()),
            new gold.debug.windowstolinux.appapi.task.log.LogWriteResult(id, gold.debug.windowstolinux.appapi.task.log.LogStorage.DATABASE, true, Optional.empty()),
            new gold.debug.windowstolinux.appapi.task.log.LogWriteResult(id, gold.debug.windowstolinux.appapi.task.log.LogStorage.DATABASE, false, Optional.of(problem)), null)) {
            var fixture = new NativeAiFixture();
            var context = monitoredWithLogResult(fixture, writeResult);
            var event = new gold.debug.windowstolinux.appapi.task.log.LogEvent(gold.debug.windowstolinux.appapi.task.log.LogLevel.WARN,
                "DEPLOYMENT_AI_SUBMISSION_CORRECTION", "FAKE_SECRET_MARKER",
                Optional.of("{\"purpose\":\"APPROVAL\",\"reason\":\"MISSING_NATIVE_CALL\",\"rejectedSubmission\":\"1\",\"nextSubmission\":\"2\",\"maximumSubmissions\":\"2\"}"),
                Optional.of(fixture.task.id()), Optional.of(id), Optional.empty());
            var captured = new java.io.ByteArrayOutputStream();
            var original = System.out;
            try (var output = new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8)) {
                System.setOut(output);
                assertSame(writeResult, context.logs().write(context.logs().register(new gold.debug.windowstolinux.appapi.task.log.LogCategory(
                    "deployment", "Deployment", Optional.empty())), event));
            } finally {
                System.setOut(original);
            }
            String observed = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(observed.contains("AI_LIVE_SUBMISSION_CORRECTION "));
            assertTrue(observed.startsWith("AI_LIVE_SUBMISSION_CORRECTION_COVERAGE_GAP "));
            assertFalse(observed.contains("FAKE_SECRET_MARKER"));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    private static ModContext monitoredWithLogResult(NativeAiFixture fixture, gold.debug.windowstolinux.appapi.task.log.LogWriteResult writeResult) throws Exception {
        var live = new DeploymentStaticLiveAcceptanceTest();
        for (String name : List.of("aiEnabled", "agentMode")) {
            var field = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(live, true);
        }
        var logs = (gold.debug.windowstolinux.appapi.task.log.LogService) java.lang.reflect.Proxy.newProxyInstance(
            gold.debug.windowstolinux.appapi.task.log.LogService.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.log.LogService.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(fixture.context.logs(), args);
                    return method.getName().equals("write") ? writeResult : result;
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        var actual = (ModContext) java.lang.reflect.Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("logs")) return logs;
                try {
                    return method.invoke(fixture.context, args);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        var monitored = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("monitored", ModContext.class, AppConfig.Ai.class);
        monitored.setAccessible(true);
        return (ModContext) monitored.invoke(live, actual, AppConfig.defaultsConfig().ai());
    }

    @Test
    void submissionCorrectionProjectionRejectsUnboundOrArbitraryDetails() {
        var task = java.util.UUID.randomUUID();
        var correction = java.util.UUID.randomUUID();
        for (String details : List.of("FAKE_SECRET_MARKER", "{}",
            "{\"purpose\":\"APPROVAL\",\"reason\":\"FAKE_SECRET_MARKER\"}")) {
            var event = new gold.debug.windowstolinux.appapi.task.log.LogEvent(gold.debug.windowstolinux.appapi.task.log.LogLevel.WARN,
                "DEPLOYMENT_AI_SUBMISSION_CORRECTION", "FAKE_SECRET_MARKER", Optional.of(details), Optional.of(task), Optional.of(correction), Optional.empty());
            assertTrue(DeploymentStaticLiveAcceptanceTest.liveSubmissionCorrectionProjection(event).isEmpty());
        }
        var valid = new gold.debug.windowstolinux.appapi.task.log.LogEvent(gold.debug.windowstolinux.appapi.task.log.LogLevel.WARN,
            "DEPLOYMENT_AI_SUBMISSION_CORRECTION", "FAKE_SECRET_MARKER",
            Optional.of("{\"purpose\":\"APPROVAL\",\"reason\":\"MISSING_NATIVE_CALL\",\"rejectedSubmission\":\"1\",\"nextSubmission\":\"2\",\"maximumSubmissions\":\"2\"}"),
            Optional.of(task), Optional.of(correction), Optional.empty());
        assertFalse(DeploymentStaticLiveAcceptanceTest.liveSubmissionCorrectionProjection(valid).orElseThrow().contains("FAKE_SECRET_MARKER"));
        assertTrue(DeploymentStaticLiveAcceptanceTest.liveSubmissionCorrectionProjection(new gold.debug.windowstolinux.appapi.task.log.LogEvent(
            valid.level(), valid.eventCode(), valid.message(), valid.details(), valid.taskId(), Optional.empty(), valid.outcome())).isEmpty());
    }

    @Test
    void approvalPageMetadataKeepsCursorsWithoutProjectingUntrustedText() throws Exception {
        var browse = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("browseMetadata", AiToolCall.class);
        browse.setAccessible(true);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var page = new AiToolCall("page", "evidence", "{\"pointer\":\"/completeExecutableInput/segments/0/text\",\"offset\":16000,\"limit\":8000}");
        var metadata = json.readTree((String) browse.invoke(null, page));
        assertEquals("/completeExecutableInput/segments/0/text", metadata.path("pointer").asText());
        assertEquals(16000, metadata.path("offset").asInt());
        assertEquals(8000, metadata.path("limit").asInt());
        var request = new AiToolCall("request", "request", "{\"part\":\"stdin\",\"offset\":0,\"limit\":8000}");
        assertEquals("stdin", json.readTree((String) browse.invoke(null, request)).path("part").asText());
        for (var call : List.of(
            new AiToolCall("foreign", "evidence", "{\"pointer\":\"/FAKE_SECRET_MARKER\",\"offset\":\"FAKE_SECRET_MARKER\",\"limit\":8000}"),
            new AiToolCall("foreign-part", "request", "{\"part\":\"FAKE_SECRET_MARKER\",\"offset\":0,\"limit\":8000}"),
            new AiToolCall("notes", "save_review_notes", "{\"text\":\"FAKE_SECRET_MARKER\"}"),
            new AiToolCall("invalid", "evidence", "{FAKE_SECRET_MARKER"))) {
            String safe = (String) browse.invoke(null, call);
            assertFalse(safe.contains("FAKE_SECRET_MARKER"));
            assertTrue(json.readTree(safe).isObject());
        }
    }

    @Test
    void malformedApprovalPageParametersReturnUnchangedForProductValidation() throws Exception {
        var live = new DeploymentStaticLiveAcceptanceTest();
        for (String name : List.of("aiEnabled", "agentMode")) {
            var field = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(live, true);
        }
        var fixture = new NativeAiFixture();
        var monitored = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("monitored", ModContext.class, AppConfig.Ai.class);
        monitored.setAccessible(true);
        var context = (ModContext) monitored.invoke(live, fixture.context, AppConfig.defaultsConfig().ai());
        var call = new AiToolCall("malformed-page", "request", "{malformed");
        fixture.nativeReply(call);
        var expected = fixture.replies.getLast().value().orElseThrow();
        var request = new AiRequest(AiPurpose.APPROVAL,
            List.of(new AiMessage(AiRole.USER, "{}", List.of())),
            8192, Duration.ofMinutes(1), false);
        assertSame(expected, context.ai().call(request, fixture.task).value().orElseThrow());
        assertEquals(List.of(call), expected.toolCalls());
        assertEquals(request, fixture.requests.getLast());
    }

    @Test
    void truncatedResponseReturnsToTheProductWithoutSupervisorCancellation() throws Exception {
        var live = new DeploymentStaticLiveAcceptanceTest();
        for (String name : List.of("aiEnabled", "agentMode")) {
            var field = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(live, true);
        }
        var fixture = new NativeAiFixture();
        var reply = new AiResponse("partial", "fixture-model", "length",
            new AiUsage(Optional.of(10L), Optional.of(2L), Optional.empty(), Optional.empty(), Optional.empty()));
        fixture.replies.add(new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(
            gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, Optional.of(reply), Optional.empty()));
        var cancellations = new java.util.concurrent.atomic.AtomicInteger();
        var handle = (gold.debug.windowstolinux.appapi.task.run.TaskHandle<?>) java.lang.reflect.Proxy.newProxyInstance(
            gold.debug.windowstolinux.appapi.task.run.TaskHandle.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.run.TaskHandle.class}, (proxy, method, args) -> {
                assertEquals("requestCancel", method.getName());
                cancellations.incrementAndGet();
                return null;
            });
        var field = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField("supervisedAgent");
        field.setAccessible(true);
        field.set(live, handle);
        var monitored = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("monitored", ModContext.class, AppConfig.Ai.class);
        monitored.setAccessible(true);
        var context = (ModContext) monitored.invoke(live, fixture.context, AppConfig.defaultsConfig().ai());
        var request = new AiRequest(AiPurpose.APPROVAL,
            List.of(new AiMessage(AiRole.USER, "{\"operation\":\"current-review\"}", List.of())),
            8192, Duration.ofMinutes(1), false);
        assertSame(reply, context.ai().call(request, fixture.task).value().orElseThrow());
        assertEquals(0, cancellations.get(), "The product approval/main loop must classify incomplete output itself");
    }

    @Test
    void realMonitoringWrapperPreservesNamedAndAutomaticToolSelection() throws Exception {
        var live = new DeploymentStaticLiveAcceptanceTest();
        for (String name : List.of("aiEnabled", "agentMode")) {
            var field = DeploymentStaticLiveAcceptanceTest.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(live, true);
        }
        var fixture = new NativeAiFixture();
        var monitored = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("monitored", ModContext.class, AppConfig.Ai.class);
        monitored.setAccessible(true);
        var context = (ModContext) monitored.invoke(live, fixture.context, AppConfig.defaultsConfig().ai());
        var tool = new AiToolDefinition("submit_result", "Submit verdict", "{\"type\":\"object\"}");
        var original = new AiRequest(AiPurpose.APPROVAL,
            List.of(new AiMessage(AiRole.SYSTEM, "Complete unchanged policy", List.of()),
                new AiMessage(AiRole.USER, "{\"operation\":\"current-review\"}", List.of())),
            Optional.of(8192), Duration.ofMinutes(10), false, List.of(tool), 1048576);
        for (var request : List.of(original.withRequiredTool("submit_result"), original)) {
            fixture.nativeReply(new AiToolCall("current-" + fixture.requests.size(), "submit_result", "{}"));
            context.ai().call(request, fixture.task);
            assertEquals(request, fixture.requests.getLast(), "Monitoring must preserve the complete request, including native tool choice");
        }
        assertEquals(2, fixture.requests.size());
        assertTrue(fixture.replies.isEmpty());
    }
}

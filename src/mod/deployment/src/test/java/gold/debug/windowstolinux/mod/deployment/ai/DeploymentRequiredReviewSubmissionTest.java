package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DeploymentRequiredReviewSubmissionTest {
    private static final String REVISION = "fixture-route";

    private static final ServerId SERVER = new ServerId(UUID.randomUUID().toString());

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void standaloneApprovalRequiresOneNativeSubmissionWithoutChangingItsDecisionContract() throws Exception {
        var fixture = new NativeAiFixture();
        deny(fixture, "standalone-denial");
        var result = new DeploymentAiJson(fixture.context).askCommand("Review this exact current request",
            facts("first"), REVISION, fixture.task, DeploymentReviewDecision::malformed);

        assertEquals("DENY", result.path("decision").asText());
        assertEquals(1, fixture.requests.size());
        var request = fixture.requests.getFirst();
        assertTrue(request.requiredToolName().isEmpty());
        assertEquals(AiPurpose.APPROVAL, request.purpose());
        assertFalse(request.jsonObjectResponse());
        assertEquals(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().approvalMaxOutputTokens(), request.maxOutputTokens().orElseThrow());
        assertEquals(1048576, request.maxRequestBytes());
        assertEquals(Duration.ofSeconds(600), request.timeout());
        assertEquals(JSON.valueToTree(facts("first")), JSON.readTree(request.messages().getLast().text()));
        var schema = JSON.readTree(request.tools().getFirst().inputSchema());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(Set.of("decision", "requiresUserConfirmation", "reason"),
            Set.copyOf(JSON.convertValue(schema.path("required"), List.class)));
        assertEquals(List.of("APPROVE", "DENY"), JSON.convertValue(schema.at("/properties/decision/enum"), List.class));
        int actualOutputLimit = 32768;
        var encoded = gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(
            gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS).encode("deepseek-flash", request,
            actualOutputLimit, gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.LOW, Map.of());
        var wire = JSON.readTree(encoded);
        assertEquals("auto", wire.path("tool_choice").asText());
        assertEquals(actualOutputLimit, wire.path("max_tokens").asInt(), "The encoded actual limit is separate from the caller's requested budget");
        assertEquals("low", wire.path("reasoning_effort").asText());
    }

    @Test
    void conversationStillRequiresFreshDecisionsAndRetainsTheOriginalReceipt() throws Exception {
        var fixture = new NativeAiFixture();
        var ai = new DeploymentAiJson(fixture.context, conversation(fixture));
        for (String current : List.of("first", "second")) {
            deny(fixture, current + "-denial");
            var result = ai.askCommand("Review this exact current request", facts(current), REVISION,
                fixture.task, DeploymentReviewDecision::malformed);
            assertEquals("DENY", result.path("decision").asText());
            var request = fixture.requests.getLast();
            assertTrue(request.requiredToolName().isEmpty());
            assertEquals(current, JSON.readTree(request.messages().getLast().text()).path("exactAction").asText());
        }
        assertEquals(2, fixture.requests.size());
        assertEquals(fixture.requests.getFirst().tools(), fixture.requests.getLast().tools());
        assertTrue(fixture.requests.getLast().messages().stream()
            .anyMatch(message -> message.toolResult().map(receipt -> receipt.callId().equals("first-denial") && !receipt.error()).orElse(false)));
    }

    @Test
    void plainTextHealthReviewStopsBeforeHostAuthorizationAndDispatchAfterOneFormatCorrection() throws Exception {
        var fixture = new NativeAiFixture();
        textReply(fixture);
        textReply(fixture);
        var boundary = boundary(fixture);
        var failure = assertThrows(ApiException.class, () -> boundary.channel().healthCheck(unit(), check()));

        assertEquals(ProblemCode.INVALID_INPUT, failure.problem().code());
        assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", failure.problem().eventCode());
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("A complete native result submission is required", failure.getCause().getMessage());
        assertStopped(fixture, boundary, 2);
        assertCorrection(fixture);
    }

    @ParameterizedTest
    @MethodSource("correctedReviews")
    void completeTextGetsOneLocalCorrectionAndStillRequiresANativeDecision(boolean retained, String decision) throws Exception {
        var fixture = new NativeAiFixture();
        textReply(fixture);
        fixture.nativeReply(new AiToolCall("corrected", "submit_result", "{\"decision\":\"" + decision
            + "\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled native review\"}"));
        var ai = retained ? new DeploymentAiJson(fixture.context, conversation(fixture)) : new DeploymentAiJson(fixture.context);
        var result = ai.askCommand("Review the exact current request", facts("first"), REVISION,
            fixture.task, DeploymentReviewDecision::malformed);

        assertEquals(decision, result.path("decision").asText());
        assertEquals(2, fixture.requests.size());
        assertCorrection(fixture);
        var first = fixture.requests.getFirst();
        var second = fixture.requests.getLast();
        assertEquals(first.tools(), second.tools());
        assertEquals(first.timeout(), second.timeout());
        assertEquals(first.maxOutputTokens(), second.maxOutputTokens());
        assertEquals(first.generationRecoveryAttempts(), second.generationRecoveryAttempts());
        assertTrue(second.messages().stream().noneMatch(m -> m.toolResult().isPresent()), "No call id existed for a tool result");
        var withoutCorrection = new ArrayList<>(second.messages());
        var correction = withoutCorrection.remove(withoutCorrection.size() - 2);
        assertEquals(first.messages(), withoutCorrection, "All original evidence stays unchanged and current facts stay last");
        assertEquals(AiRole.USER, correction.role());
        assertTrue(correction.text().startsWith("SUBMISSION_REJECTED: native submit_result is missing."));
        assertTrue(correction.text().contains("Text alone is not a decision or execution permission"));
        assertTrue(second.messages().stream().noneMatch(m -> m.text().contains("UNTRUSTED_TEXT_APPROVE")));

        if (retained) {
            deny(fixture, "next-command");
            ai.askCommand("Review another exact request", facts("second"), REVISION,
                fixture.task, DeploymentReviewDecision::malformed);
            var next = fixture.requests.getLast().messages();
            assertTrue(next.stream().noneMatch(m -> m.text().startsWith("SUBMISSION_REJECTED: native submit_result is missing.")),
                "A local missing-call correction must not pollute the next command");
            assertEquals("second", JSON.readTree(next.getLast().text()).path("exactAction").asText());
            assertTrue(next.stream().anyMatch(m -> m.toolResult().map(r -> r.callId().equals("corrected")).orElse(false)));
        }
    }

    private static Stream<Arguments> correctedReviews() {
        return Stream.of(false, true).flatMap(retained -> Stream.of("APPROVE", "DENY")
            .map(decision -> Arguments.of(retained, decision)));
    }

    @ParameterizedTest
    @MethodSource("mixedInvalidReviews")
    void missingCallAndInvalidNativeShapeShareTheSameTwoSubmissionBudget(boolean retained, boolean textFirst) throws Exception {
        var fixture = new NativeAiFixture();
        if (textFirst) textReply(fixture);
        fixture.nativeReply(new AiToolCall("bad-native", "submit_result", "{\"decision\":\"APPROVE\"}"));
        if (!textFirst) textReply(fixture);
        deny(fixture, "unreachable-third");
        var ai = retained ? new DeploymentAiJson(fixture.context, conversation(fixture)) : new DeploymentAiJson(fixture.context);

        if (retained) {
            assertThrows(IOException.class, () -> ai.askCommand("Review current request", facts("first"), REVISION,
                fixture.task, DeploymentReviewDecision::malformed));
        } else {
            var boundary = boundary(fixture);
            assertThrows(ApiException.class, () -> boundary.channel().healthCheck(unit(), check()));
            assertStopped(fixture, boundary, 2);
        }
        assertEquals(2, fixture.requests.size());
        assertEquals(1, fixture.replies.size(), "No third model call may escape the common budget");
        assertEquals(textFirst ? 1 : 0, corrections(fixture).size());
        assertTrue(fixture.requests.stream().allMatch(r -> r.requiredToolName().isEmpty()));
    }

    private static Stream<Arguments> mixedInvalidReviews() {
        return Stream.of(false, true).flatMap(retained -> Stream.of(false, true).map(first -> Arguments.of(retained, first)));
    }

    @Test
    void transportUnknownAfterFormatCorrectionStaysUnknownAndDoesNotGetAnotherCall() {
        var fixture = new NativeAiFixture();
        textReply(fixture);
        fixture.replies.add(new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(
            ProblemCode.UNKNOWN, "MODEL_UNKNOWN", "Controlled unknown transport", Map.of(), Optional.empty()))));
        deny(fixture, "unreachable-third");
        var failure = assertThrows(DeploymentAiJson.CapabilityOutcomeException.class, () -> new DeploymentAiJson(fixture.context)
            .askCommand("Review current request", facts("first"), REVISION, fixture.task, DeploymentReviewDecision::malformed));
        assertEquals(TaskResult.UNKNOWN, failure.outcome());
        assertEquals(2, fixture.requests.size());
        assertEquals(1, fixture.replies.size());
        assertEquals(1, corrections(fixture).size());
    }

    @Test
    void correctionIsNotOfferedForLanguageOtherFinishReasonsOrInconsistentNativeCalls() {
        for (String finish : List.of("content_filter", "tool_calls", "stop")) {
            var fixture = new NativeAiFixture();
            var calls = finish.equals("stop") ? List.of(new AiToolCall("unexpected", "submit_result", "{}")) : List.<AiToolCall>of();
            fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("Controlled incomplete",
                "fixture-model", finish, new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                calls, Optional.empty())), Optional.empty()));
            assertThrows(IOException.class, () -> new DeploymentAiJson(fixture.context).askCommand("Review", facts("first"),
                REVISION, fixture.task, DeploymentReviewDecision::malformed));
            assertEquals(1, fixture.requests.size());
            assertTrue(corrections(fixture).isEmpty());
        }
        var language = new NativeAiFixture();
        textReply(language);
        assertThrows(IOException.class, () -> new DeploymentAiJson(language.context).ask(AiPurpose.LANGUAGE, "Analyse", Map.of(), REVISION, language.task));
        assertEquals(1, language.requests.size());
        assertTrue(corrections(language).isEmpty());
    }

    @Test
    void completeHealthDenialKeepsItsReasonAndNeverRequestsHostAuthorization() throws Exception {
        var fixture = new NativeAiFixture();
        deny(fixture, "health-denial");
        var boundary = boundary(fixture);
        var failure = assertThrows(ApiException.class, () -> boundary.channel().healthCheck(unit(), check()));

        assertEquals(ProblemCode.ACCESS_DENIED, failure.problem().code());
        assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", failure.problem().eventCode());
        assertEquals("Controlled current request denial", failure.problem().context().get("reviewReason"));
        assertEquals("false", failure.problem().context().get("requiresUserConfirmation"));
        assertStopped(fixture, boundary, 1);
    }

    @Test
    void automaticToolSelectionDoesNotAcceptAnIncompleteApprovalShapeOrDispatchIt() throws Exception {
        var fixture = new NativeAiFixture();
        for (String id : List.of("invalid-first", "invalid-correction"))
            fixture.nativeReply(new AiToolCall(id, "submit_result", "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false}"));
        var boundary = boundary(fixture);
        var failure = assertThrows(ApiException.class, () -> boundary.channel().healthCheck(unit(), check()));

        assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", failure.problem().eventCode());
        assertStopped(fixture, boundary, 2);
        assertEquals("invalid-first", fixture.requests.getLast().messages().getLast().toolResult().orElseThrow().callId());
        assertTrue(fixture.requests.getLast().messages().getLast().toolResult().orElseThrow().error());
    }

    @ParameterizedTest
    @MethodSource("unforcedCalls")
    void languageAndOtherToolSetsKeepTheirOriginalSelection(AiPurpose purpose, List<AiToolDefinition> definitions,
                                                            boolean retainedConversation) throws Exception {
        var fixture = new NativeAiFixture();
        var ai = retainedConversation ? new DeploymentAiJson(fixture.context, conversation(fixture)) : new DeploymentAiJson(fixture.context);
        fixture.nativeReply(new AiToolCall("controlled-call", "submit_result", "{}"));
        var messages = List.of(new AiMessage(AiRole.USER, "Complete controlled request", List.of()));
        ai.call(purpose, messages, definitions, REVISION, fixture.task, false);

        assertEquals(1, fixture.requests.size());
        var request = fixture.requests.getFirst();
        assertTrue(request.requiredToolName().isEmpty());
        assertEquals(purpose, request.purpose());
        assertEquals(messages, request.messages());
        assertEquals(definitions, request.tools());
    }

    private static Stream<Arguments> unforcedCalls() {
        var submit = new AiToolDefinition("submit_result", "Controlled structured submission", "{\"type\":\"object\"}");
        var other = new AiToolDefinition("inspect", "Controlled source inspection", "{\"type\":\"object\"}");
        return Stream.of(false, true).flatMap(retained -> Stream.of(
            Arguments.of(AiPurpose.LANGUAGE, List.of(submit), retained),
            Arguments.of(AiPurpose.APPROVAL, List.of(), retained),
            Arguments.of(AiPurpose.APPROVAL, List.of(other), retained),
            Arguments.of(AiPurpose.APPROVAL, List.of(submit, other), retained)));
    }

    private static DeploymentApprovalConversation conversation(NativeAiFixture fixture) {
        return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApprovalConversation(fixture.context, fixture.task, SERVER, "verified-controlled-target", "a".repeat(64), REVISION);
    }

    private static Map<String, ?> facts(String action) {
        return Map.of("serverTargets", List.of(SERVER.id()), "operation", "reviewed-command",
            "exactAction", action, "completeExecutableInput", "printf controlled-health\n");
    }

    private static void deny(NativeAiFixture fixture, String id) {
        fixture.nativeReply(new AiToolCall(id, "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled current request denial\"}"));
    }

    private static void textReply(NativeAiFixture fixture) {
        fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new AiResponse("UNTRUSTED_TEXT_APPROVE", "fixture-model", "stop",
                new AiUsage(Optional.of(3558L), Optional.of(659L), Optional.empty(), Optional.empty(), Optional.empty()))), Optional.empty()));
    }

    private static List<gold.debug.windowstolinux.appapi.task.log.LogEvent> corrections(NativeAiFixture fixture) {
        return fixture.events.stream().filter(event -> event.eventCode().equals("DEPLOYMENT_AI_SUBMISSION_CORRECTION")).toList();
    }

    private static void assertCorrection(NativeAiFixture fixture) throws Exception {
        var events = corrections(fixture);
        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals(Optional.of(fixture.task.id()), event.taskId());
        assertTrue(event.operationId().isPresent(), "Each correction has an independent diagnostic identity, not a generation retry number");
        assertTrue(event.outcome().isEmpty(), "A format correction is not a terminal task result");
        assertEquals(Map.of("purpose", "APPROVAL", "reason", "MISSING_NATIVE_CALL", "rejectedSubmission", "1",
            "nextSubmission", "2", "maximumSubmissions", "2"), JSON.readValue(event.details().orElseThrow(), Map.class));
        assertTrue(event.details().orElseThrow().length() < 256);
        assertTrue(fixture.requests.stream().allMatch(r -> r.requiredToolName().isEmpty()));
        assertEquals(2, fixture.events.stream().filter(e -> e.eventCode().equals("DEPLOYMENT_AI_USAGE")).count());
    }

    private static DeploymentUnitIdentity unit() {
        return DeploymentUnitIdentity.forManaged("controlled-health", SERVER, "b".repeat(64));
    }

    private static DeploymentHealthCheck check() {
        return new DeploymentHealthCheck.Process(5, 1);
    }

    private record Boundary(DeploymentPublishChannel channel, AtomicInteger authorizations, AtomicInteger dispatches) {
    }

    private static Boundary boundary(NativeAiFixture fixture) throws Exception {
        var authorizations = new AtomicInteger();
        var dispatches = new AtomicInteger();
        var host = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(),
            new Class<?>[]{InteractionService.class}, (proxy, method, arguments) -> {
                authorizations.incrementAndGet();
                throw new AssertionError("Unapproved review reached host authorization: " + method.getName());
            });
        var ui = (UiService) Proxy.newProxyInstance(UiService.class.getClassLoader(), new Class<?>[]{UiService.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("environment")) return new UiEnvironment(Locale.ENGLISH, "LIGHT", false);
                throw new AssertionError(method.getName());
            });
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("ui")) return ui;
                if (method.getName().equals("interaction")) return host;
                try {
                    return method.invoke(fixture.context, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        var reviewed = new DeploymentCommandReviewScope(context, host, fixture.task, "AUTOMATIC", false, () -> REVISION);
        var session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
            (proxy, method, arguments) -> {
                dispatches.incrementAndGet();
                throw new AssertionError("Unapproved review reached remote execution: " + method.getName());
            });
        return new Boundary(new DeploymentPublishChannel(session, reviewed.interaction(), SERVER, fixture.task, "f".repeat(64)),
            authorizations, dispatches);
    }

    private static void assertStopped(NativeAiFixture fixture, Boundary boundary, int expectedCalls) {
        assertEquals(expectedCalls, fixture.requests.size());
        assertTrue(fixture.requests.stream().allMatch(request -> request.requiredToolName().isEmpty()));
        assertEquals(0, boundary.authorizations().get());
        assertEquals(0, boundary.dispatches().get());
    }
}

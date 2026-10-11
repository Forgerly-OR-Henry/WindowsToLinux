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
        assertEquals(Optional.of("submit_result"), request.requiredToolName());
        assertEquals(AiPurpose.APPROVAL, request.purpose());
        assertFalse(request.jsonObjectResponse());
        assertEquals(8192, request.maxOutputTokens().orElseThrow());
        assertEquals(1048576, request.maxRequestBytes());
        assertEquals(Duration.ofSeconds(600), request.timeout());
        assertEquals(JSON.valueToTree(facts("first")), JSON.readTree(request.messages().getLast().text()));
        var schema = JSON.readTree(request.tools().getFirst().inputSchema());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(Set.of("decision", "requiresUserConfirmation", "reason"),
            Set.copyOf(JSON.convertValue(schema.path("required"), List.class)));
        assertEquals(List.of("APPROVE", "DENY"), JSON.convertValue(schema.at("/properties/decision/enum"), List.class));
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
            assertEquals(Optional.of("submit_result"), request.requiredToolName());
            assertEquals(current, JSON.readTree(request.messages().getLast().text()).path("exactAction").asText());
        }
        assertEquals(2, fixture.requests.size());
        assertEquals(fixture.requests.getFirst().tools(), fixture.requests.getLast().tools());
        assertTrue(fixture.requests.getLast().messages().stream()
            .anyMatch(message -> message.toolResult().map(receipt -> receipt.callId().equals("first-denial") && !receipt.error()).orElse(false)));
    }

    @Test
    void plainTextHealthReviewStopsBeforeHostAuthorizationAndDispatchWithoutRetry() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new AiResponse("Unsubmitted controlled review", "fixture-model", "stop",
                new AiUsage(Optional.of(3558L), Optional.of(659L), Optional.empty(), Optional.empty(), Optional.empty()))), Optional.empty()));
        var boundary = boundary(fixture);
        var failure = assertThrows(ApiException.class, () -> boundary.channel().healthCheck(unit(), check()));

        assertEquals(ProblemCode.INVALID_INPUT, failure.problem().code());
        assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", failure.problem().eventCode());
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("A complete native result submission is required", failure.getCause().getMessage());
        assertStopped(fixture, boundary, 1);
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
    void forcedToolDoesNotAcceptAnIncompleteApprovalShapeOrDispatchIt() throws Exception {
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
        return new DeploymentApprovalConversation(fixture.context, fixture.task, SERVER, "verified-controlled-target", "a".repeat(64), REVISION);
    }

    private static Map<String, ?> facts(String action) {
        return Map.of("serverTargets", List.of(SERVER.id()), "operation", "reviewed-command",
            "exactAction", action, "completeExecutableInput", "printf controlled-health\n");
    }

    private static void deny(NativeAiFixture fixture, String id) {
        fixture.nativeReply(new AiToolCall(id, "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled current request denial\"}"));
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
        assertTrue(fixture.requests.stream().allMatch(request -> request.requiredToolName().equals(Optional.of("submit_result"))));
        assertEquals(0, boundary.authorizations().get());
        assertEquals(0, boundary.dispatches().get());
    }
}

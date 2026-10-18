package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;

class DeploymentAiCallMetricsTest {
    @Test
    void unknownGenerationRemainsUnknownAndItsUsageRoundTripsThroughTheRealLogCodec() throws Exception {
        var problem = AiGenerationAttempt.attach(new Problem(ProblemCode.UNKNOWN, "AI_TIMEOUT", "Provider deadline elapsed",
            Map.of(), Optional.empty()), List.of(new AiGenerationAttempt(UUID.randomUUID(), 1, "fixture", "OPENAI_CHAT_COMPLETIONS",
            TaskResult.UNKNOWN, "AI_TIMEOUT", "NONE", 120000,
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), 100, 32768)));
        var target = new gold.debug.windowstolinux.app.db.log.LogStore.Target(UUID.randomUUID(),
            "gold.debug.windowstolinux.mod.deployment", "v1", UUID.randomUUID(), "m_" + "a".repeat(64) + "_v1",
            "deployment", "deployment_log", "Deployment");
        for (boolean thrown : List.of(false, true)) {
            var fixture = new NativeAiFixture();
            var result = new OperationResult<AiResponse>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem));
            if (thrown) fixture.refusal = Optional.of(new ApiException(problem));
            else fixture.replies.add(result);
            var metrics = new DeploymentAiCallMetrics(fixture.context, fixture.task, "native_tools");
            if (thrown)
                assertSame(problem, assertThrows(ApiException.class, () -> metrics.call(request, "fixture-route")).problem());
            else assertSame(result, metrics.call(request, "fixture-route"));
            assertEquals(1, fixture.requests.size());
            assertEquals(1, fixture.events.size());
            var event = fixture.events.getFirst();
            assertTrue(event.outcome().isEmpty(), "An unresolved model observation is not a terminal task outcome");
            var persisted = gold.debug.windowstolinux.app.db.log.LogRecordCodec.create(target, "1.0.0", event);
            assertEquals(persisted, gold.debug.windowstolinux.app.db.log.LogRecordCodec.decode(target,
                gold.debug.windowstolinux.app.db.log.LogRecordCodec.encode(target.key(), persisted)));
            var facts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(persisted.details());
            assertEquals("UNKNOWN", facts.path("outcome").asText());
            assertEquals("UNKNOWN", facts.path("generationAttempt.1.outcome").asText());
            assertEquals("UNKNOWN", facts.path("generationAttempt.1.inputTokens").asText());
            assertEquals("1", facts.path("generationUnknownUsageCount").asText());
            assertFalse(persisted.truncated());
        }
    }

    @Test
    void physicalUnknownUsageSurvivesSuccessfulRecoveryAndFailureWithoutInventingTotals() throws Exception {
        UUID generation = UUID.randomUUID();
        var missing = new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var attempts = List.of(new AiGenerationAttempt(generation, 1, "fixture", "OPENAI_CHAT_COMPLETIONS", TaskResult.UNKNOWN,
                "AI_TRANSPORT_UNKNOWN", "HTTP_BODY_INCOMPLETE", 1, missing, 100, 32768),
            new AiGenerationAttempt(generation, 2, "fixture", "OPENAI_CHAT_COMPLETIONS", TaskResult.SUCCEEDED,
                "AI_GENERATION_COMPLETE", "NONE", 1, usage, 100, 32768));
        var response = new AiResponse("ok", "fixture", "stop", usage, List.of(), Optional.empty(), Optional.of(100), Optional.of(32768), attempts);
        var succeeded = new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(response), Optional.<Problem>empty());
        var facts = DeploymentAiCallMetrics.facts(request, succeeded);
        assertEquals("2", facts.get("generationAttemptCount"));
        assertEquals("1", facts.get("generationUnknownUsageCount"));
        assertEquals("3", facts.get("generationKnownInputTokens"));
        assertEquals("4", facts.get("generationKnownOutputTokens"));
        assertEquals("false", facts.get("generationUsageComplete"));
        assertEquals("UNKNOWN", facts.get("generationAttempt.1.inputTokens"));
        assertEquals("UNKNOWN", facts.get("generationAttempt.1.outcome"));
        assertEquals("3", facts.get("generationAttempt.2.inputTokens"));
        var failed = AiGenerationAttempt.attach(new Problem(ProblemCode.IO_FAILURE, "AI_TRANSPORT_UNKNOWN", "Missing body", Map.of(), Optional.empty()), attempts);
        assertEquals(attempts, AiGenerationAttempt.fromProblem(failed));
        var failureFacts = DeploymentAiCallMetrics.facts(request, new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(failed)));
        assertEquals("2", failureFacts.get("generationAttemptCount"));
        assertEquals("UNKNOWN", failureFacts.get("inputTokens"));
        assertEquals("3", failureFacts.get("generationKnownInputTokens"));
        var fixture = new NativeAiFixture();
        fixture.replies.add(succeeded);
        new DeploymentAiCallMetrics(fixture.context, fixture.task, "native_tools").call(request, "fixture-route");
        var retained = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.events.getFirst().details().orElseThrow());
        assertEquals("UNKNOWN", retained.path("generationAttempt.1.inputTokens").asText());
        assertEquals("false", retained.path("generationUsageComplete").asText());
        assertFalse(retained.toString().contains("analyse"));
        fixture.refusal = Optional.of(new ApiException(failed));
        var thrown = assertThrows(ApiException.class, () -> new DeploymentAiCallMetrics(fixture.context, fixture.task, "native_tools").call(request, "fixture-route"));
        assertSame(failed, thrown.problem());
        var exceptional = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.events.getLast().details().orElseThrow());
        assertEquals("UNKNOWN", exceptional.path("outcome").asText());
        assertEquals("1", exceptional.path("generationUnknownUsageCount").asText());
    }

    private final AiRequest request = new AiRequest(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "analyse", List.of())), 65536, Duration.ofSeconds(10), false);

    private final AiUsage usage = new AiUsage(Optional.of(3L), Optional.of(4L), Optional.empty(), Optional.empty(), Optional.empty());

    @Test
    void actualBudgetIsReturnedAndRecordedWithoutConfusingItWithTheRequestedBudget() throws Exception {
        var fixture = new NativeAiFixture();
        var response = new AiResponse("ok", "fixture", "stop", usage, List.of(), Optional.empty(), Optional.of(100), Optional.of(32768));
        fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(response), Optional.empty()));
        assertSame(response, new DeploymentAiJson(fixture.context).call(request.purpose(), request.messages(), List.of(), "fixture-route", fixture.task, true));
        var logged = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.events.getFirst().details().orElseThrow());
        assertEquals("65536", logged.path("requestedMaximumOutputTokens").asText());
        assertEquals("32768", logged.path("maximumOutputTokens").asText());
        assertEquals("4", logged.path("outputTokens").asText());
    }

    @Test
    void missingAndMalformedActualBudgetsStayUnknownIncludingOmittedRequests() {
        var result = new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("ok", "fixture", "stop", usage)), Optional.<Problem>empty());
        assertEquals("UNKNOWN", DeploymentAiCallMetrics.facts(request, result).get("maximumOutputTokens"));
        var omitted = new AiRequest(request.purpose(), request.messages(), request.timeout(), false);
        assertEquals("UNKNOWN", DeploymentAiCallMetrics.facts(omitted, result).get("requestedMaximumOutputTokens"));
        for (String invalid : List.of("0", "-1", "2147483648", "01", "private")) {
            var failed = new OperationResult<AiResponse>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "invalid",
                Map.of("effectiveMaxOutputTokens", invalid), Optional.empty())));
            assertEquals("UNKNOWN", DeploymentAiCallMetrics.facts(request, failed).get("maximumOutputTokens"));
        }
    }

    @Test
    void failedRetentionMetricsAreRecordedBeforeTheDeploymentCallerRejectsExecution() throws Exception {
        var fixture = new NativeAiFixture();
        var paid = new AiResponse("", "fixture", "tool_calls", usage, List.of(), Optional.empty(), Optional.of(123), Optional.of(32768));
        fixture.replies.add(new OperationResult<>(TaskResult.FAILED, Optional.of(paid),
            Optional.of(new Problem(ProblemCode.UNAVAILABLE, "AI_SESSION_STATE_LIMIT", "Native continuation state cannot be retained", Map.of(), Optional.empty()))));
        var failure = assertThrows(DeploymentAiJson.CapabilityOutcomeException.class,
            () -> new DeploymentAiJson(fixture.context).call(request.purpose(), request.messages(), List.of(), "fixture-route", fixture.task, false));
        assertEquals(TaskResult.FAILED, failure.outcome());
        assertEquals("AI_SESSION_STATE_LIMIT", failure.problem().orElseThrow().eventCode());
        var logged = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.events.getFirst().details().orElseThrow());
        assertEquals("3", logged.path("inputTokens").asText());
        assertEquals("4", logged.path("outputTokens").asText());
        assertEquals("UNKNOWN", logged.path("cachedInputTokens").asText());
        assertEquals("32768", logged.path("maximumOutputTokens").asText());
        assertEquals("123", logged.path("encodedRequestBytes").asText());
        assertEquals(1, fixture.requests.size());
    }
}

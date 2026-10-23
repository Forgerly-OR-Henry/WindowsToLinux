package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;

class DeploymentAiJsonTest {
    @Test
    void independentCommandReviewExposesItsExactDecisionSchema() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.nativeReply(new AiToolCall("review", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Unowned destructive target\"}"));
        var result = new DeploymentAiJson(fixture.context).askCommand("Independent review", Map.of(),
            "fixture-route", fixture.task, node -> false);
        assertEquals("DENY", result.path("decision").asText());
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.requests.getFirst().tools().getFirst().inputSchema());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(3, schema.path("required").size());
        assertEquals(List.of("APPROVE", "DENY"), new com.fasterxml.jackson.databind.ObjectMapper()
            .convertValue(schema.at("/properties/decision/enum"), List.class));
        assertEquals(2000, schema.at("/properties/reason/maxLength").asInt());
        assertEquals("boolean", schema.at("/properties/requiresUserConfirmation/type").asText());
        assertEquals(1, fixture.requests.size());
    }

    @Test
    void exhaustedApprovalOutputIsClassifiedWithoutCorrectionOrRetry() {
        var fixture = new NativeAiFixture();
        fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "fixture-model", "length",
            new AiUsage(Optional.of(8407L), Optional.of(8192L), Optional.of(2176L), Optional.of(6231L), Optional.of(8192L)))), Optional.empty()));
        var exhausted = assertThrows(DeploymentAiJson.CapabilityOutcomeException.class, () -> new DeploymentAiJson(fixture.context)
            .askCommand("Complete independent review", Map.of(), "fixture-route", fixture.task, node -> true));
        assertEquals(TaskResult.FAILED, exhausted.outcome());
        assertEquals("DEPLOYMENT_AI_OUTPUT_BUDGET_EXHAUSTED", exhausted.problem().orElseThrow().eventCode());
        assertEquals(1, fixture.requests.size(), "A truncated decision must not trigger another paid format correction");
    }

    @Test
    void configuredBudgetsReachOrdinaryExtendedAndIndependentApprovalRequests() throws Exception {
        var fixture = new NativeAiFixture();
        var ai = new DeploymentAiJson(fixture.context,
            new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Ai(4096, 12288, 2048,
                new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Requests(45, 75, 600),
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution(),
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().history()));
        var messages = List.of(new AiMessage(AiRole.USER, "bounded original evidence", List.of()));
        for (int index = 0; index < 3; index++) {
            fixture.nativeReply(new AiToolCall("configured" + index, "submit_result", "{}"));
            ai.call(index == 2 ? AiPurpose.APPROVAL : AiPurpose.LANGUAGE, messages, List.of(), "fixture-route", fixture.task, index != 0);
        }
        assertEquals(List.of(4096, 12288, 2048), fixture.requests.stream().map(request -> request.maxOutputTokens().orElseThrow()).toList());
        assertEquals(List.of(45L, 600L, 75L), fixture.requests.stream().map(request -> request.timeout().toSeconds()).toList());
        assertEquals(3, fixture.requests.size(), "No clamping fallback or extra model call is introduced");
    }

    @Test
    void budgetRefusalIsReturnedWithoutClippingOrRetryingTheOriginalRequest() throws Exception {
        var fixture = new NativeAiFixture();
        var refused = new ApiException(new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "invalid input",
            Map.of("maximumOutputTokens", "32768", "effectiveMaxOutputTokens", "32768"), Optional.empty()));
        fixture.refusal = Optional.of(refused);
        assertSame(refused, assertThrows(ApiException.class, () -> new DeploymentAiJson(fixture.context)
            .call(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "analyse", List.of())), List.of(), "fixture-route", fixture.task, true)));
        assertEquals(1, fixture.requests.size());
        assertEquals(Optional.of(65536), fixture.requests.getFirst().maxOutputTokens());
        var logged = new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.events.getFirst().details().orElseThrow());
        assertEquals("65536", logged.path("requestedMaximumOutputTokens").asText());
        assertEquals("32768", logged.path("maximumOutputTokens").asText());
    }

    @Test
    void installerSourcesRemainCompleteAndStableBeforeDynamicOrderedWrappers() throws Exception {
        var fixture = new NativeAiFixture();
        var ai = new DeploymentAiJson(fixture.context);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int index = 0; index < 2; index++) {
            String input = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.renderTracked(
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.digest(
                    gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.render()));
            var complete = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.describe(input);
            fixture.nativeReply(new AiToolCall("review" + index, "submit_result", "{}"));
            ai.askCommand("Review every source and ordered wrapper", Map.of("completeExecutableInput", complete, "exactAction", "target" + index),
                "fixture-route", fixture.task, node -> false);
            String serialized = fixture.requests.getLast().messages().getLast().text();
            assertTrue(serialized.startsWith("{\"completeExecutableInput\":{\"sources\":"));
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.verify(json.readTree(serialized).path("completeExecutableInput"), input);
        }
        String first = fixture.requests.getFirst().messages().getLast().text();
        String second = fixture.requests.getLast().messages().getLast().text();
        int common = 0;
        while (common < Math.min(first.length(), second.length()) && first.charAt(common) == second.charAt(common))
            common++;
        assertTrue(common > 200000, "Complete immutable helper sources precede all original-operation identities");
        assertEquals(2, fixture.requests.size());
    }

    @Test
    void approvalPutsCompleteUnchangedExecutableBeforeChangingTargetMetadata() throws Exception {
        var fixture = new NativeAiFixture();
        var ai = new DeploymentAiJson(fixture.context);
        String executable = "printf 'unchanged complete installer'\n".repeat(4000);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String target : List.of("first", "second")) {
            var facts = Map.of("completeExecutableInput", executable, "exactAction", "exec /bin/bash -s",
                "businessRevision", target, "serverTargets", List.of(target), "authorizationPolicy", "MANUAL_REVIEW");
            fixture.nativeReply(new AiToolCall(target, "submit_result", "{}"));
            ai.askCommand("Review every executable byte", facts, "fixture-route", fixture.task, node -> false);
            String input = fixture.requests.getLast().messages().getLast().text();
            assertTrue(input.startsWith("{\"completeExecutableInput\":"));
            assertEquals(json.valueToTree(facts), json.readTree(input), "Every original field and byte remains in the same untrusted data message");
            assertEquals(2, fixture.requests.getLast().messages().size());
            assertEquals(8192, fixture.requests.getLast().maxOutputTokens().orElseThrow());
        }
        String first = fixture.requests.getFirst().messages().getLast().text();
        String second = fixture.requests.getLast().messages().getLast().text();
        int common = 0;
        while (common < Math.min(first.length(), second.length()) && first.charAt(common) == second.charAt(common))
            common++;
        assertTrue(common > executable.length(), "Changing the target cannot break the prefix before the complete immutable executable");
        assertEquals(2, fixture.requests.size(), "Ordering adds no model calls and reuses no approval");
    }

    @Test
    void extendedApprovalKeepsFullCommandEvidenceWithinItsOwnOutputCeiling() throws Exception {
        var fixture = new NativeAiFixture();
        var ai = new DeploymentAiJson(fixture.context);
        fixture.nativeReply(new AiToolCall("language", "submit_result", "{}"));
        ai.ask(AiPurpose.LANGUAGE, "Analyse", Map.of(), "fixture-route", fixture.task);
        String command = "x".repeat(60000);
        fixture.nativeReply(new AiToolCall("approval", "submit_result", "{}"));
        ai.askCommand("Review complete evidence", Map.of("command", command), "fixture-route", fixture.task, node -> false);
        var budgets = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai();
        assertEquals(budgets.approvalMaxOutputTokens(), fixture.requests.getLast().maxOutputTokens().orElseThrow());
        assertEquals(budgets.deploymentMaxOutputTokens(), fixture.requests.getFirst().maxOutputTokens().orElseThrow());
        assertTrue(fixture.requests.getLast().maxOutputTokens().orElseThrow() <= budgets.extendedDeploymentMaxOutputTokens());
        assertEquals(command, new com.fasterxml.jackson.databind.ObjectMapper().readTree(fixture.requests.getLast().messages().getLast().text()).path("command").asText());
        assertEquals(java.time.Duration.ofMinutes(10), fixture.requests.getLast().timeout());
    }

    @Test
    void structuredResultsUseNativeSubmissionAndGrantNoExecutionPermission() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.nativeReply(new AiToolCall("s1", "submit_result", "{\"decision\":\"DENY\",\"reason\":\"complete evidence reviewed\"}"));
        var answer = new DeploymentAiJson(fixture.context).ask(AiPurpose.APPROVAL, "Review full command", Map.of("exactAction", "printf safe"), "fixture-route", fixture.task);
        assertEquals("DENY", answer.path("decision").asText());
        assertFalse(fixture.requests.getFirst().jsonObjectResponse());
        assertEquals("submit_result", fixture.requests.getFirst().tools().getFirst().name());
    }

    @Test
    void invalidSubmissionGetsErrorForOriginalIdWithoutChangingProtocol() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.nativeReply(new AiToolCall("s1", "submit_result", "{\"x\":1,\"x\":2}"));
        fixture.nativeReply(new AiToolCall("s2", "submit_result", "{\"decision\":\"DENY\"}"));
        assertEquals("DENY", new DeploymentAiJson(fixture.context).ask(AiPurpose.APPROVAL, "Review", Map.of(), "fixture-route", fixture.task).path("decision").asText());
        var feedback = fixture.requests.getLast().messages().getLast().toolResult().orElseThrow();
        assertEquals("s1", feedback.callId());
        assertTrue(feedback.error());
        assertTrue(fixture.requests.getLast().messages().get(2).continuation().isPresent());
    }

    @Test
    void unknownIsPreservedAndNeverRetried() {
        var fixture = new NativeAiFixture();
        fixture.replies.add(new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "MODEL_UNKNOWN", "unknown", Map.of(), Optional.empty()))));
        var error = assertThrows(DeploymentAiJson.CapabilityOutcomeException.class, () -> new DeploymentAiJson(fixture.context)
            .ask(AiPurpose.LANGUAGE, "Analyse", Map.of(), "fixture-route", fixture.task));
        assertEquals(TaskResult.UNKNOWN, error.outcome());
        assertEquals(1, fixture.requests.size());
    }
}

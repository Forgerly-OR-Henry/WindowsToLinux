package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import org.junit.jupiter.api.Test;

class DeploymentAiOutputConfigTest {
    @Test
    void independentApprovalSelectsRecoveryAndKeepsClosedDenialFinal() throws Exception {
        var fixture = new NativeAiFixture();
        var binding = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding("fixture-host", "fixture-target", "a".repeat(64));
        var evidence = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence() {
            public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding() {
                return binding;
            }

            public Map<String, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources() {
                return Map.of();
            }

            public Object receipts() {
                return Map.of();
            }
        };
        var approval = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(fixture.context, fixture.task, evidence);
        fixture.nativeReply(new AiToolCall("denied", "submit_result", """
            {"decision":"DENY","requiresUserConfirmation":false,"reason":"Current input is refused","missingEvidence":[],"adjustments":[],"candidateCommands":[],"discussionStatus":"CLOSED"}
            """));
        try (var command = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest("printf fixture", "printf fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.time.Duration.ofSeconds(1), 1024)) {
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new gold.debug.windowstolinux.appapi.server.model.ServerId("fixture-host"), "a".repeat(64), command);
            var facts = Map.of("inputKind", "EXECUTABLE_SCRIPT", "completeExecutableInput", "printf fixture");
            var first = approval.review("Review current input", facts, intent, command, "fixture-route", fixture.task);
            var second = approval.review("Review current input", facts, intent, command, "fixture-route", fixture.task);
            assertEquals("DENY", first.decision().decision());
            assertEquals(first.decision(), second.decision());
        }
        assertEquals(1, fixture.requests.size(), "Closed DENY remains final despite selected transport recovery");
        assertEquals(1, fixture.requests.getFirst().generationRecoveryAttempts());
        assertEquals(AiPurpose.APPROVAL, fixture.requests.getFirst().purpose());
    }

    @Test
    void recoveryIsCallerSelectedForMainAndApprovalWhileOrdinaryAdaptersStayDisabled() throws Exception {
        var fixture = new NativeAiFixture();
        for (int extra : List.of(0, 1, 16)) {
            var selected = new DeploymentAiJson(fixture.context, extra);
            for (var purpose : List.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL)) {
                fixture.nativeReply(new AiToolCall("chosen", "submit_result", "{}"));
                selected.call(purpose, List.of(new AiMessage(AiRole.USER, "current", List.of())), List.of(new AiToolDefinition("submit_result", "complete", "{\"type\":\"object\"}")),
                    "fixture-route", fixture.task, false);
                assertEquals(extra, fixture.requests.getLast().generationRecoveryAttempts());
                if (purpose == AiPurpose.APPROVAL)
                    assertEquals(Optional.of("submit_result"), fixture.requests.getLast().requiredToolName());
            }
        }
        fixture.nativeReply(new AiToolCall("ordinary", "submit_result", "{}"));
        new DeploymentAiJson(fixture.context).call(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "ordinary", List.of())), List.of(), "fixture-route", fixture.task, false);
        assertEquals(0, fixture.requests.getLast().generationRecoveryAttempts());
        fixture.nativeReply(new AiToolCall("main", "submit_result", "{}"));
        new gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol(fixture.context, "fixture-route")
            .decide(Map.of(), new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory(), 1, fixture.task);
        assertEquals(1, fixture.requests.getLast().generationRecoveryAttempts());
        var request = new AiRequest(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "old SDK", List.of())), java.time.Duration.ofSeconds(1), false);
        assertEquals(0, request.generationRecoveryAttempts());
        assertThrows(IllegalArgumentException.class, () -> request.withGenerationRecovery(17));
    }

    @Test
    void analysisAndApprovalUseTheirOwnConfiguredOutputRequests() throws Exception {
        var fixture = new NativeAiFixture();
        var configured = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai();
        var ai = new DeploymentAiJson(fixture.context, new DeploymentConfig.Ai(4096, 12288, 2048,
            new DeploymentConfig.Requests(12, 9, 60), configured.execution(), configured.history()));
        for (var purpose : List.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL)) {
            fixture.nativeReply(new AiToolCall(purpose.name(), "submit_result", "{}"));
            var response = fixture.replies.removeLast().value().orElseThrow();
            fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("{}",
                response.modelId(), response.finishReason(), response.usage(), response.toolCalls(), response.continuation())), Optional.empty()));
            assertTrue(ai.ask(purpose, "Return the complete structured result", Map.of(), "fixture-route", fixture.task).isObject());
        }
        assertEquals(List.of(Optional.of(4096), Optional.of(2048)), fixture.requests.stream().map(AiRequest::maxOutputTokens).toList());
        assertEquals(2, fixture.requests.size());
        assertEquals(List.of(java.time.Duration.ofSeconds(12), java.time.Duration.ofSeconds(9)), fixture.requests.stream().map(AiRequest::timeout).toList());
        fixture.nativeReply(new AiToolCall("extended", "submit_result", "{}"));
        ai.call(AiPurpose.LANGUAGE, fixture.requests.getFirst().messages(), List.of(), "fixture-route", fixture.task, true);
        assertEquals(Optional.of(12288), fixture.requests.getLast().maxOutputTokens());
        assertEquals(java.time.Duration.ofSeconds(60), fixture.requests.getLast().timeout());
    }
}

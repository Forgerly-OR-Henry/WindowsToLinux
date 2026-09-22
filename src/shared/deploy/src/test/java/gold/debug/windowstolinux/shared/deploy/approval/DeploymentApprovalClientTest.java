package gold.debug.windowstolinux.shared.deploy.approval;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.model.agent.*;
import org.junit.jupiter.api.Test;

class DeploymentApprovalClientTest {
    private static final URI ENDPOINT = URI.create("https://example.invalid/chat/completions");

    private static final AgentAction ACTION = new AgentAction("one", "task", "server/user", "a".repeat(64), 1,
            AgentToolType.VERIFY_SERVER, Map.of(), Map.of("identity", "fixed"), AgentRiskLevel.NORMAL);
    static String envelope(String content) throws com.fasterxml.jackson.core.JsonProcessingException {
        return new ObjectMapper().writeValueAsString(Map.of("choices",
                List.of(Map.of("message", Map.of("content", content))), "usage", Map.of("total_tokens", 42)));
    }

    @Test
    void approvalHasIndependentSkillAndActualUsage() throws Exception {
        var captured = new ArrayList<String>();
        String response = envelope("{\"decision\":\"DENY\",\"risk\":\"HIGH\",\"binding\":\"" + ACTION.binding()
                + "\",\"reason\":\"outside scope\",\"evidence\":[\"identity\"]}");
        var client = new DeploymentApprovalClient(new StructuredAiClient((e, k, b) -> {
            captured.add(b);
            assertArrayEquals("secret".toCharArray(), k);
            return new RoleChatResult(200, response);
        }));
        var reply = client.review(ENDPOINT, "model", "secret".toCharArray(), "deploy", ACTION, AgentRiskLevel.NORMAL);
        assertEquals(AgentReviewDecision.DENY, reply.value().decision());
        assertEquals(42, reply.tokens().orElseThrow());
        assertTrue(captured.getFirst().contains("Independent Approval Skill v1"));
        assertFalse(captured.getFirst().contains("\"history\""));
        assertFalse(captured.getFirst().contains("secret"));
    }

    @Test
    void malformedOrUnboundApprovalsCannotPass() throws Exception {
        String good = "{\"decision\":\"ALLOW\",\"risk\":\"NORMAL\",\"binding\":\"" + ACTION.binding()
                + "\",\"reason\":\"checked\",\"evidence\":[\"identity\"]}";
        for (String bad : List.of(good.replace("\"ALLOW\"", "\"ALLOW\",\"decision\":\"DENY\""), good + " {}",
                good.replace("\"identity\"", "\"invented\""), good.replace(ACTION.binding(), "b".repeat(64)),
                good.replace("[\"identity\"]", "[]"), good.replace("\"reason\":\"checked\"", "\"reason\":false"))) {
            var body = envelope(bad);
            var client = new DeploymentApprovalClient(
                    new StructuredAiClient((e, k, b) -> new RoleChatResult(200, body)));
            assertThrows(Exception.class,
                    () -> client.review(ENDPOINT, "model", new char[]{'k'}, "deploy", ACTION, AgentRiskLevel.NORMAL));
        }
    }
}

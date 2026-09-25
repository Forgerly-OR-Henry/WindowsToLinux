package gold.debug.windowstolinux.shared.standard.deploy.assistance.execution.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.model.agent.*;
import org.junit.jupiter.api.Test;

class AssistedAiProtocolClientTest {
    private static final URI ENDPOINT = URI.create("https://example.invalid/chat/completions");

    private static final AgentAction ACTION = new AgentAction("one", "task", "server/user", "a".repeat(64), 1,
            AgentToolType.VERIFY_SERVER, Map.of(), Map.of("identity", "fixed"), AgentRiskLevel.NORMAL);
    static String envelope(String content) throws com.fasterxml.jackson.core.JsonProcessingException {
        return new ObjectMapper().writeValueAsString(Map.of("choices",
                List.of(Map.of("message", Map.of("content", content))), "usage", Map.of("total_tokens", 42)));
    }

    @Test
    void repeatedEvidenceAcrossManyToolsDoesNotExhaustTheRequestBudget() throws Exception {
        var actions = new ArrayList<AgentAction>();
        for (int i = 0; i < 16; i++)
            actions.add(new AgentAction("op" + i, "task", "target", "a".repeat(64), 1, AgentToolType.SERVICE_STATUS,
                    Map.of("service", "app" + i), Map.of("observed", "x".repeat(7000)), AgentRiskLevel.NORMAL));
        var client = new AssistedAiProtocolClient(new StructuredAiClient((endpoint, key, body) -> {
            var json = new ObjectMapper();
            var context = json.readTree(json.readTree(body).path("messages").get(1).path("content").asText());
            assertEquals(1, context.path("evidenceSets").size());
            assertEquals(16, context.path("actions").size());
            for (var action : context.path("actions"))
                assertEquals(7000, context.path("evidenceSets").path(action.path("evidenceReference").asText())
                        .path("observed").asText().length());
            return new RoleChatResult(200, envelope(json.writeValueAsString(Map.of("decision", "EXECUTE", "actionId",
                    actions.getFirst().id(), "binding", actions.getFirst().binding(), "reason", "observed"))));
        }));
        assertEquals(actions.getFirst().binding(),
                client.decide(ENDPOINT, "model", new char[]{'k'}, "deploy", actions, List.of(), 30).value().binding());
    }
}

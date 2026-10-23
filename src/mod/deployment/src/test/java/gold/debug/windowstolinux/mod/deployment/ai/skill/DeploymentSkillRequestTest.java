package gold.debug.windowstolinux.mod.deployment.ai.skill;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol;
import org.junit.jupiter.api.Test;
class DeploymentSkillRequestTest {
    @Test void fixedPolicyPrecedesDynamicEvidenceAndHistoryRetainsNativeState() throws Exception {
        var fixture=new NativeAiFixture(); var protocol=new DeploymentAgentProtocol(fixture.context,"fixture-route");
        var history=new DeploymentNativeHistory();
        fixture.nativeReply(new AiToolCall("r1","read","{\"path\":\"app.py\",\"offset\":0,\"limit\":20}"));
        var facts=Map.<String,Object>of("sourceRevision","a".repeat(64));
        var response=protocol.decide(facts,history,79,fixture.task);
        assertFalse(fixture.requests.getFirst().tools().isEmpty());
        assertFalse(fixture.requests.getFirst().jsonObjectResponse());
        assertEquals(AiRole.SYSTEM,fixture.requests.getFirst().messages().getFirst().role());
        history.retain("original source",response,List.of(new AiToolResult("r1","o1\nactual source",false)));
        fixture.nativeReply(new AiToolCall("r2","evidence_index","{\"offset\":0,\"limit\":50}"));
        protocol.decide(facts,history,78,fixture.task);
        assertEquals(fixture.requests.getFirst().messages().getFirst(),fixture.requests.getLast().messages().getFirst());
        assertTrue(fixture.requests.getLast().messages().stream().anyMatch(m->m.continuation().isPresent()));
        assertEquals("r1",fixture.requests.getLast().messages().stream().flatMap(m->m.toolResult().stream()).findFirst().orElseThrow().callId());
    }
}

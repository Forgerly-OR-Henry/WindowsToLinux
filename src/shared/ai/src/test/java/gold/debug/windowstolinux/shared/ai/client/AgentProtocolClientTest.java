package gold.debug.windowstolinux.shared.ai.client;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.model.agent.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentProtocolClientTest {
    private static final URI ENDPOINT=URI.create("https://example.invalid/chat/completions");
    private static final AgentAction ACTION=new AgentAction("one","task","server/user","a".repeat(64),1,AgentToolType.VERIFY_SERVER,Map.of(),Map.of("identity","fixed"),AgentRiskLevel.NORMAL);
    static String envelope(String content)throws com.fasterxml.jackson.core.JsonProcessingException{return new ObjectMapper().writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))),"usage",Map.of("total_tokens",42)));}
    @Test void approvalHasIndependentSkillAndActualUsage()throws Exception{
        var captured=new ArrayList<String>();String response=envelope("{\"decision\":\"DENY\",\"risk\":\"HIGH\",\"binding\":\""+ACTION.binding()+"\",\"reason\":\"outside scope\",\"evidence\":[\"identity\"]}");
        var client=new AgentProtocolClient((e,k,b)->{captured.add(b);assertArrayEquals("secret".toCharArray(),k);return new RoleChatResult(200,response);});
        var reply=client.review(ENDPOINT,"model","secret".toCharArray(),"deploy",ACTION,AgentRiskLevel.NORMAL);
        assertEquals(AgentReviewDecision.DENY,reply.value().decision());assertEquals(42,reply.tokens().orElseThrow());
        assertTrue(captured.getFirst().contains("Independent Approval Skill v1"));assertFalse(captured.getFirst().contains("\"history\""));assertFalse(captured.getFirst().contains("secret"));
    }
    @Test void malformedOrUnboundApprovalsCannotPass()throws Exception{
        String good="{\"decision\":\"ALLOW\",\"risk\":\"NORMAL\",\"binding\":\""+ACTION.binding()+"\",\"reason\":\"checked\",\"evidence\":[\"identity\"]}";
        for(String bad:List.of(good.replace("\"ALLOW\"","\"ALLOW\",\"decision\":\"DENY\""),good+" {}",good.replace("\"identity\"","\"invented\""),good.replace(ACTION.binding(),"b".repeat(64)),good.replace("[\"identity\"]","[]"),good.replace("\"reason\":\"checked\"","\"reason\":false"))){
            var body=envelope(bad);var client=new AgentProtocolClient((e,k,b)->new RoleChatResult(200,body));
            assertThrows(Exception.class,()->client.review(ENDPOINT,"model",new char[]{'k'},"deploy",ACTION,AgentRiskLevel.NORMAL));
        }
    }
    @Test void repeatedEvidenceAcrossManyToolsDoesNotExhaustTheRequestBudget()throws Exception{
        var actions=new ArrayList<AgentAction>();for(int i=0;i<16;i++)actions.add(new AgentAction("op"+i,"task","target","a".repeat(64),1,AgentToolType.SERVICE_STATUS,Map.of("service","app"+i),Map.of("observed","x".repeat(7000)),AgentRiskLevel.NORMAL));
        var client=new AgentProtocolClient((endpoint,key,body)->{
            var json=new ObjectMapper();var context=json.readTree(json.readTree(body).path("messages").get(1).path("content").asText());
            assertEquals(1,context.path("evidenceSets").size());assertEquals(16,context.path("actions").size());
            for(var action:context.path("actions"))assertEquals(7000,context.path("evidenceSets").path(action.path("evidenceReference").asText()).path("observed").asText().length());
            return new RoleChatResult(200,envelope(json.writeValueAsString(Map.of("decision","EXECUTE","actionId",actions.getFirst().id(),"binding",actions.getFirst().binding(),"reason","observed"))));
        });
        assertEquals(actions.getFirst().binding(),client.decide(ENDPOINT,"model",new char[]{'k'},"deploy",actions,List.of(),30).value().binding());
    }
}

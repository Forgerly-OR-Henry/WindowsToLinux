package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentMirroredInstallerResponsesBudgetTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int ONE_MIB=1048576;

    @ParameterizedTest
    @ValueSource(strings={"PLAIN","FILES","TRACKED"})
    void fullResponsesSystemNavigationSchemaHistoryAndContinuationFitAndOuterGatesStillReject(String kind) throws Exception {
        verifyFullResponses(kind);
    }

    @Test
    void customResponsesCompleteWorkerNavigationHistoryAndContinuationFitAndOuterGatesStillReject() throws Exception {
        verifyFullResponses("CUSTOM");
    }

    private void verifyFullResponses(String kind) throws Exception {
        try(var fixture=new MirroredInstallerTestFixture();var command=fixture.request(fixture.installer(kind,true))) {
            var facts=fixture.facts(kind,command);
            String inner=DeploymentAiJson.facts(facts,ONE_MIB);
            System.out.println("MIRRORED_RESPONSES_INNER kind="+kind+" factsCharacters="+inner.length()+" ceiling="+ONE_MIB);
            var conversation=fixture.conversation();
            String historyArguments="{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"A historical verdict is not a grant\"}";
            var historyCall=new AiToolCall("past-review-call","submit_result",historyArguments);
            var stateArray=JSON.createArrayNode();
            stateArray.addObject().put("type","function_call").put("call_id",historyCall.id()).put("name",historyCall.name()).put("arguments",historyArguments);
            String state=stateArray.toString();
            var continuation=new AiContinuation(UUID.randomUUID(),bytes(state));
            var historyResult=new AiToolResult(historyCall.id(),"{\"accepted\":true,\"decision\":\"DENY\",\"permission\":false}",false);
            var historicalResponse=new AiResponse("","history-fixture","tool_calls",
                new AiUsage(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()),List.of(historyCall),Optional.of(continuation));
            conversation.retain("{\"historicalReview\":true}",historicalResponse,historyResult,fixture.model.configurationRevision,fixture.task);
            fixture.model.nativeReply(new AiToolCall("current-denial","submit_result","{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled independent denial\"}"));
            var decision=new DeploymentAiJson(fixture.context,conversation).askCommand(
                "Review all executable sources and installation effects",facts,fixture.model.configurationRevision,fixture.task,DeploymentReviewDecision::malformed);
            assertEquals("DENY",decision.path("decision").asText());
            assertEquals(1,fixture.model.requests.size());
            AiRequest captured=fixture.model.requests.getFirst();
            assertEquals(ONE_MIB,captured.maxRequestBytes());
            assertEquals(AiRole.SYSTEM,captured.messages().getFirst().role());
            var projection=JSON.readTree(captured.messages().getLast().text());
            var current=MirroredInstallerTestFixture.restoreCarrier(captured.messages());
            var carrier=projection.path("approvalSourceCarrier");
            assertEquals("WTL_APPROVAL_SOURCE_CARRIER_V1",carrier.path("format").asText());
            assertEquals(kind.equals("CUSTOM")?10:7,carrier.path("frames").size());
            assertTrue(carrier.path("currentCharacters").intValue()<=ONE_MIB,"The complete current including regenerated navigation passed the original facts gate before framing");
            assertTrue(current.path("helperInstallationSourceEvidence").has("requiredExecutableEvidencePointers"));
            assertTrue(current.at("/helperInstallationSourceEvidence/sourceCounts").has("mirrorRuntime"));
            assertEquals("/completeExecutableInput/sources/mirrorRuntime",current.at("/mirrorTransport/runtimeSourcePointer").asText());
            var schema=JSON.readTree(captured.tools().getFirst().inputSchema());
            assertEquals(Set.of("decision","requiresUserConfirmation","reason"),JSON.convertValue(schema.path("required"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){}));

            var messages=new ArrayList<>(captured.messages());
            var states=Map.of(continuation.id(),state);
            var complete=request(captured,messages);
            int aggregate=aggregate(complete,states);
            long rawSourceBytes=0;
            for(var row:carrier.path("frames"))rawSourceBytes+=row.path("utf8Bytes").longValue();
            System.out.println("MIRRORED_RESPONSES_PREENCODE kind="+kind+" fullFactsCharacters="+carrier.path("currentCharacters").intValue()
                +" rawUserSourceBytes="+rawSourceBytes+" manifestBytes="+bytes(captured.messages().getLast().text())
                +" systemBytes="+bytes(captured.messages().getFirst().text())+" navigationBytes="+JSON.writeValueAsBytes(current.path("helperInstallationSourceEvidence")).length
                +" continuationBytes="+bytes(state)+" aggregateBytes="+aggregate+" encodedBytes=UNMEASURED ceiling="+ONE_MIB);
            // This factory selects ResponsesCodec, whose final encode is NativeModelCodec.encode.
            var codec=ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES);
            byte[] encoded;
            try {encoded=codec.encode("gpt-6-luna",complete,65536,ModelReasoningLevel.HIGH,states);}
            catch(ModelRequestBudgetException refused) {
                System.out.println("MIRRORED_RESPONSES_ENCODED_REFUSAL kind="+kind+" actualEncodedBytes="+refused.encodedBytes()+" ceiling="+ONE_MIB);
                throw refused;
            }
            assertTrue(encoded.length<=ONE_MIB);
            var wire=JSON.readTree(encoded);
            assertTrue(wire.has("input"));
            assertFalse(wire.has("messages"),"ChatCompletions encoding cannot stand in for Responses");
            assertEquals("system",wire.path("input").get(0).path("role").asText());
            assertTrue(wire.path("input").toString().contains("function_call_output"));
            assertEquals("high",wire.at("/reasoning/effort").asText());
            assertEquals("auto",wire.path("tool_choice").asText());
            var wireInput=wire.path("input");
            var wireProjection=JSON.readTree(wireInput.get(wireInput.size()-1).path("content").get(0).path("text").textValue());
            var frameTexts=new ArrayList<String>();
            int frameCount=wireProjection.at("/approvalSourceCarrier/frames").size();
            for(int index=wireInput.size()-frameCount-1;index<wireInput.size()-1;index++) {
                assertEquals("user",wireInput.get(index).path("role").asText());
                frameTexts.add(wireInput.get(index).path("content").get(0).path("text").textValue());
            }
            var wireRestored=MirroredInstallerTestFixture.restoreCarrier(wireProjection,frameTexts);
            assertEquals(current,wireRestored,"Decode the actual Responses body back to every complete current fact");
            assertArrayEquals(command.stdin(),gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.reconstruct(wireRestored.path("completeExecutableInput")).getBytes(StandardCharsets.UTF_8));
            for(var source:current.path("completeExecutableInput").path("sources"))
                assertFalse(captured.messages().getFirst().text().contains(source.textValue()),"Complete source bodies stay USER data");
            System.out.println("MIRRORED_RESPONSES_SIZE kind="+kind+" fullFactsCharacters="+carrier.path("currentCharacters").intValue()
                +" rawUserSourceBytes="+rawSourceBytes+" aggregateBytes="+aggregate+" actualEncodedBytes="+encoded.length+" ceiling="+ONE_MIB);

            int headroom=ONE_MIB-aggregate;
            assertTrue(headroom>128,"Keep observable room for real outer-message/schema/encoding overhead");
            var aggregateOversize=new ArrayList<>(messages);
            aggregateOversize.add(new AiMessage(AiRole.USER,"x".repeat(headroom+1),List.of()));
            assertThrows(IllegalArgumentException.class,()->codec.encode("gpt-6-luna",request(captured,aggregateOversize),65536,ModelReasoningLevel.HIGH,states));

            var encodedOversize=new ArrayList<>(messages);
            encodedOversize.add(new AiMessage(AiRole.USER,"\\\"".repeat((headroom-32)/2),List.of()));
            var escaped=request(captured,encodedOversize);
            assertTrue(aggregate(escaped,states)<ONE_MIB,"Inner and aggregate text can pass while actual JSON bytes do not");
            var rejection=assertThrows(ModelRequestBudgetException.class,()->codec.encode("gpt-6-luna",escaped,65536,ModelReasoningLevel.HIGH,states));
            assertTrue(rejection.encodedBytes()>ONE_MIB);
            assertEquals(1,fixture.model.requests.size(),"Outer codec refusals perform no provider call");
        }
    }

    private static AiRequest request(AiRequest original,List<AiMessage> messages) {
        return new AiRequest(original.purpose(),messages,original.maxOutputTokens(),original.timeout(),original.jsonObjectResponse(),original.tools(),ONE_MIB,
            original.requiredToolName(),original.generationRecoveryAttempts(),Optional.of("gpt-6-luna"),Optional.of(AiReasoningMode.HIGH));
    }

    private static int aggregate(AiRequest request,Map<UUID,String> states) {
        int size=0;
        for(var message:request.messages()) {
            size+=bytes(message.text());
            for(var call:message.toolCalls())size+=bytes(call.arguments());
            if(message.toolResult().isPresent())size+=bytes(message.toolResult().orElseThrow().text());
            if(message.continuation().isPresent())size+=bytes(states.get(message.continuation().orElseThrow().id()));
        }
        for(var tool:request.tools())size+=bytes(tool.description())+bytes(tool.inputSchema());
        return size;
    }

    private static int bytes(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
}

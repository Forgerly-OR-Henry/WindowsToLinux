package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.mod.deployment.ai.review.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeploymentMirroredInstallerCoverageTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test
    void omittedRuntimeLeafCannotCompleteAndCompleteEvidenceDoesNotOverrideIndependentDeny() throws Exception {
        try(var fixture=new MirroredInstallerTestFixture();var request=fixture.request(fixture.installer("TRACKED",true))) {
            var facts=JSON.valueToTree(fixture.facts("TRACKED",request));
            var coverage=new DeploymentAgentInputCoverage(request,facts);
            deliver(coverage,"request","script",request.script());
            var evidence=facts.path("completeExecutableInput");
            String bootstrap = "/sources/mirrorBootstrapShell";
            for(String pointer:DeploymentInstallerEvidence.readableBodies(evidence)) {
                if(pointer.equals("/sources/mirrorRuntime") || pointer.equals(bootstrap))continue;
                deliver(coverage,"evidence","/completeExecutableInput"+pointer,evidence.at(pointer).textValue());
            }
            assertFalse(coverage.complete(),"All old two-source leaves do not cover the newly factored executable runtime");
            assertTrue(JSON.valueToTree(coverage.missing()).path("equivalentEvidencePointers").toString().contains("/sources/mirrorRuntime"));
            deliver(coverage,"evidence",DeploymentInstallerEvidence.MIRROR_SOURCE_POINTER,evidence.at("/sources/mirrorRuntime").textValue());
            assertFalse(coverage.complete(), "The shared bootstrap source is still required exactly once");
            assertTrue(JSON.valueToTree(coverage.missing()).path("equivalentEvidencePointers").toString().contains(bootstrap));
            deliver(coverage,"evidence","/completeExecutableInput"+bootstrap,evidence.at(bootstrap).textValue());
            assertTrue(coverage.complete());
            var hostCalls=new AtomicInteger();
            var host=(InteractionService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{InteractionService.class},(p,m,a)->{hostCalls.incrementAndGet();throw new AssertionError("DENY must not reach host grant");});
            fixture.model.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("denied-complete-mirror","submit_result","{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Complete bytes still require an independent safety verdict\"}"));
            var refusal=assertThrows(ApiException.class,()->DeploymentFixedCommandApproval.approveWithEvidence(fixture.context,host,fixture.intent("TRACKED",request),request,fixture.task,fixture.model.configurationRevision,"AUTOMATIC",false,DeploymentCurrentCommandEvidence.NONE));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED",refusal.problem().eventCode());
            assertEquals(1,fixture.model.requests.size());
            assertEquals(0,hostCalls.get());
        }
    }

    private static void deliver(DeploymentAgentInputCoverage coverage,String tool,String route,String text) {
        // Each page is confirmed as actually delivered before the next one is considered.
        for(int start=0;start<text.length();) {
            int end=Math.min(text.length(),start+4000);
            var args=JSON.valueToTree(Map.of(tool.equals("request")?"part":"pointer",route));
            var page=Map.of("offset",start,"next",end,"total",text.length(),"sha256",DeploymentAgentInputs.hash(text),"text",text.substring(start,end));
            coverage.returned(tool,args,page,"delivered-current-page");
            coverage.delivered(JSON.createObjectNode(),"delivered-current-page"::equals);
            start=end;
        }
    }
}

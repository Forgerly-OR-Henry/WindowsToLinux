package gold.debug.windowstolinux.app.service.ai;
import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.contract.*;
import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.deployment.automatic.AutomaticDeploymentTaskService;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.shared.ai.client.*;
import gold.debug.windowstolinux.shared.model.ai.*;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AutomaticTaskStartGuardTest {
    @TempDir Path root;
    final ServerProfile server=new ServerProfile("server","example.invalid",22,"deploy","ref",CredentialStorageMode.MASTER_PASSWORD);
    AiUseCaseFacade ai(DesktopPersistence db){return new AiUseCaseFacade(db.aiProfiles(),new DesktopSecretStoreService(db.encryptedSecrets()),new OpenAiCompatibleRoleClient(),new AgentProtocolClient((url,key,body)->{throw new AssertionError("no AI before preflight");}));}
    AutomaticDeploymentTaskService task(DesktopPersistence db){
        var facade=(AutomaticDeploymentApplicationFacade)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AutomaticDeploymentApplicationFacade.class},(p,m,a)->{throw new AssertionError("preflight must precede source, SSH, upload and environment: "+m);});
        return new AutomaticDeploymentTaskService(facade,ai(db),null,new ServerOperationLockRegistry(),db.agentTasks(),null);
    }
    @Test void allPoliciesBlockEmptyAndDisabledApproversBeforeAnySideEffect()throws Exception{
        try(var db=DesktopPersistence.open(root)){
            var p=new AiProviderProfile("model",URI.create("https://example.invalid/v1/chat/completions"),"test","ai/test",CredentialStorageMode.MASTER_PASSWORD);
            db.aiProfiles().saveVerified(p.stored(),"model",Instant.now());db.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,List.of(new AiPurposeAssignment("model",true)));
            for(var policy:AgentApprovalMode.values())for(boolean disabled:List.of(false,true)){
                db.aiProfiles().purposes().save(AiPurposeType.APPROVAL,disabled?List.of(new AiPurposeAssignment("model",false)):List.of());
                var request=new AutomaticDeploymentRequest(Optional.of(root.resolve("source-not-read")),Optional.empty(),server,Map.of()).withAutomation(DeploymentAutomationMode.AGENT,policy);
                char[] secret="temporary".toCharArray();assertThrows(ApplicationServiceException.class,()->task(db).deploy(request,secret,null,value->{throw new AssertionError();},m->{}));
                assertEquals(0,new String(secret).replace("\0","").length());assertTrue(db.agentTasks().recent().isEmpty());assertTrue(DeploymentAiScope.current().isEmpty());
            }
        }
    }
    @Test void newStaticTaskCannotBypassUnknownAgentEffects()throws Exception{
        try(var db=DesktopPersistence.open(root)){
            db.agentTasks().create("previous","server","a".repeat(64),DeploymentAutomationMode.AGENT,AgentApprovalMode.AUTOMATIC,"b".repeat(64),"deployment-v1","approval-v1");
            db.agentTasks().event("previous","EXECUTING","old-operation","c".repeat(64));db.agentTasks().state("previous",AgentTaskState.UNKNOWN);
            var request=new AutomaticDeploymentRequest(Optional.of(root.resolve("unread")),Optional.empty(),server,Map.of());
            var error=assertThrows(IllegalStateException.class,()->task(db).deploy(request,"temporary".toCharArray(),null,value->{throw new AssertionError();},m->{}));
            assertEquals("deployment.agent.unresolvedOutcome",error.getMessage());assertEquals(List.of("previous"),db.agentTasks().unresolved("server"));
        }
    }
}

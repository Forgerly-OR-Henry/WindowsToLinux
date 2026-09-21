package gold.debug.windowstolinux.app.service.deployment.automatic;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.deploy.agent.*;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Timeout(20)
class AutomaticAgentBoundaryTest {
    @TempDir Path root;
    final ServerProfile server=new ServerProfile("server","example.invalid",22,"deploy","ref",CredentialStorageMode.MASTER_PASSWORD);
    final AutomaticDeploymentInteraction human=new AutomaticDeploymentInteraction(){
        public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> fields){throw new AssertionError();}
        public boolean confirm(String key,Map<String,?> details){throw new AssertionError("full control must not ask human for permitted operations");}
        public char[] requestSecret(String key){throw new AssertionError();}
    };
    @Test void actualDiagnosticFeedbackChangesTheNextActionAndReviewBinding()throws Exception{
        Files.writeString(root.resolve("app.txt"),"source");var selected=new ArrayList<AgentToolType>();var reviews=new ArrayList<AgentAction>();
        var models=new AgentModelPort(){
            public AgentDecision decide(String goal,List<AgentAction> actions,List<String> history,int remaining){
                var chosen=actions.stream().filter(a->selected.isEmpty()?a.tool()==AgentToolType.SERVICE_STATUS:a.tool()==AgentToolType.DEPLOY_TRANSACTION).findFirst().orElseThrow();
                if(!selected.isEmpty()){assertTrue(history.getLast().contains("STOPPED"));assertEquals("STOPPED",chosen.evidence().get("observed/state"));}
                selected.add(chosen.tool());return new AgentDecision(AgentDecisionType.EXECUTE,chosen.id(),chosen.binding(),"actual state informs next step");
            }
            public AgentReview review(String goal,AgentAction action,AgentRiskLevel local){reviews.add(action);return new AgentReview(AgentReviewDecision.ALLOW,local,action.binding(),"owned exact action",List.copyOf(action.evidence().keySet()));}
            public boolean advanceDeployment(){throw new AssertionError();}
        };
        var boundary=new AutomaticAgentBoundary("task",server,AgentApprovalMode.FULL_CONTROL,models,new AgentTaskControl(state->{}),human,event->{},()->true);
        boundary.freeze(root);boundary.register("status",AgentToolType.SERVICE_STATUS,Map.of("application","demo"),()->true,()->new AgentObservation(true,true,Map.of("state","STOPPED")));
        assertEquals("done",boundary.execute(AgentToolType.DEPLOY_TRANSACTION,Map.of("application","demo"),()->"done"));
        assertEquals(List.of(AgentToolType.SERVICE_STATUS,AgentToolType.DEPLOY_TRANSACTION),selected);assertEquals(2,reviews.size());
        assertEquals("STOPPED",reviews.getLast().evidence().get("observed/state"));
    }
    @Test void sourceMutationDuringApprovalInvalidatesTheWrite()throws Exception{
        Path file=root.resolve("app.txt");Files.writeString(file,"original");var ref=new AtomicReference<AgentTaskControl>();
        var control=new AgentTaskControl(state->{if(state==AgentTaskState.PAUSED)ref.get().cancel();});ref.set(control);
        var models=new AgentModelPort(){
            public AgentDecision decide(String g,List<AgentAction> actions,List<String> history,int remaining){var a=actions.getFirst();return new AgentDecision(AgentDecisionType.EXECUTE,a.id(),a.binding(),"deploy");}
            public AgentReview review(String g,AgentAction a,AgentRiskLevel risk)throws Exception{Files.writeString(file,"changed");return new AgentReview(AgentReviewDecision.ALLOW,risk,a.binding(),"reviewed old source",List.copyOf(a.evidence().keySet()));}
            public boolean advanceDeployment(){throw new AssertionError();}
        };
        var boundary=new AutomaticAgentBoundary("task",server,AgentApprovalMode.FULL_CONTROL,models,control,human,event->{},()->true);boundary.freeze(root);
        assertThrows(CancellationException.class,()->boundary.execute(AgentToolType.DEPLOY_TRANSACTION,Map.of("application","demo"),()->{throw new AssertionError("changed source executed");}));
    }
}

package gold.debug.windowstolinux.shared.deploy.agent;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentSessionTest {
    static AgentAction action(AgentRiskLevel risk){return new AgentAction("op","task","server/account","a".repeat(64),1,AgentToolType.DEPLOY_TRANSACTION,
        Map.of("release","a".repeat(64)),Map.of("source","verified","plan","validated"),risk);}
    static AgentReview review(AgentAction a,AgentReviewDecision d,AgentRiskLevel risk){return new AgentReview(d,risk,a.binding(),"checked",List.of("source","plan"));}
    static AgentModelPort model(AgentReviewDecision d,AgentRiskLevel risk,AtomicInteger reviews){return new AgentModelPort(){
        public AgentDecision decide(String g,List<AgentAction> as,List<String> h,int left){var a=as.getFirst();return new AgentDecision(AgentDecisionType.EXECUTE,a.id(),a.binding(),"execute");}
        public AgentReview review(String g,AgentAction a,AgentRiskLevel local){reviews.incrementAndGet();return DeploymentAgentSessionTest.review(a,d,risk);}
        public boolean advanceDeployment(){fail("valid review must not rotate");return false;}
    };}
    static AgentTaskControl cancelOnPause(){var ref=new AtomicReference<AgentTaskControl>();var c=new AgentTaskControl(s->{if(s==AgentTaskState.PAUSED)ref.get().cancel();});ref.set(c);return c;}
    @Test void allPoliciesRespectIndependentReviewAndHumanMatrix()throws Exception{
        for(var mode:AgentApprovalMode.values())for(var risk:List.of(AgentRiskLevel.NORMAL,AgentRiskLevel.HIGH)){
            var a=action(risk);var human=new AtomicInteger();var executed=new AtomicInteger();var reviews=new AtomicInteger();var events=new ArrayList<Map<String,String>>();
            var session=new DeploymentAgentSession("task","server/account","deploy",mode,model(AgentReviewDecision.ALLOW,AgentRiskLevel.NORMAL,reviews),
                cancelOnPause(),(x,r)->{human.incrementAndGet();return true;},events::add);
            var observed=session.run(List.of(a),"op",new AgentExecutionPort(){
                public AgentRiskLevel validate(AgentAction x){return risk;}
                public AgentObservation execute(AgentAction x){executed.incrementAndGet();assertEquals("EXECUTING",events.getLast().get("type"));return new AgentObservation(true,true,Map.of("health","passed"));}
            });
            assertTrue(observed.succeeded());assertEquals(1,reviews.get());assertEquals(1,executed.get());
            assertEquals(mode==AgentApprovalMode.MANUAL_REVIEW||mode==AgentApprovalMode.AUTOMATIC&&risk==AgentRiskLevel.HIGH?1:0,human.get());
            assertThrows(CancellationException.class,()->session.run(List.of(a),"op",new AgentExecutionPort(){
                public AgentRiskLevel validate(AgentAction x){fail("must not replay");return risk;}
                public AgentObservation execute(AgentAction x){throw new AssertionError();}
            }));
        }
    }
    @Test void localReviewAndHumanDenialsNeverDispatch()throws Exception{
        for(String rejection:List.of("local","review","evidence","human","binding")){
            var a=action(AgentRiskLevel.NORMAL);var reviews=new AtomicInteger();var chosen=model(rejection.equals("review")?AgentReviewDecision.DENY:rejection.equals("evidence")?AgentReviewDecision.NEEDS_EVIDENCE:AgentReviewDecision.ALLOW,AgentRiskLevel.NORMAL,reviews);
            AgentModelPort models=rejection.equals("binding")?new AgentModelPort(){
                public AgentDecision decide(String g,List<AgentAction> as,List<String> h,int left)throws Exception{return chosen.decide(g,as,h,left);}
                public AgentReview review(String g,AgentAction x,AgentRiskLevel r){return new AgentReview(AgentReviewDecision.ALLOW,r,"b".repeat(64),"wrong target",List.of("source"));}
                public boolean advanceDeployment(){throw new AssertionError();}
            }:chosen;
            var session=new DeploymentAgentSession("task","server/account","deploy",AgentApprovalMode.MANUAL_REVIEW,models,cancelOnPause(),(x,r)->!rejection.equals("human"),e->{});
            assertThrows(CancellationException.class,()->session.run(List.of(a),"op",new AgentExecutionPort(){
                public AgentRiskLevel validate(AgentAction x){return rejection.equals("local")?AgentRiskLevel.FORBIDDEN:AgentRiskLevel.NORMAL;}
                public AgentObservation execute(AgentAction x){throw new AssertionError("denied operation executed");}
            }));
            if(rejection.equals("local"))assertEquals(0,reviews.get());
        }
    }
    @Test void changedPreconditionsInvalidateApproval(){
        var c=cancelOnPause();var checks=new AtomicInteger();
        var session=new DeploymentAgentSession("task","server/account","deploy",AgentApprovalMode.FULL_CONTROL,model(AgentReviewDecision.ALLOW,AgentRiskLevel.NORMAL,new AtomicInteger()),c,(a,r)->true,e->{});
        assertThrows(CancellationException.class,()->session.run(List.of(action(AgentRiskLevel.NORMAL)),"op",new AgentExecutionPort(){
            public AgentRiskLevel validate(AgentAction a){return checks.incrementAndGet()==1?AgentRiskLevel.NORMAL:AgentRiskLevel.HIGH;}
            public AgentObservation execute(AgentAction a){throw new AssertionError();}
        }));
        assertEquals(2,checks.get());
    }
    @Test void unknownResultPreventsResumeAndReexecution(){
        var c=cancelOnPause();var calls=new AtomicInteger();var session=new DeploymentAgentSession("task","server/account","deploy",AgentApprovalMode.FULL_CONTROL,
            model(AgentReviewDecision.ALLOW,AgentRiskLevel.NORMAL,new AtomicInteger()),c,(a,r)->true,e->{});
        var executor=new AgentExecutionPort(){
            public AgentRiskLevel validate(AgentAction a){return AgentRiskLevel.NORMAL;}
            public AgentObservation execute(AgentAction a){calls.incrementAndGet();return new AgentObservation(false,false,Map.of());}
        };
        assertThrows(IllegalStateException.class,()->session.run(List.of(action(AgentRiskLevel.NORMAL)),"op",executor));
        assertEquals(AgentTaskState.UNKNOWN,c.state());c.resume();assertEquals(AgentTaskState.UNKNOWN,c.state());
        assertThrows(IllegalStateException.class,()->session.run(List.of(action(AgentRiskLevel.NORMAL)),"op",executor));assertEquals(1,calls.get());
    }
    @Test void durableIntentFailurePreventsDispatch(){
        var session=new DeploymentAgentSession("task","server/account","deploy",AgentApprovalMode.FULL_CONTROL,model(AgentReviewDecision.ALLOW,AgentRiskLevel.NORMAL,new AtomicInteger()),
            cancelOnPause(),(a,r)->true,e->{if(e.get("type").equals("EXECUTING"))throw new IllegalStateException("db full");});
        assertThrows(IllegalStateException.class,()->session.run(List.of(action(AgentRiskLevel.NORMAL)),"op",new AgentExecutionPort(){
            public AgentRiskLevel validate(AgentAction a){return AgentRiskLevel.NORMAL;}
            public AgentObservation execute(AgentAction a){throw new AssertionError();}
        }));
    }
}

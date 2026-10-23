package gold.debug.windowstolinux.mod.deployment.ai.agent;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import org.junit.jupiter.api.Test;
class DeploymentToolSchedulerTest {
    private AiToolCall call(String id,String name) { return new AiToolCall(id,name,"{}"); }
    @Test void independentReadsOverlapAndExclusiveActionWaitsForOrderedCommits() throws Exception {
        var rendezvous=new CountDownLatch(2); var active=new AtomicInteger(); var peak=new AtomicInteger();
        var commits=new ArrayList<String>(); var coordination=Thread.currentThread();
        var calls=List.of(call("a","read"),call("b","read"),call("c","patch"),call("d","read"));
        var results=DeploymentToolScheduler.execute(calls,new DeploymentToolScheduler.Adapter() {
            public DeploymentToolScheduler.Execution execution(AiToolCall c) { return c.name().equals("read")?DeploymentToolScheduler.Execution.LOCAL_READ:DeploymentToolScheduler.Execution.EXCLUSIVE; }
            public void checkpoint() { }
            public boolean stopped() { return false; }
            public DeploymentToolScheduler.Prepared prepare(AiToolCall c) {
                assertSame(coordination,Thread.currentThread());
                return new DeploymentToolScheduler.Prepared() {
                    public Object run() throws Exception {
                        if(c.name().equals("patch")) { assertSame(coordination,Thread.currentThread()); assertEquals(0,active.get()); assertEquals(List.of("a","b"),commits); return "exclusive"; }
                        int current=active.incrementAndGet(); peak.accumulateAndGet(current,Math::max);
                        try { if(!c.id().equals("d")) { rendezvous.countDown(); assertTrue(rendezvous.await(3,TimeUnit.SECONDS)); } return c.id(); }
                        finally { active.decrementAndGet(); }
                    }
                    public AiToolResult commit(AiToolCall c,Object value) { assertSame(coordination,Thread.currentThread()); commits.add(c.id()); return new AiToolResult(c.id(),value.toString(),false); }
                };
            }
        });
        assertEquals(2,peak.get()); assertEquals(List.of("a","b","c","d"),commits); assertEquals(commits,results.stream().map(AiToolResult::callId).toList());
    }
    @Test void cancellationAfterStartDrainsWorkersAndNeverDispatchesNextEffect() {
        var closed=new AtomicInteger(); var checkpoints=new AtomicInteger(); var effect=new AtomicBoolean();
        assertThrows(CancellationException.class,()->DeploymentToolScheduler.execute(List.of(call("a","read"),call("b","patch")),new DeploymentToolScheduler.Adapter() {
            public DeploymentToolScheduler.Execution execution(AiToolCall c) { return c.name().equals("read")?DeploymentToolScheduler.Execution.LOCAL_READ:DeploymentToolScheduler.Execution.EXCLUSIVE; }
            public void checkpoint() { if(checkpoints.incrementAndGet()==3) { assertEquals(1,closed.get()); throw new CancellationException(); } }
            public boolean stopped() { return false; }
            public DeploymentToolScheduler.Prepared prepare(AiToolCall c) {
                return new DeploymentToolScheduler.Prepared() {
                    public Object run() { if(c.name().equals("patch")) effect.set(true); closed.incrementAndGet(); return "done"; }
                    public AiToolResult commit(AiToolCall c,Object v) { return new AiToolResult(c.id(),"done",false); }
                };
            }
        }));
        assertEquals(1,closed.get()); assertFalse(effect.get());
    }
    @Test void oversizedBatchIsRejectedEntirelyBeforeAnyPreparation() throws Exception {
        var calls=java.util.stream.IntStream.range(0,5).mapToObj(i->call("call_"+i,"read")).toList();
        var results=DeploymentToolScheduler.execute(calls,new DeploymentToolScheduler.Adapter() {
            public DeploymentToolScheduler.Execution execution(AiToolCall c) { throw new AssertionError(); }
            public void checkpoint() { throw new AssertionError(); }
            public boolean stopped() { throw new AssertionError(); }
            public DeploymentToolScheduler.Prepared prepare(AiToolCall c) { throw new AssertionError(); }
        }); assertEquals(5,results.size()); assertTrue(results.stream().allMatch(AiToolResult::error));
    }
}

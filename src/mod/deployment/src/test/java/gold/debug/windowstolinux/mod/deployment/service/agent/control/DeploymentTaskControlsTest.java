package gold.debug.windowstolinux.mod.deployment.service.agent.control;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.agent.control.DeploymentAgentControl;
import gold.debug.windowstolinux.mod.deployment.service.agent.control.DeploymentTaskController;
import org.junit.jupiter.api.Test;

class DeploymentTaskControlsTest extends DeploymentRuntimeFixture {
    @Test void requestedPauseWaitsForSettledStageAndContinuesOriginalWorker() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime,true);
            var context=runtime.modContexts().get(OWNER).context();
            var controls=new DeploymentTaskControls(context);
            var entered=new CountDownLatch(1); var settled=new CountDownLatch(1);
            var continued=new AtomicInteger();
            var adapter=new DeploymentTaskController(context,controls::state,controls::request,controls::waiting);
            var task=context.tasks().submit(new TaskRequest("standard-boundary-fixture","safe boundary",List.of(),true), original -> {
                try(var boundary=controls.begin(original)) {
                    original.progress("Preparing real stage",.25); entered.countDown();
                    settled.await(8,TimeUnit.SECONDS);
                    controls.checkpoint(original);
                    continued.incrementAndGet();
                    original.progress("Finished stage",1);
                    return new OperationResult<Void>(TaskResult.SUCCEEDED,Optional.empty(),Optional.empty());
                }
            });
            try {
                assertTrue(entered.await(8,TimeUnit.SECONDS));
                controls.waiting(task.id(),true);
                assertEquals(TaskControlSnapshot.State.WAITING_USER,adapter.snapshot(task.id()).state());
                assertThrows(ApiException.class,()->adapter.request(task.id(),TaskCommand.RESUME));
                controls.waiting(task.id(),false);
                adapter.request(task.id(),TaskCommand.PAUSE);
                assertEquals(TaskControlSnapshot.State.PAUSE_REQUESTED,adapter.snapshot(task.id()).state());
                assertEquals(0,continued.get()); settled.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(controls.state(task.id()).orElse(null)!=DeploymentAgentControl.State.PAUSED && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(TaskControlSnapshot.State.PAUSED,adapter.snapshot(task.id()).state());
                assertEquals("Preparing real stage",task.snapshot().progressText());
                assertEquals(0,continued.get());
                adapter.request(task.id(),TaskCommand.RESUME);
                assertEquals(TaskResult.SUCCEEDED,task.completion().toCompletableFuture().get(8,TimeUnit.SECONDS).outcome());
                assertEquals(1,continued.get()); assertTrue(controls.state(task.id()).isEmpty());
            } finally {
                settled.countDown();
                if(controls.state(task.id()).orElse(null)==DeploymentAgentControl.State.PAUSED)
                    controls.request(task.id(),DeploymentAgentControl.CommandAction.RESUME);
            }
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Exercises cooperative boundaries on authentic CLASS/H2 tasks, without SDK PAUSED state. / 在真实CLASS/H2任务上验证协作边界，不使用SDK暂停状态。 */
class DeploymentAgentControlTest extends DeploymentRuntimeFixture {
    @Test
    void pauseWaitsForActualBoundaryAndRefreshPreservesOriginalIdentity() throws Exception {
        verify(false, false);
    }

    @Test
    void knownRejectedRefreshRemainsPausedUntilAnotherExplicitRequest() throws Exception {
        verify(true, false);
    }

    @Test
    void originalCancellationWakesPausedWorker() throws Exception {
        verify(false, true);
    }

    private void verify(boolean reject, boolean cancel) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var control = new DeploymentAgentControl();
            var journal = new DeploymentAgentJournal(context.db());
            var entered = new CountDownLatch(1);
            var settled = new CountDownLatch(1);
            var refreshed = new AtomicInteger();
            var continued = new AtomicInteger();
            var failure = new AtomicReference<Throwable>();
            var original = new AtomicReference<UUID>();
            String type = "deployment-agent-control-test";
            try (var registration = context.tasks().registerRecovery(type, (previous,
                    task) -> new OperationResult<Void>(Outcome.UNKNOWN, Optional.empty(), Optional.empty()))) {
                var handle = context.tasks()
                        .submit(new TaskRequest(type, "Original controlled worker", List.of(), true), task -> {
                            original.set(task.id());
                            journal.create(task, new DeploymentAgentJournal.Identity("boundary-only", "a".repeat(64),
                                    "a".repeat(64), "MANUAL_REVIEW"));
                            entered.countDown();
                            settled.await(5, TimeUnit.SECONDS);
                            try {
                                boolean changed = control.checkpoint(task, new DeploymentAgentControl.Boundary() {
                                    public void paused() throws Exception {
                                        journal.state(task, "WAITING");
                                    }

                                    public boolean refresh() throws Exception {
                                        if (reject && refreshed.getAndIncrement() == 0)
                                            throw new DeploymentAgentControl.RefreshRejectedException(
                                                    new Problem(ProblemCode.INVALID_INPUT,
                                                            "CONTROLLED_REFRESH_REJECTED", "Invalid selected route",
                                                            Map.of("task", task.id().toString()),
                                                            Optional.of(task.id())),
                                                    new IllegalArgumentException("Invalid selected route"));
                                        if (!reject)
                                            refreshed.incrementAndGet();
                                        journal.replaceConfiguration(task, "a".repeat(64), "b".repeat(64));
                                        return true;
                                    }

                                    public void resumed() throws Exception {
                                        continued.incrementAndGet();
                                        journal.state(task, "RUNNING");
                                    }
                                });
                                assertTrue(changed);
                                assertEquals(original.get(), task.id());
                                journal.state(task, "SUCCEEDED");
                                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                            } catch (CancellationException requested) {
                                journal.state(task, "CANCELLED");
                                return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                            } catch (Throwable problem) {
                                failure.set(problem);
                                if (problem instanceof Exception exception)
                                    throw exception;
                                if (problem instanceof Error error)
                                    throw error;
                                throw new IllegalStateException(problem);
                            } finally {
                                control.finish();
                            }
                        });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class,
                        () -> control.request(DeploymentAgentControl.CommandAction.REFRESH_MODELS));
                control.request(DeploymentAgentControl.CommandAction.PAUSE);
                assertEquals(DeploymentAgentControl.State.PAUSE_REQUESTED, control.state());
                assertEquals(TaskState.RUNNING, handle.snapshot().state());
                assertEquals(0, continued.get());
                settled.countDown();
                awaitState(control, DeploymentAgentControl.State.PAUSED);
                assertEquals(TaskState.RUNNING, handle.snapshot().state());
                if (cancel)
                    handle.requestCancel();
                else {
                    control.request(DeploymentAgentControl.CommandAction.REFRESH_MODELS);
                    if (reject) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (refreshed.get() == 0 && System.nanoTime() < deadline)
                            Thread.sleep(10);
                        assertEquals(1, refreshed.get());
                        awaitState(control, DeploymentAgentControl.State.PAUSED);
                        assertEquals(0, continued.get());
                        control.request(DeploymentAgentControl.CommandAction.REFRESH_MODELS);
                    }
                }
                var result = handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertNull(failure.get());
                assertEquals(cancel ? Outcome.CANCELLED : Outcome.SUCCEEDED, result.outcome());
                assertEquals(cancel ? 0 : 1, continued.get());
                assertEquals(original.get(), handle.id());
                assertEquals(DeploymentAgentControl.State.FINISHED, control.state());
                assertThrows(IllegalStateException.class,
                        () -> control.request(DeploymentAgentControl.CommandAction.RESUME));
            }
        }
    }

    private static void awaitState(DeploymentAgentControl control, DeploymentAgentControl.State wanted)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (control.state() != wanted && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertEquals(wanted, control.state());
    }
}

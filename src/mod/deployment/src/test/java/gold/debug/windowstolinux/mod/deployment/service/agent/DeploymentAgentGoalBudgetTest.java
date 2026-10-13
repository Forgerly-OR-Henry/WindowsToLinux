package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;

final class DeploymentAgentGoalBudgetTest {
    @Test
    void carryKeepsEveryOriginalChargeAndRejectsRefund() {
        var build = new DeploymentBuildBudget(60);
        build.charge(2_000_000_000L);
        var saved = new DeploymentAgentGoalBudget.Snapshot(20, 3, 4, 100, 9, 5, 17, 7201, build.snapshot());
        assertEquals(saved, new DeploymentAgentGoalBudget(saved).snapshot());
        var refund = new DeploymentAgentGoalBudget.Snapshot(20, 2, 4, 100, 9, 5, 17, 7201, build.snapshot());
        assertThrows(IllegalStateException.class, () -> DeploymentAgentContinuation.requireCarry(saved, refund));
        var increased = new DeploymentAgentGoalBudget.Snapshot(21, 3, 4, 100, 9, 5, 17, 7201, build.snapshot());
        assertThrows(IllegalStateException.class, () -> DeploymentAgentContinuation.requireCarry(saved, increased));
    }

    @Test
    void actualBuildChargesSaturateWithoutOverflowRefund() {
        var original = new DeploymentBuildBudget(60);
        original.charge(5_000_000_000L);
        var child = new DeploymentBuildBudget(original.snapshot());
        assertEquals(55, child.remainingSeconds());
        child.charge(Long.MAX_VALUE);
        assertEquals(0, child.remainingSeconds());
        assertEquals(child.snapshot().limitNanos(), child.snapshot().usedNanos());
        assertEquals(55, original.remainingSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"responses", "tool-batch"})
    void actualMainAndNestedCallsCarryAcrossFreshTasksAndExhaustBeforeProviderOrToolDispatch(String boundary) throws Exception {
        var root = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        root.configurationRevision = "a".repeat(64);
        var child = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        child.configurationRevision = root.configurationRevision;
        assertNotEquals(root.task.id(), child.task.id());
        var initial = new DeploymentAgentGoalBudget.Snapshot(boundary.equals("responses") ? 2 : 5, 0, 0, 2, 0, 0, 0, 0,
            new DeploymentBuildBudget(60).snapshot());
        var original = new DeploymentAgentGoalBudget(initial);
        root.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("main-original", "source", "{}"));
        try (var scope = original.open(root.task); var tools = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.open(2)) {
            var reply = new gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol(root.context, root.configurationRevision)
                .decide(java.util.Map.of(), new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory(), 1, root.task);
            assertTrue(gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.consume(reply.toolCalls()));
            DeploymentAgentGoalBudget.mainAttempts(root.task, reply.toolCalls().size());
        }
        assertEquals(1, root.requests.size());
        assertEquals(1, original.snapshot().mainResponses());
        assertEquals(1, original.snapshot().sharedTools());
        var carried = new DeploymentAgentGoalBudget(original.snapshot());
        var dispatches = new java.util.concurrent.atomic.AtomicInteger();
        var available = java.util.Map.of("done", new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop.Tool<String>(
            "Controlled local terminal tool", args -> {
            dispatches.incrementAndGet();
            return gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop.Feedback.ended(
                new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
                    java.util.Optional.of("observed"), java.util.Optional.empty()));
        }));
        child.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("nested-new", "done", "{}"));
        if (boundary.equals("tool-batch")) {
            child.replies.clear();
            child.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("nested-one", "done", "{}"),
                new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("nested-two", "done", "{}"));
        }
        try (var scope = carried.open(child.task); var tools = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.open(2)) {
            assertEquals(1, gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.remaining());
            var nested = new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop<String>(child.context, child.task, child.configurationRevision,
                "Controlled nested deployment", 2);
            if (boundary.equals("responses"))
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, nested.run(() -> available, java.util.Map::of).outcome());
            else assertThrows(IllegalStateException.class, () -> nested.run(() -> available, java.util.Map::of));
            assertEquals(1, child.requests.size());
            assertEquals(boundary.equals("responses") ? 1 : 0, dispatches.get());
            assertEquals(1, carried.snapshot().mainResponses());
            assertEquals(1, carried.snapshot().nestedResponses());
            assertEquals(2, carried.snapshot().sharedTools());
            assertEquals(0, gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.remaining());
            assertThrows(IllegalStateException.class, () -> nested.run(() -> available, java.util.Map::of));
            assertEquals(1, child.requests.size());
            assertEquals(boundary.equals("responses") ? 1 : 0, dispatches.get());
            if (boundary.equals("responses")) {
                assertThrows(IllegalStateException.class, () -> new gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol(child.context, child.configurationRevision)
                    .decide(java.util.Map.of(), new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory(), 0, child.task));
                assertEquals(1, child.requests.size());
            }
        }
        assertEquals(1, original.snapshot().sharedTools());
        assertEquals(1, original.snapshot().mainResponses());
        DeploymentAgentContinuation.requireCarry(original.snapshot(), carried.snapshot());
    }
}

package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPolicyHistory;
import org.junit.jupiter.api.Test;

/** Goal policy carry is independent of immutable source/target/budget request and old action identities. */
class DeploymentAgentGoalPolicyTest {
    @Test
    void theNextWorkerInheritsConfirmedPolicyWithoutChangingTheOriginalRequestOrBudgets() throws Exception {
        var db = (ModDatabase) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModDatabase.class},
            (p, method, args) -> {
                throw new AssertionError("No database access in policy carry test");
            });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class},
            (p, method, args) -> {
                assertEquals("db", method.getName());
                return db;
            });
        var original = new DeploymentAgentInputs.Request(Map.of("goal", "same goal"), "MANUAL_REVIEW", false, 1024, 10, 1024);
        var goal = new DeploymentAgentGoal(context, new ServerId("original-server"), original,
            ignored -> {
                throw new AssertionError("No task admission in policy carry test");
            });
        var task = task();
        var after = new Confirmed("AUTOMATIC", 1);
        try (var worker = goal.rootWorker(task).open()) {
            assertEquals(new Confirmed("MANUAL_REVIEW", 0), worker.approvalPolicy());
            worker.approvalPolicy(after);
            assertEquals(after, goal.approvalPolicy());
            assertThrows(IllegalStateException.class, () -> worker.approvalPolicy(new Confirmed("FULL_CONTROL", 0)));
        }
        var budget = new DeploymentAgentGoalBudget(new DeploymentBuildBudget(60));
        var before = budget.snapshot();
        try (var child = new DeploymentAgentGoal.Worker(goal, task(), Optional.empty(), budget).open()) {
            assertEquals(after, child.approvalPolicy());
            child.approvalPolicy(after);
            assertEquals(before, budget.snapshot());
        }
        assertEquals("MANUAL_REVIEW", original.approvalMode());
        assertFalse(original.rememberAuthorization());
        assertEquals(Map.of("goal", "same goal"), original.requirements());
        var workflow = new DeploymentAgentWorkflow(context);
        var goals = DeploymentAgentWorkflow.class.getDeclaredField("goals");
        goals.setAccessible(true);
        @SuppressWarnings("unchecked") var saved = (Map<UUID, DeploymentAgentGoal>) goals.get(workflow);
        saved.put(task.id(), goal);
        var transition = workflow.approvalPolicyState(task.id());
        assertEquals("AUTOMATIC", transition.mode());
        assertEquals(1, transition.revision());
        assertFalse(transition.editable(), "A goal between actual child workers retains its value without borrowing an old context");
        assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,
            () -> workflow.requestApprovalPolicyChange(task.id(), "FULL_CONTROL", 1));
    }

    @Test
    void historicalConfirmationEventsCarryTheLatestPolicyWithoutRewritingTheSavedIdentity() {
        var identity = new DeploymentAgentActionLog.Identity("server", "a".repeat(64), "b".repeat(64), "MANUAL_REVIEW", false);
        var events = List.of(new DeploymentAgentActionLog.Event(1, "APPROVAL_POLICY_CONFIRMED", "4", "AUTOMATIC"),
            new DeploymentAgentActionLog.Event(2, "COMMAND_EXECUTING", "a".repeat(64), "old original body"),
            new DeploymentAgentActionLog.Event(3, "APPROVAL_POLICY_CONFIRMED", "5", "FULL_CONTROL"));
        assertEquals(new Confirmed("FULL_CONTROL", 5), DeploymentAgentPolicyHistory.confirmed(events, identity.approvalMode()));
        assertEquals("MANUAL_REVIEW", identity.approvalMode());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPolicyHistory.confirmed(List.of(events.getFirst(),
            new DeploymentAgentActionLog.Event(3, "APPROVAL_POLICY_CONFIRMED", "6", "FULL_CONTROL")), identity.approvalMode()));
    }

    private TaskContext task() {
        var id = UUID.randomUUID();
        return (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
            (p, method, args) -> {
                assertEquals("id", method.getName());
                return id;
            });
    }
}

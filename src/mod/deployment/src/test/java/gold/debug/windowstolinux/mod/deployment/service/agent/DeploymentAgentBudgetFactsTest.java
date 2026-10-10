package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget;
import org.junit.jupiter.api.Test;

class DeploymentAgentBudgetFactsTest {
    @Test
    void multipleCallsAndRejectedDuplicatesConsumeActualAttemptsInsteadOfModelResponses() {
        try (var scope = DeploymentToolBudget.open(80)) {
            var initial = DeploymentAgentBudgetFacts.observe(79, 0);
            assertEquals(80, initial.get("remainingExecutableToolAttempts"));
            var calls = List.of(call("read-a"), call("read-b"), call("read-c"));
            assertTrue(DeploymentToolBudget.consume(calls));
            var next = DeploymentAgentBudgetFacts.observe(78, 3);
            assertEquals(78, next.get("remainingModelResponsesAfterThisRequest"));
            assertEquals(77, next.get("remainingSharedToolAttempts"));
            assertEquals(77, next.get("remainingExecutableToolAttempts"));
            assertFalse(DeploymentToolBudget.consume(List.of(call("read-a"))));
            var rejected = DeploymentAgentBudgetFacts.observe(77, 4);
            assertEquals(76, rejected.get("remainingExecutableToolAttempts"));
            assertEquals(76, DeploymentToolBudget.remaining(), "Reading budget facts cannot replenish a rejected attempt");
        }
    }

    @Test
    void publicationConsumptionRemainsSharedAndCanBeLowerThanMainCoordinatorAllowance() {
        try (var scope = DeploymentToolBudget.open(80)) {
            assertTrue(DeploymentToolBudget.consume(List.of(call("main-plan"), call("main-seal"))));
            assertTrue(DeploymentToolBudget.consume(List.of(call("publication-inspect"), call("publication-read"), call("publication-stage"))));
            var facts = DeploymentAgentBudgetFacts.observe(78, 2);
            assertEquals(78, facts.get("remainingMainToolAttempts"));
            assertEquals(75, facts.get("remainingSharedToolAttempts"));
            assertEquals(75, facts.get("remainingExecutableToolAttempts"));
            assertEquals(true, facts.get("publicationUsesSharedAttempts"));
            assertEquals(true, facts.get("rejectedCallsConsumeAttempts"));
            assertEquals(4, facts.get("maximumCallsPerResponse"));
            assertEquals(75, DeploymentToolBudget.remaining());
        }
    }

    @Test
    void factsDeriveActualScopeMaximumWhileModelResponsesRemainEighty() {
        for (int maximum : List.of(4, 80, 160)) {
            try (var scope = DeploymentToolBudget.open(maximum)) {
                assertTrue(DeploymentToolBudget.consume(List.of(call("main"), call("publication"))));
                var facts = DeploymentAgentBudgetFacts.observe(79, 1);
                assertEquals(80, facts.get("maximumModelResponses"));
                assertEquals(79, facts.get("remainingModelResponsesAfterThisRequest"));
                assertEquals(maximum, facts.get("maximumMainToolAttempts"));
                assertEquals(maximum, facts.get("maximumSharedToolAttempts"));
                assertEquals(maximum - 1, facts.get("remainingMainToolAttempts"));
                assertEquals(maximum - 2, facts.get("remainingSharedToolAttempts"));
                assertEquals(maximum - 2, facts.get("remainingExecutableToolAttempts"));
                assertEquals(4, facts.get("maximumCallsPerResponse"));
                assertEquals(maximum - 2, DeploymentToolBudget.remaining());
            }
        }
        var defaultFacts = DeploymentAgentBudgetFacts.observe(78, 2);
        assertEquals(80, defaultFacts.get("maximumSharedToolAttempts"));
        assertEquals(78, defaultFacts.get("remainingExecutableToolAttempts"));
    }

    @Test
    void attemptsBeyondEightyAndPublicationDebitsUseTheSameScope() {
        try (var scope = DeploymentToolBudget.open(160)) {
            var main = java.util.stream.IntStream.range(0, 81).mapToObj(i -> call("main-" + i)).toList();
            assertTrue(DeploymentToolBudget.consume(main));
            assertTrue(DeploymentToolBudget.consume(List.of(call("publish"), call("verify"))));
            var facts = DeploymentAgentBudgetFacts.observe(50, 81);
            assertEquals(79, facts.get("remainingMainToolAttempts"));
            assertEquals(77, facts.get("remainingSharedToolAttempts"));
            assertEquals(77, facts.get("remainingExecutableToolAttempts"));
            assertEquals(80, facts.get("maximumModelResponses"));
            assertFalse(DeploymentToolBudget.consume(List.of(call("publish"))));
            assertEquals(76, DeploymentAgentBudgetFacts.observe(50, 81).get("remainingExecutableToolAttempts"));
        }
    }

    private static AiToolCall call(String id) {
        return new AiToolCall(id, "read", "{}");
    }
}

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
    void factsDeriveActualScopeMaximumAndConfiguredModelResponses() {
        for (int maximum : List.of(4, 80, 512, 4096)) {
            try (var scope = DeploymentToolBudget.open(maximum)) {
                assertTrue(DeploymentToolBudget.consume(List.of(call("main"), call("publication"))));
                var facts = DeploymentAgentBudgetFacts.observe(79, 1);
                assertEquals(512, facts.get("maximumModelResponses"));
                assertEquals(79, facts.get("remainingModelResponsesAfterThisRequest"));
                int mainMaximum = Math.min(maximum, 2048);
                assertEquals(mainMaximum, facts.get("maximumMainToolAttempts"));
                assertEquals(maximum, facts.get("maximumSharedToolAttempts"));
                assertEquals(Math.min(mainMaximum - 1, 320), facts.get("remainingMainToolAttempts"));
                assertEquals(maximum - 2, facts.get("remainingSharedToolAttempts"));
                assertEquals(Math.min(Math.min(mainMaximum - 1, 320), maximum - 2), facts.get("remainingExecutableToolAttempts"));
                assertEquals(4, facts.get("maximumCallsPerResponse"));
                assertEquals(maximum - 2, DeploymentToolBudget.remaining());
            }
        }
        var defaultFacts = DeploymentAgentBudgetFacts.observe(78, 2);
        assertEquals(256, defaultFacts.get("maximumSharedToolAttempts"));
        assertEquals(254, defaultFacts.get("remainingExecutableToolAttempts"));
    }

    @Test
    void attemptsBeyondEightyAndPublicationDebitsUseTheSameScope() {
        try (var scope = DeploymentToolBudget.open(512)) {
            var main = java.util.stream.IntStream.range(0, 81).mapToObj(i -> call("main-" + i)).toList();
            assertTrue(DeploymentToolBudget.consume(main));
            assertTrue(DeploymentToolBudget.consume(List.of(call("publish"), call("verify"))));
            var facts = DeploymentAgentBudgetFacts.observe(50, 81);
            assertEquals(204, facts.get("remainingMainToolAttempts"));
            assertEquals(429, facts.get("remainingSharedToolAttempts"));
            assertEquals(204, facts.get("remainingExecutableToolAttempts"));
            assertEquals(512, facts.get("maximumModelResponses"));
            assertFalse(DeploymentToolBudget.consume(List.of(call("publish"))));
            assertEquals(428, DeploymentToolBudget.remaining());
            var publication = java.util.stream.IntStream.range(0, 300).mapToObj(i -> call("publication-" + i)).toList();
            assertTrue(DeploymentToolBudget.consume(publication));
            assertEquals(128, DeploymentAgentBudgetFacts.observe(50, 81).get("remainingExecutableToolAttempts"));
        }
    }

    @Test
    void unusedBatchSlotsCannotReplenishRemainingDecisionCapacity() {
        try (var scope = DeploymentToolBudget.open(512)) {
            assertTrue(DeploymentToolBudget.consume(List.of(call("main"))));
            var lastResponse = DeploymentAgentBudgetFacts.observe(0, 1);
            assertEquals(512, lastResponse.get("maximumMainToolAttempts"));
            assertEquals(512, lastResponse.get("maximumSharedToolAttempts"));
            assertEquals(4, lastResponse.get("remainingMainToolAttempts"));
            assertEquals(511, lastResponse.get("remainingSharedToolAttempts"));
            assertEquals(4, lastResponse.get("remainingExecutableToolAttempts"));
            var twoResponses = DeploymentAgentBudgetFacts.observe(1, 1);
            assertEquals(8, twoResponses.get("remainingExecutableToolAttempts"));
            assertEquals(511, DeploymentToolBudget.remaining());
        }
    }

    private static AiToolCall call(String id) {
        return new AiToolCall(id, "read", "{}");
    }
}

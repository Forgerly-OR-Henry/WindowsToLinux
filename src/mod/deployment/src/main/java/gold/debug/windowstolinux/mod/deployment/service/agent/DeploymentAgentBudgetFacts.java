package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget;

/** Read-only budget facts distinguish model responses from native attempts. / 只读预算事实区分模型响应与原生工具尝试。 */
final class DeploymentAgentBudgetFacts {
    private DeploymentAgentBudgetFacts() {
    }

    static Map<String, Object> observe(int remainingResponses, int mainAttempts) {
        var goal = DeploymentAgentGoalBudget.current();
        if (goal.isPresent()) {
            var original = goal.orElseThrow().snapshot();
            return Map.ofEntries(Map.entry("maximumModelResponses", original.maximumResponses()),
                Map.entry("remainingModelResponsesAfterThisRequest", Math.max(0, original.remainingResponses() - 1)),
                Map.entry("maximumMainToolAttempts", Math.min(original.maximumTools(), original.maximumResponses() * 4)), Map.entry("maximumSharedToolAttempts", original.maximumTools()),
                Map.entry("mainToolAttempts", original.mainTools()), Map.entry("remainingMainToolAttempts", Math.max(0, Math.min(original.maximumTools(), original.maximumResponses() * 4) - original.mainTools())),
                Map.entry("remainingSharedToolAttempts", original.remainingTools()), Map.entry("remainingExecutableToolAttempts", original.remainingTools()),
                Map.entry("maximumCallsPerResponse", 4), Map.entry("rejectedCallsConsumeAttempts", true), Map.entry("publicationUsesSharedAttempts", true),
                Map.entry("goal", original), Map.entry("continuation", DeploymentAgentGoal.currentWorker()
                    .flatMap(DeploymentAgentGoal.Worker::predecessor).map(value -> Map.of(
                        "root", value.root().toString(), "predecessor", value.predecessor().toString(), "ticket", value.ticket().toString(),
                        "operation", value.operation().toString(), "application", value.application(), "source", value.source(),
                        "acceptedFailure", value.acceptedFailure(), "nativeFailureObservation", value.nativeFailureObservation()))
                    .orElse(Map.of())), Map.entry("hostCommandProtocol", "PER_TASK_VERSION_1_UNCHANGED; cumulative counts are facts"));
        }
        int maximumAttempts = DeploymentToolBudget.maximum();
        int maximumResponses = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().agentModelResponses();
        int maximumMainAttempts = Math.min(maximumAttempts, maximumResponses * 4);
        int mainRemaining = Math.min(Math.max(0, maximumMainAttempts - mainAttempts), Math.max(0, remainingResponses + 1) * 4);
        int sharedRemaining = DeploymentToolBudget.remaining();
        return Map.ofEntries(Map.entry("maximumModelResponses", maximumResponses), Map.entry("remainingModelResponsesAfterThisRequest", remainingResponses),
            Map.entry("maximumMainToolAttempts", maximumMainAttempts), Map.entry("maximumSharedToolAttempts", maximumAttempts),
            Map.entry("mainToolAttempts", mainAttempts), Map.entry("remainingMainToolAttempts", mainRemaining),
            Map.entry("remainingSharedToolAttempts", sharedRemaining), Map.entry("remainingExecutableToolAttempts", Math.min(mainRemaining, sharedRemaining)),
            Map.entry("maximumCallsPerResponse", 4), Map.entry("rejectedCallsConsumeAttempts", true), Map.entry("publicationUsesSharedAttempts", true));
    }
}

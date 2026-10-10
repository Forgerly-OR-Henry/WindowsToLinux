package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget;

/** Read-only budget facts distinguish model responses from native attempts. / 只读预算事实区分模型响应与原生工具尝试。 */
final class DeploymentAgentBudgetFacts {
    private DeploymentAgentBudgetFacts() {
    }

    static Map<String, Object> observe(int remainingResponses, int mainAttempts) {
        int mainRemaining = Math.max(0, 80 - mainAttempts);
        int sharedRemaining = DeploymentToolBudget.remaining();
        return Map.of("maximumModelResponses", 80, "remainingModelResponsesAfterThisRequest", remainingResponses,
            "maximumSharedToolAttempts", 80, "mainToolAttempts", mainAttempts,
            "remainingMainToolAttempts", mainRemaining, "remainingSharedToolAttempts", sharedRemaining,
            "remainingExecutableToolAttempts", Math.min(mainRemaining, sharedRemaining),
            "maximumCallsPerResponse", 4, "rejectedCallsConsumeAttempts", true, "publicationUsesSharedAttempts", true);
    }
}

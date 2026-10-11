package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget;

/** Read-only budget facts distinguish model responses from native attempts. / 只读预算事实区分模型响应与原生工具尝试。 */
final class DeploymentAgentBudgetFacts {
    private DeploymentAgentBudgetFacts() {
    }

    static Map<String, Object> observe(int remainingResponses, int mainAttempts) {
        int maximumAttempts = DeploymentToolBudget.maximum();
        int maximumMainAttempts = Math.min(maximumAttempts, 80 * 4);
        int mainRemaining = Math.min(Math.max(0, maximumMainAttempts - mainAttempts), Math.max(0, remainingResponses + 1) * 4);
        int sharedRemaining = DeploymentToolBudget.remaining();
        return Map.ofEntries(Map.entry("maximumModelResponses", 80), Map.entry("remainingModelResponsesAfterThisRequest", remainingResponses),
            Map.entry("maximumMainToolAttempts", maximumMainAttempts), Map.entry("maximumSharedToolAttempts", maximumAttempts),
            Map.entry("mainToolAttempts", mainAttempts), Map.entry("remainingMainToolAttempts", mainRemaining),
            Map.entry("remainingSharedToolAttempts", sharedRemaining), Map.entry("remainingExecutableToolAttempts", Math.min(mainRemaining, sharedRemaining)),
            Map.entry("maximumCallsPerResponse", 4), Map.entry("rejectedCallsConsumeAttempts", true), Map.entry("publicationUsesSharedAttempts", true));
    }
}

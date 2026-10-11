package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

/** Model verdict only; exact request validation and host grants remain in the shared approval boundary. */
@FunctionalInterface
public interface DeploymentCommandReviewModel {
    default String responseInstructions() {
        return DeploymentReviewDecision.INSTRUCTION;
    }

    Result review(String instructions, Map<String, ?> facts, ActionIntent intent, CommandRequest request,
                  String revision, TaskContext task) throws ApiException, DeploymentAiJson.CapabilityOutcomeException, IOException;

    record Result(DeploymentReviewDecision decision, Map<String, String> refusalData) {
        public Result {
            Objects.requireNonNull(decision);
            if (!java.util.Set.of("APPROVE", "DENY").contains(decision.decision()) || decision.reason() == null
                || decision.reason().isBlank() || decision.reason().length() > 2000)
                throw new IllegalArgumentException("Invalid independent model verdict");
            refusalData = Map.copyOf(refusalData);
            if (!java.util.Set.of("missingEvidence", "adjustments", "candidateCommands", "reviewBinding", "discussionStatus").containsAll(refusalData.keySet())
                || refusalData.values().stream().anyMatch(value -> value == null || value.length() > 4096))
                throw new IllegalArgumentException("Invalid bounded model refusal data");
            if (decision.decision().equals("APPROVE") && !refusalData.isEmpty())
                throw new IllegalArgumentException("Approval cannot authorize suggested replacements");
        }
    }

    static DeploymentCommandReviewModel submission(DeploymentAiJson ai) {
        return (instructions, facts, intent, request, revision, task) -> new Result(
            DeploymentReviewDecision.parse(ai.askCommand(instructions, facts, revision, task, DeploymentReviewDecision::malformed)), Map.of());
    }
}

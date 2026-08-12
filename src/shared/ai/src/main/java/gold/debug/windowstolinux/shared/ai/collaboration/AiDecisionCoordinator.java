package gold.debug.windowstolinux.shared.ai.collaboration;

import java.util.List;
import java.util.Objects;

/** Reconciles optional model advice without allowing it to grant execution authority. / 协调可选模型建议且不允许其授予执行权限。 */
public final class AiDecisionCoordinator {
    /** Reconciles deterministic authority and validated advisory evidence. / 协调确定性权威与已验证建议证据。 */
    public AiCollaborationDecision reconcile(DeterministicDecision deterministic,
                                             List<AiRoleInvocationResult> invocations) {
        Objects.requireNonNull(deterministic, "deterministic");
        List<AiInvocationEvidence> evidence = Objects.requireNonNull(invocations, "invocations").stream()
                .map(AiRoleInvocationResult::evidence).toList();
        if (deterministic == DeterministicDecision.STOP) {
            return decision(CollaborationDisposition.SAFE_STOP, "deterministic-stop", evidence);
        }
        List<AiAdviceDecision> validated = evidence.stream()
                .filter(item -> item.status() == AiInvocationStatus.VALIDATED)
                .map(item -> item.output().orElseThrow().decision()).distinct().toList();
        if (validated.contains(AiAdviceDecision.NEEDS_HUMAN_DECISION)) {
            return decision(CollaborationDisposition.USER_DECISION_REQUIRED, "model-requests-human-decision", evidence);
        }
        if (validated.size() > 1) {
            return decision(CollaborationDisposition.USER_DECISION_REQUIRED, "validated-model-conflict", evidence);
        }
        if (validated.contains(AiAdviceDecision.SAFE_STOP)) {
            return decision(CollaborationDisposition.SAFE_STOP, "validated-model-safe-stop", evidence);
        }
        String reason = evidence.stream().anyMatch(item -> item.status() != AiInvocationStatus.VALIDATED)
                ? "selected-model-unavailable-or-invalid" : "deterministic-allow-preserved";
        return decision(CollaborationDisposition.DETERMINISTIC_ONLY, reason, evidence);
    }

    private static AiCollaborationDecision decision(CollaborationDisposition disposition, String reason,
                                                     List<AiInvocationEvidence> evidence) {
        return new AiCollaborationDecision(disposition, reason, evidence);
    }
}

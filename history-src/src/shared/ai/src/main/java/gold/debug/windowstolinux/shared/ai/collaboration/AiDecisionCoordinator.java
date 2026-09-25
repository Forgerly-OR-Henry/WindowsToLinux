package gold.debug.windowstolinux.shared.ai.collaboration;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationEvidence;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;

/**
 * Reconciles optional model advice without allowing it to grant execution authority. / 协调可选模型建议且不允许其授予执行权限。
 */
public final class AiDecisionCoordinator {
    /**
     * Reconciles deterministic authority and validated advisory evidence. / 协调确定性权威与已验证建议证据。
     *
     * @param deterministic deterministic / 确定性
     * @param invocations invocations / 调用集合
     * @return constructed or resolved ai collaboration decision / 构造或解析得到的AICollaboration决定
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
                ? "selected-model-unavailable-or-invalid"
                : "deterministic-allow-preserved";
        return decision(CollaborationDisposition.DETERMINISTIC_ONLY, reason, evidence);
    }

    /**
     * Builds ai collaboration decision from the supplied decision inputs.
     * <p>根据所提供决定输入构建AICollaboration决定。
     *
     * @param disposition disposition / 处置方式
     * @param reason reason / 原因
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return ai collaboration decision from the supplied decision inputs / 根据所提供决定输入构建AICollaboration决定
     */
    private static AiCollaborationDecision decision(CollaborationDisposition disposition, String reason,
            List<AiInvocationEvidence> evidence) {
        return new AiCollaborationDecision(disposition, reason, evidence);
    }
}

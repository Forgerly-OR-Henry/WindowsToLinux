package gold.debug.windowstolinux.shared.ai.collaboration;

import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationEvidence;

import java.util.List;
import java.util.Objects;

/**
 * Reconciled decision that preserves deterministic authority and every invocation record. / 保留确定性权威及全部调用记录的协调决策。
 *
 * @param disposition disposition / 处置方式
 * @param reason reason / 原因
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record AiCollaborationDecision(
        CollaborationDisposition disposition,
        String reason,
        List<AiInvocationEvidence> evidence
) {
    /**
     * Validates the bounded decision record. / 验证有界决策记录。
     *
     * @param disposition disposition / 处置方式
     * @param reason reason / 原因
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiCollaborationDecision {
        disposition = Objects.requireNonNull(disposition, "disposition");
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (!reason.matches("[a-z][a-z0-9-]{0,95}")) throw new IllegalArgumentException("reason is invalid");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}

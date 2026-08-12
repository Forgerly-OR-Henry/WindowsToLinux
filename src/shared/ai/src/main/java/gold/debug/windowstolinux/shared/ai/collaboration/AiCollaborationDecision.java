package gold.debug.windowstolinux.shared.ai.collaboration;

import java.util.List;
import java.util.Objects;

/** Reconciled decision that preserves deterministic authority and every invocation record. / 保留确定性权威及全部调用记录的协调决策。 */
public record AiCollaborationDecision(
        CollaborationDisposition disposition,
        String reason,
        List<AiInvocationEvidence> evidence
) {
    /** Validates the bounded decision record. / 验证有界决策记录。 */
    public AiCollaborationDecision {
        disposition = Objects.requireNonNull(disposition, "disposition");
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (!reason.matches("[a-z][a-z0-9-]{0,95}")) throw new IllegalArgumentException("reason is invalid");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}

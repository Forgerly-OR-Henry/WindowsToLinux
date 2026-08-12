package gold.debug.windowstolinux.shared.ai.collaboration;

import java.util.Objects;

/** Result of exactly one provider invocation for one role. / 一个角色恰好一次提供者调用的结果。 */
public record AiRoleInvocationResult(AiInvocationEvidence evidence) {
    /** Validates the result wrapper. / 验证结果包装。 */
    public AiRoleInvocationResult {
        evidence = Objects.requireNonNull(evidence, "evidence");
    }
}

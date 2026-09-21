package gold.debug.windowstolinux.shared.ai.collaboration.invocation;

import java.util.Objects;

/**
 * Result of exactly one provider invocation for one role. / 一个角色恰好一次提供者调用的结果。
 *
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 * @param attempts attempts / 尝试集合
 */
public record AiRoleInvocationResult(AiInvocationEvidence evidence, java.util.List<AiProviderAttempt> attempts) {
    /**
     * Captures a single transport attempt before service-level chaining. / 在服务层串联前捕获一次传输尝试。
     *
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    public AiRoleInvocationResult(AiInvocationEvidence evidence) {
        this(evidence, java.util.List.of(new AiProviderAttempt(evidence.providerId(), evidence.model(), evidence.status(), evidence.validationDetail(), evidence.observedAt())));
    }
    /**
     * Validates the result wrapper. / 验证结果包装。
     *
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param attempts attempts / 尝试集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiRoleInvocationResult {
        evidence = Objects.requireNonNull(evidence, "evidence");
        attempts = java.util.List.copyOf(attempts);
    }
}

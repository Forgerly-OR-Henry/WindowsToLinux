package gold.debug.windowstolinux.api.ai;

import java.util.Optional;

/**
 * Optional nonnegative token counts reported by the provider. / 提供方可选报告的非负令牌用量。
 * @param inputTokens optional nonnegative input token count / 可选的非负输入令牌数
 * @param outputTokens optional nonnegative output token count / 可选的非负输出令牌数
 */
public record AiUsage(Optional<Long> inputTokens, Optional<Long> outputTokens) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param inputTokens optional nonnegative input token count / 可选的非负输入令牌数
     * @param outputTokens optional nonnegative output token count / 可选的非负输出令牌数
     */
    public AiUsage {
        java.util.Objects.requireNonNull(inputTokens, "inputTokens");
        java.util.Objects.requireNonNull(outputTokens, "outputTokens");
        if (inputTokens.isPresent() && inputTokens.get() < 0)
            throw new IllegalArgumentException("Negative input token count");
        if (outputTokens.isPresent() && outputTokens.get() < 0)
            throw new IllegalArgumentException("Negative output token count");
    }
}

package gold.debug.windowstolinux.appapi.ai.model;

import java.util.Optional;

/**
 * Optional nonnegative token counts reported by the provider. / 提供方可选报告的非负令牌用量。
 * @param inputTokens optional nonnegative input token count / 可选的非负输入令牌数
 * @param outputTokens optional nonnegative output token count / 可选的非负输出令牌数
 * @param cachedInputTokens optional cached input count, included in inputTokens / 可选缓存输入数，包含在输入总数内
 * @param cacheMissInputTokens optional provider-reported uncached input count / 可选提供方报告的未命中输入数
 * @param reasoningOutputTokens optional reasoning count, included in outputTokens / 可选推理输出数，包含在输出总数内
 */
public record AiUsage(Optional<Long> inputTokens, Optional<Long> outputTokens, Optional<Long> cachedInputTokens,
                      Optional<Long> cacheMissInputTokens, Optional<Long> reasoningOutputTokens) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param inputTokens optional nonnegative input token count / 可选的非负输入令牌数
     * @param outputTokens optional nonnegative output token count / 可选的非负输出令牌数
     * @param cachedInputTokens optional cached input count / 可选缓存输入数
     * @param cacheMissInputTokens optional uncached input count / 可选未命中输入数
     * @param reasoningOutputTokens optional reasoning output count / 可选推理输出数
     */
    public AiUsage {
        for (var value : java.util.List.of(inputTokens, outputTokens, cachedInputTokens, cacheMissInputTokens, reasoningOutputTokens))
            if (value.isPresent() && value.orElseThrow() < 0)
                throw new IllegalArgumentException("Negative token count");
        requireSubset(cachedInputTokens, inputTokens);
        requireSubset(cacheMissInputTokens, inputTokens);
        requireSubset(reasoningOutputTokens, outputTokens);
        if (inputTokens.isPresent() && cachedInputTokens.isPresent() && cacheMissInputTokens.isPresent()
            && cachedInputTokens.orElseThrow() != inputTokens.orElseThrow() - cacheMissInputTokens.orElseThrow())
            throw new IllegalArgumentException("Cache counts do not match input tokens");
    }

    /** Checks reported subset counts without inventing missing totals. / 校验已报告子集数，不虚构缺失总数。
     * @param subset optional subset count / 可选子集数
     * @param total optional total count / 可选总数 */
    private static void requireSubset(Optional<Long> subset, Optional<Long> total) {
        if (subset.isPresent() && total.isPresent() && subset.orElseThrow() > total.orElseThrow())
            throw new IllegalArgumentException("Token subset exceeds total");
    }
}

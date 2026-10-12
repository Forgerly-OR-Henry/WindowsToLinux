package gold.debug.windowstolinux.appapi.ai.model;

/**
 * Model response and usage without provider implementation types. / 不暴露提供方实现类型的模型响应及用量。
 * @param text message or response text / 消息或响应文本
 * @param modelId platform identity of the selected model configuration / 选定模型配置的平台身份
 * @param finishReason provider-reported reason for ending generation / 提供方报告的生成结束原因
 * @param usage provider-reported token usage / 提供方报告的令牌用量
 * @param toolCalls complete native call batch / 完整原生调用批次
 * @param continuation task-bound opaque continuation / 任务绑定的不透明续接状态
 * @param encodedRequestBytes host-measured encoded request length, absent for unmeasured fixtures / 宿主测量的编码请求长度，未测量的夹具为空
 * @param effectiveMaxOutputTokens actual host-sent request budget, not the model capability limit / 宿主实际发送的请求额度，不表示模型能力上限
 * @param generationAttempts immutable physical generation outcomes; unknown usage is never replaced by a later response / 不可变物理生成结果，未知用量不由后续响应覆盖
 */
public record AiResponse(String text, String modelId, String finishReason, AiUsage usage,
                         java.util.List<AiToolCall> toolCalls, java.util.Optional<AiContinuation> continuation,
                         java.util.Optional<Integer> encodedRequestBytes,
                         java.util.Optional<Integer> effectiveMaxOutputTokens,
                         java.util.List<AiGenerationAttempt> generationAttempts) {
    public AiResponse(String text, String modelId, String finishReason, AiUsage usage,
                      java.util.List<AiToolCall> toolCalls, java.util.Optional<AiContinuation> continuation,
                      java.util.Optional<Integer> encodedRequestBytes,
                      java.util.Optional<Integer> effectiveMaxOutputTokens) {
        this(text, modelId, finishReason, usage, toolCalls, continuation, encodedRequestBytes, effectiveMaxOutputTokens, java.util.List.of());
    }

    public AiResponse(String text, String modelId, String finishReason, AiUsage usage,
                      java.util.List<AiToolCall> toolCalls, java.util.Optional<AiContinuation> continuation,
                      java.util.Optional<Integer> encodedRequestBytes) {
        this(text, modelId, finishReason, usage, toolCalls, continuation, encodedRequestBytes, java.util.Optional.empty());
    }

    public AiResponse(String text, String modelId, String finishReason, AiUsage usage,
                      java.util.List<AiToolCall> toolCalls, java.util.Optional<AiContinuation> continuation) {
        this(text, modelId, finishReason, usage, toolCalls, continuation, java.util.Optional.empty());
    }

    public AiResponse(String text, String modelId, String finishReason, AiUsage usage) {
        this(text, modelId, finishReason, usage, java.util.List.of(), java.util.Optional.empty());
    }

    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param text message or response text / 消息或响应文本
     * @param modelId platform identity of the selected model configuration / 选定模型配置的平台身份
     * @param finishReason provider-reported reason for ending generation / 提供方报告的生成结束原因
     * @param usage provider-reported token usage / 提供方报告的令牌用量
     */
    public AiResponse {
        java.util.Objects.requireNonNull(text, "text");
        java.util.Objects.requireNonNull(modelId, "modelId");
        java.util.Objects.requireNonNull(finishReason, "finishReason");
        java.util.Objects.requireNonNull(usage, "usage");
        generationAttempts = AiGenerationAttempt.validated(generationAttempts);
        toolCalls = java.util.List.copyOf(toolCalls);
        java.util.Objects.requireNonNull(continuation);
        java.util.Objects.requireNonNull(encodedRequestBytes);
        java.util.Objects.requireNonNull(effectiveMaxOutputTokens);
        if (effectiveMaxOutputTokens.isPresent() && effectiveMaxOutputTokens.orElseThrow() < 1)
            throw new IllegalArgumentException("Effective output budget must be positive");
        if (encodedRequestBytes.isPresent() && (encodedRequestBytes.orElseThrow() < 1 || encodedRequestBytes.orElseThrow() > 64 * 1024 * 1024))
            throw new IllegalArgumentException("Encoded request size is invalid");
        if (toolCalls.stream().map(AiToolCall::id).distinct().count() != toolCalls.size())
            throw new IllegalArgumentException("Duplicate native tool call IDs");
    }
}

package gold.debug.windowstolinux.appapi.ai;

/**
 * Model response and usage without provider implementation types. / 不暴露提供方实现类型的模型响应及用量。
 * @param text message or response text / 消息或响应文本
 * @param modelId platform identity of the selected model configuration / 选定模型配置的平台身份
 * @param finishReason provider-reported reason for ending generation / 提供方报告的生成结束原因
 * @param usage provider-reported token usage / 提供方报告的令牌用量
 */
public record AiResponse(String text, String modelId, String finishReason, AiUsage usage) {
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
    }
}

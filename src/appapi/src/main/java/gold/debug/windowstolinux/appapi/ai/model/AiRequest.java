package gold.debug.windowstolinux.appapi.ai.model;

import java.time.Duration;
import java.util.List;

/**
 * Bounded model invocation with explicit time and output budgets. / 具有明确时间及输出预算的模型调用。
 * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
 * @param messages copied model conversation in request order / 按请求顺序复制的模型对话
 * @param maxOutputTokens strictly positive output token budget / 严格为正的输出令牌预算
 * @param timeout strictly positive operation deadline / 严格为正的操作期限
 * @param jsonObjectResponse whether the response must contain a JSON object / 是否要求响应包含 JSON 对象
 */
public record AiRequest(AiPurpose purpose, List<AiMessage> messages, int maxOutputTokens, Duration timeout,
                        boolean jsonObjectResponse) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
     * @param messages copied model conversation in request order / 按请求顺序复制的模型对话
     * @param maxOutputTokens strictly positive output token budget / 严格为正的输出令牌预算
     * @param timeout strictly positive operation deadline / 严格为正的操作期限
     * @param jsonObjectResponse whether the response must contain a JSON object / 是否要求响应包含 JSON 对象
     */
    public AiRequest {
        java.util.Objects.requireNonNull(purpose, "purpose");
        messages = List.copyOf(messages);
        if (maxOutputTokens < 1)
            throw new IllegalArgumentException("maxOutputTokens is below its minimum");
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");
    }
}

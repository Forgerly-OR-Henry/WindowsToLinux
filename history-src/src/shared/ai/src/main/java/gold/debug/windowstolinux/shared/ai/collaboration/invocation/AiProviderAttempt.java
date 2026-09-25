package gold.debug.windowstolinux.shared.ai.collaboration.invocation;

import java.time.Instant;
import java.util.Objects;

/**
 * One bounded provider attempt without secrets, prompts or raw responses. / 不含秘密、提示或原始响应的有界提供者尝试记录。
 *
 * @param providerId provider id / 提供者标识
 * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param detail detail / 详情
 * @param attemptedAt attempted at / 已尝试时刻
 */
public record AiProviderAttempt(String providerId, String model, AiInvocationStatus status, String detail,
        Instant attemptedAt) {
    /**
     * Validates a compact attempt record. / 校验精简尝试记录。
     *
     * @param providerId provider id / 提供者标识
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param detail detail / 详情
     * @param attemptedAt attempted at / 已尝试时刻
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiProviderAttempt {
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(model);
        Objects.requireNonNull(status);
        Objects.requireNonNull(detail);
        Objects.requireNonNull(attemptedAt);
        if (providerId.length() > 64 || model.length() > 128 || detail.length() > 256)
            throw new IllegalArgumentException("AI attempt metadata exceeds bounds");
    }
}

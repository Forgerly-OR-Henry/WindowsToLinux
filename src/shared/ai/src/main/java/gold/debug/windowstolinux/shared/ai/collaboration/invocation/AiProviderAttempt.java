package gold.debug.windowstolinux.shared.ai.collaboration.invocation;

import java.time.Instant;
import java.util.Objects;

/** One bounded provider attempt without secrets, prompts or raw responses. / 不含秘密、提示或原始响应的有界提供者尝试记录。 */
public record AiProviderAttempt(String providerId, String model, AiInvocationStatus status, String detail, Instant attemptedAt) {
    /** Validates a compact attempt record. / 校验精简尝试记录。 */
    public AiProviderAttempt {
        Objects.requireNonNull(providerId); Objects.requireNonNull(model); Objects.requireNonNull(status); Objects.requireNonNull(detail); Objects.requireNonNull(attemptedAt);
        if (providerId.length() > 64 || model.length() > 128 || detail.length() > 256) throw new IllegalArgumentException("AI attempt metadata exceeds bounds");
    }
}

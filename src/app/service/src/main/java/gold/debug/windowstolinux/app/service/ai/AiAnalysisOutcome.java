package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;

/**
 * UI-safe result for the optional AI explanation operation.
 *
 * <p>适合界面使用的可选 AI 解释操作结果。
 *
 * @param available the {@code available} value / {@code available} 值
 * @param status the {@code status} value / {@code status} 值
 * @param content the {@code content} value / {@code content} 值
 * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
 */
public record AiAnalysisOutcome(boolean available, LocalizedMessage status, String content, String diagnostic,
                                java.util.List<gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiProviderAttempt> attempts) {
    /** Preserves results created before ordered invocation. / 保留有序调用引入前的结果构造。 */
    public AiAnalysisOutcome(boolean available, LocalizedMessage status, String content, String diagnostic) { this(available, status, content, diagnostic, java.util.List.of()); }
    /**
     * Creates a {@code AiAnalysisOutcome} instance.
     *
     * <p>创建 {@code AiAnalysisOutcome} 实例。
     *
     * @param available the {@code available} value / {@code available} 值
     * @param status the {@code status} value / {@code status} 值
     * @param content the {@code content} value / {@code content} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AiAnalysisOutcome {
        status = Objects.requireNonNull(status, "status");
        content = Objects.requireNonNull(content, "content");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        attempts = java.util.List.copyOf(attempts);
    }

    /**
     * Performs the {@code unavailable} operation.
     *
     * <p>执行 {@code unavailable} 操作。
     *
     * @param status the {@code status} value / {@code status} 值
     * @return the operation result / 操作结果
     */
    public static AiAnalysisOutcome unavailable(LocalizedMessage status) {
        return unavailable(status, "");
    }

    /**
     * Performs the {@code unavailable} operation.
     *
     * <p>执行 {@code unavailable} 操作。
     *
     * @param status the {@code status} value / {@code status} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @return the operation result / 操作结果
     */
    public static AiAnalysisOutcome unavailable(LocalizedMessage status, String diagnostic) {
        return new AiAnalysisOutcome(false, status, "", diagnostic);
    }

    /**
     * Performs the {@code available} operation.
     *
     * <p>执行 {@code available} 操作。
     *
     * @param content the {@code content} value / {@code content} 值
     * @return the operation result / 操作结果
     */
    public static AiAnalysisOutcome available(String content) {
        return new AiAnalysisOutcome(true, LocalizedMessage.of("ai.available"), content, "");
    }
}

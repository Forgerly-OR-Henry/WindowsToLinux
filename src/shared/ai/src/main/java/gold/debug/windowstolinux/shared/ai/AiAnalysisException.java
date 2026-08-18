package gold.debug.windowstolinux.shared.ai;

import gold.debug.windowstolinux.shared.model.message.LocalizedFailure;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;

/**
 * A safe, non-secret failure from the optional AI explanation path.
 *
 * <p>可选 AI 解释路径产生的安全、非秘密失败。
 */
public final class AiAnalysisException extends Exception implements LocalizedFailure {
    private final LocalizedMessage userMessage;
    private final String diagnostic;

    /**
     * Creates a {@code AiAnalysisException} instance.
     *
     * <p>创建 {@code AiAnalysisException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     */
    public AiAnalysisException(LocalizedMessage userMessage, String diagnostic) {
        this(userMessage, diagnostic, null);
    }

    /**
     * Creates a {@code AiAnalysisException} instance.
     *
     * <p>创建 {@code AiAnalysisException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @param cause the {@code cause} value / {@code cause} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AiAnalysisException(LocalizedMessage userMessage, String diagnostic, Throwable cause) {
        super(Objects.requireNonNull(diagnostic, "diagnostic"), cause);
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.diagnostic = diagnostic;
    }

    /** Performs the {@code userMessage} operation. / 执行 {@code userMessage} 操作。 */
    @Override
    public LocalizedMessage userMessage() {
        return userMessage;
    }

    /** Performs the {@code diagnostic} operation. / 执行 {@code diagnostic} 操作。 */
    @Override
    public String diagnostic() {
        return diagnostic;
    }
}

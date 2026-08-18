package gold.debug.windowstolinux.shared.model.message;

import java.util.Objects;

/**
 * An unchecked application-boundary failure with a localizable summary.
 *
 * <p>带有可本地化摘要的应用边界非受检失败。
 */
public final class LocalizedOperationException extends RuntimeException implements LocalizedFailure {
    private final LocalizedMessage userMessage;
    private final String diagnostic;

    /**
     * Creates a {@code LocalizedOperationException} instance.
     *
     * <p>创建 {@code LocalizedOperationException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     */
    public LocalizedOperationException(LocalizedMessage userMessage, String diagnostic) {
        this(userMessage, diagnostic, null);
    }

    /**
     * Creates a {@code LocalizedOperationException} instance.
     *
     * <p>创建 {@code LocalizedOperationException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @param cause the {@code cause} value / {@code cause} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public LocalizedOperationException(LocalizedMessage userMessage, String diagnostic, Throwable cause) {
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

package gold.debug.windowstolinux.shared.linux.connection;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedFailure;

import java.util.Objects;
import java.util.Map;

/**
 * A connection, fingerprint, protocol or controlled-operation failure.
 *
 * <p>连接、指纹、协议或受控操作失败。
 */
public final class LinuxOperationException extends Exception implements LocalizedFailure {
    private final LocalizedMessage userMessage;
    private final String diagnostic;

    /**
     * Creates a {@code LinuxOperationException} instance.
     *
     * <p>创建 {@code LinuxOperationException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     */
    public LinuxOperationException(LocalizedMessage userMessage, String diagnostic) {
        this(userMessage, diagnostic, null);
    }

    /**
     * Creates a {@code LinuxOperationException} instance.
     *
     * <p>创建 {@code LinuxOperationException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @param cause the {@code cause} value / {@code cause} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public LinuxOperationException(LocalizedMessage userMessage, String diagnostic, Throwable cause) {
        super(Objects.requireNonNull(diagnostic, "diagnostic"), cause);
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.diagnostic = diagnostic;
    }

    /**
     * Performs the {@code localized} operation.
     *
     * <p>执行 {@code localized} 操作。
     *
     * @param messageKey the {@code messageKey} value / {@code messageKey} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @return the operation result / 操作结果
     */
    public static LinuxOperationException localized(String messageKey, String diagnostic) {
        return new LinuxOperationException(LocalizedMessage.of(messageKey), diagnostic);
    }

    /**
     * Performs the {@code localized} operation.
     *
     * <p>执行 {@code localized} 操作。
     *
     * @param messageKey the {@code messageKey} value / {@code messageKey} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @param cause the {@code cause} value / {@code cause} 值
     * @return the operation result / 操作结果
     */
    public static LinuxOperationException localized(String messageKey, String diagnostic, Throwable cause) {
        return new LinuxOperationException(LocalizedMessage.of(messageKey), diagnostic, cause);
    }

    /**
     * Performs the {@code localized} operation.
     *
     * <p>执行 {@code localized} 操作。
     *
     * @param messageKey the {@code messageKey} value / {@code messageKey} 值
     * @param arguments the {@code arguments} value / {@code arguments} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @return the operation result / 操作结果
     */
    public static LinuxOperationException localized(
            String messageKey,
            Map<String, ?> arguments,
            String diagnostic
    ) {
        return new LinuxOperationException(LocalizedMessage.of(messageKey, arguments), diagnostic);
    }

    /**
     * Performs the {@code localized} operation.
     *
     * <p>执行 {@code localized} 操作。
     *
     * @param messageKey the {@code messageKey} value / {@code messageKey} 值
     * @param arguments the {@code arguments} value / {@code arguments} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @param cause the {@code cause} value / {@code cause} 值
     * @return the operation result / 操作结果
     */
    public static LinuxOperationException localized(
            String messageKey,
            Map<String, ?> arguments,
            String diagnostic,
            Throwable cause
    ) {
        return new LinuxOperationException(LocalizedMessage.of(messageKey, arguments), diagnostic, cause);
    }

    @Override
    public LocalizedMessage userMessage() {
        return userMessage;
    }

    @Override
    public String diagnostic() {
        return diagnostic;
    }
}

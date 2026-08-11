package gold.debug.windowstolinux.app.secret.api;

import gold.debug.windowstolinux.shared.model.message.LocalizedFailure;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;

/**
 * Sensitive-store failures are intentionally not expanded with plaintext input.
 *
 * <p>敏感存储失败被刻意设计为不附加明文输入。
 */
public final class SecretStoreException extends Exception implements LocalizedFailure {
    private final LocalizedMessage userMessage;
    private final String diagnostic;

    /**
     * Creates a {@code SecretStoreException} instance.
     *
     * <p>创建 {@code SecretStoreException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     * @param cause the {@code cause} value / {@code cause} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SecretStoreException(LocalizedMessage userMessage, String diagnostic, Throwable cause) {
        super(Objects.requireNonNull(diagnostic, "diagnostic"), cause);
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.diagnostic = diagnostic;
    }

    /**
     * Creates a {@code SecretStoreException} instance.
     *
     * <p>创建 {@code SecretStoreException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     */
    public SecretStoreException(LocalizedMessage userMessage, String diagnostic) {
        this(userMessage, diagnostic, null);
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

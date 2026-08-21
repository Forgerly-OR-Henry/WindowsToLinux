package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * Sensitive-store failures are intentionally not expanded with plaintext input.
 *
 * <p>敏感存储失败被刻意设计为不附加明文输入。
 */
public final class SecretStoreException extends Exception implements FailureCarrier {
    private final FailureDescriptor failure;

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
    public SecretStoreException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates a {@code SecretStoreException} instance.
     *
     * <p>创建 {@code SecretStoreException} 实例。
     *
     * @param userMessage the {@code userMessage} value / {@code userMessage} 值
     * @param diagnostic the {@code diagnostic} value / {@code diagnostic} 值
     */
    public static SecretStoreException create(SecretStoreFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /** Creates a typed failure with its original cause. / 创建带原始原因的类型化失败。 */
    public static SecretStoreException create(SecretStoreFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /** Creates a typed failure with safe message arguments. / 创建带安全消息参数的类型化失败。 */
    public static SecretStoreException create(
            SecretStoreFailureType type, Map<String, ?> arguments, String diagnostic, Throwable cause) {
        return new SecretStoreException(
                FailureDescriptor.create(type, OperationIdentity.create(), arguments, diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}

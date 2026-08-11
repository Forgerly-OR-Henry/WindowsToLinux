package gold.debug.windowstolinux.shared.linux.connection;

import java.security.KeyPair;
import java.util.Objects;

/**
 * In-memory authentication material supplied by the platform secret module.
 *
 * <p>由平台秘密模块提供的内存认证材料。
 */
public sealed interface SshCredential permits SshCredential.Password, SshCredential.PrivateKey {
    /**
     * Provides the {@code Password} implementation.
     *
     * <p>提供 {@code Password} 实现。
     */
    final class Password implements SshCredential {
        private final char[] value;

        /**
         * Creates a {@code Password} instance.
         *
         * <p>创建 {@code Password} 实例。
         *
         * @param value the {@code value} value / {@code value} 值
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         */
        public Password(char[] value) {
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException("password must not be empty");
            }
            this.value = value.clone();
        }

        /**
         * Performs the {@code copy} operation.
         *
         * <p>执行 {@code copy} 操作。
         *
         * @return the operation result / 操作结果
         */
        public char[] copy() {
            return value.clone();
        }

        /**
         * Performs the {@code clear} operation.
         *
         * <p>执行 {@code clear} 操作。
         */
        public void clear() {
            java.util.Arrays.fill(value, '\0');
        }
    }

    /**
     * Represents an immutable {@code PrivateKey} value.
     *
     * <p>表示不可变的 {@code PrivateKey} 值。
     *
     * @param keyPair the {@code keyPair} value / {@code keyPair} 值
     */
    record PrivateKey(KeyPair keyPair) implements SshCredential {
        /**
         * Creates a {@code PrivateKey} instance.
         *
         * <p>创建 {@code PrivateKey} 实例。
         *
         * @param keyPair the {@code keyPair} value / {@code keyPair} 值
         * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
         */
        public PrivateKey {
            keyPair = Objects.requireNonNull(keyPair, "keyPair");
        }
    }
}

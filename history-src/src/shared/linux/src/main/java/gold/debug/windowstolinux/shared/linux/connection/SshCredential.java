package gold.debug.windowstolinux.shared.linux.connection;

import java.security.KeyPair;
import java.util.Objects;

/**
 * In-memory authentication material supplied by the platform secret module.
 *
 *  <p>由平台秘密模块提供的内存认证材料。
 */
public sealed interface SshCredential permits SshCredential.Password, SshCredential.PrivateKey {
    /**
     * Creates an independently owned credential for a bounded connection attempt. / 为有界连接尝试创建独立持有的凭据。
     *
     * @return an independently owned credential for a bounded connection attempt / 为有界连接尝试创建独立持有的凭据
     */
    default SshCredential duplicate() {
        return this;
    }

    /**
     * Clears mutable authentication material owned by this credential. / 清除此凭据持有的可变认证材料。
     */
    default void clear() {
        // Immutable private-key references do not expose mutable character material. / 不可变私钥引用不暴露可变字符材料。
    }

    /**
     * Owns a mutable SSH password buffer that must be cleared after connection use.
     * <p>持有可变 SSH 密码缓冲区，连接使用后必须清空。
     */
    final class Password implements SshCredential {
        /**
         * Candidate content accepted or rejected by this contract.
         * <p>由当前契约接收或拒绝的候选内容。
         */
        private final char[] value;

        /**
         * Validates and binds the inputs required by password.
         * <p>校验并绑定密码所需输入。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
         */
        public Password(char[] value) {
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException("password must not be empty");
            }
            this.value = value.clone();
        }

        /**
         * Copies char.
         * <p>复制char。
         *
         * @return the operation result / 操作结果
         */
        public char[] copy() {
            return value.clone();
        }

        /**
         * Creates a separately clearable password credential. / 创建可单独清除的密码凭据。
         *
         * @return a separately clearable password credential / 可单独清除的密码凭据
         */
        @Override
        public SshCredential duplicate() {
            return new Password(value);
        }

        /**
         * Clears retained credential material after its scoped use.
         * <p>在限定作用域使用结束后清空保留的凭据素材。
         */
        @Override
        public void clear() {
            java.util.Arrays.fill(value, '\0');
        }
    }

    /**
     * Represents an immutable {@code PrivateKey} value.
     *
     *  <p>表示不可变的 {@code PrivateKey} 值。
     *
     * @param keyPair key pair / 键配对
     */
    record PrivateKey(KeyPair keyPair) implements SshCredential {
        /**
         * Validates and binds the inputs required by private key.
         * <p>校验并绑定私有键所需输入。
         *
         * @param keyPair key pair / 键配对
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public PrivateKey {
            keyPair = Objects.requireNonNull(keyPair, "keyPair");
        }
    }
}

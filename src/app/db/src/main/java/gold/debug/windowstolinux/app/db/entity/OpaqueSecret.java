package gold.debug.windowstolinux.app.db.entity;

import java.util.Arrays;
import java.util.Objects;

/**
 * Database-only encrypted material; this module never decrypts it.
 *
 * <p>仅供数据库保存的加密材料；本模块绝不解密它。
 *
 * @param key the {@code key} value / {@code key} 值
 * @param algorithm the {@code algorithm} value / {@code algorithm} 值
 * @param salt the {@code salt} value / {@code salt} 值
 * @param nonce the {@code nonce} value / {@code nonce} 值
 * @param ciphertext the {@code ciphertext} value / {@code ciphertext} 值
 */
public record OpaqueSecret(String key, String algorithm, byte[] salt, byte[] nonce, byte[] ciphertext) {
    /**
     * Creates a {@code OpaqueSecret} instance.
     *
     * <p>创建 {@code OpaqueSecret} 实例。
     *
     * @param key the {@code key} value / {@code key} 值
     * @param algorithm the {@code algorithm} value / {@code algorithm} 值
     * @param salt the {@code salt} value / {@code salt} 值
     * @param nonce the {@code nonce} value / {@code nonce} 值
     * @param ciphertext the {@code ciphertext} value / {@code ciphertext} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public OpaqueSecret {
        key = Objects.requireNonNull(key, "key");
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        salt = copy(salt, "salt");
        nonce = copy(nonce, "nonce");
        ciphertext = copy(ciphertext, "ciphertext");
    }

    /**
     * Returns a defensive copy of the salt.
     *
     * <p>返回盐值的防御性副本。
     *
     * @return a copy of the salt / 盐值副本
     */
    @Override
    public byte[] salt() {
        return salt.clone();
    }

    /**
     * Returns a defensive copy of the nonce.
     *
     * <p>返回随机数的防御性副本。
     *
     * @return a copy of the nonce / 随机数副本
     */
    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    /**
     * Returns a defensive copy of the ciphertext.
     *
     * <p>返回密文的防御性副本。
     *
     * @return a copy of the ciphertext / 密文副本
     */
    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    private static byte[] copy(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Arrays.copyOf(value, value.length);
    }
}

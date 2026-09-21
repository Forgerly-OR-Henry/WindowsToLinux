package gold.debug.windowstolinux.app.db.entity;

import java.util.Arrays;
import java.util.Objects;

/**
 * Database-only encrypted material; this module never decrypts it.
 *
 *  <p>仅供数据库保存的加密材料；本模块绝不解密它。
 *
 * @param key lookup key within the current contract / 当前契约内的查找键
 * @param algorithm algorithm / 算法
 * @param salt salt / 盐
 * @param nonce nonce / 随机数
 * @param ciphertext ciphertext / 密文
 */
public record OpaqueSecret(String key, String algorithm, byte[] salt, byte[] nonce, byte[] ciphertext) {
    /**
     * Validates and binds the inputs required by opaque secret.
     * <p>校验并绑定不透明秘密所需输入。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param algorithm algorithm / 算法
     * @param salt salt / 盐
     * @param nonce nonce / 随机数
     * @param ciphertext ciphertext / 密文
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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
     *  <p>返回盐值的防御性副本。
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
     *  <p>返回随机数的防御性副本。
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
     *  <p>返回密文的防御性副本。
     *
     * @return a copy of the ciphertext / 密文副本
     */
    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /**
     * Copies opaque secret.
     * <p>复制不透明秘密。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static byte[] copy(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Arrays.copyOf(value, value.length);
    }
}

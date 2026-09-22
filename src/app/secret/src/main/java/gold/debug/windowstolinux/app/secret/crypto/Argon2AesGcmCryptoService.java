package gold.debug.windowstolinux.app.secret.crypto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Derives encryption keys with Argon2 and authenticates secret ciphertext with AES-GCM.
 * <p>使用 Argon2 派生加密密钥，并通过 AES-GCM 认证秘密密文。
 */
public final class Argon2AesGcmCryptoService implements AutoCloseable {
    /**
     * SALT BYTES.
     * <p>盐字节。
     */
    private static final int SALT_BYTES = 16;

    /**
     * NONCE BYTES.
     * <p>随机数字节。
     */
    private static final int NONCE_BYTES = 12;

    /**
     * Master-password buffer used to unlock protected credentials.
     * <p>用于解锁受保护凭据的主密码缓冲区。
     */
    private final char[] masterPassword;

    /**
     * Random.
     * <p>随机。
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * Validates and binds the inputs required by argon 2 aes gcm crypto service.
     * <p>校验并绑定Argon2AesGcm加密服务所需输入。
     *
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public Argon2AesGcmCryptoService(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length < 12) {
            throw new IllegalArgumentException("master password must contain at least 12 characters");
        }
        this.masterPassword = masterPassword.clone();
    }

    /**
     * Encrypts secret content with authenticated protection under the selected key contract.
     * <p>按所选密钥契约对秘密内容进行认证加密保护。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return the operation result / 操作结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public EncryptedPayload encrypt(char[] value) throws Exception {
        byte[] plaintext = toUtf8(value);
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] key = deriveKey(salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new EncryptedPayload(salt, nonce, cipher.doFinal(plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Authenticates encrypted content before returning decrypted secret material.
     * <p>在返回解密秘密素材前认证加密内容。
     *
     * @param secret secret / 秘密
     * @return the operation result / 操作结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public char[] decrypt(OpaqueSecret secret) throws Exception {
        byte[] key = deriveKey(secret.salt());
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, secret.nonce()));
            plaintext = cipher.doFinal(secret.ciphertext());
            return new String(plaintext, StandardCharsets.UTF_8).toCharArray();
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * Derives the encryption key from the supplied password and salt parameters.
     * <p>根据提供的密码及盐参数派生加密密钥。
     *
     * @param salt salt / 盐
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private byte[] deriveKey(byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withSalt(salt)
                .withIterations(3).withMemoryAsKB(64 * 1024).withParallelism(1).build();
        byte[] key = new byte[32];
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        generator.generateBytes(masterPassword, key);
        return key;
    }

    /**
     * Fills a new buffer with cryptographically secure random bytes.
     * <p>用密码学安全随机字节填充新缓冲区。
     *
     * @param size size / 大小
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * Converts the current contract to utf 8.
     * <p>将当前契约转换为Utf8。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private static byte[] toUtf8(char[] value) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] copy = new byte[bytes.remaining()];
        bytes.get(copy);
        return copy;
    }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public void close() {
        Arrays.fill(masterPassword, '\0');
    }

    /**
     * Represents an immutable {@code EncryptedPayload} value.
     *
     *  <p>表示不可变的 {@code EncryptedPayload} 值。
     *
     * @param salt salt / 盐
     * @param nonce nonce / 随机数
     * @param ciphertext ciphertext / 密文
     */
    public record EncryptedPayload(byte[] salt, byte[] nonce, byte[] ciphertext) {
        /**
         * Binds the supplied dependencies and state for encrypted payload.
         * <p>为加密载荷绑定传入的依赖及状态。
         *
         * @param salt salt / 盐
         * @param nonce nonce / 随机数
         * @param ciphertext ciphertext / 密文
         */
        public EncryptedPayload {
            salt = salt.clone();
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
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
    }
}

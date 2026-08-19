package gold.debug.windowstolinux.app.secret.crypto;

import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Provides the {@code Argon2AesGcmCryptoService} implementation.
 *
 * <p>提供 {@code Argon2AesGcmCryptoService} 实现。
 */
public final class Argon2AesGcmCryptoService implements AutoCloseable {
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private final char[] masterPassword;
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates a {@code Argon2AesGcmCryptoService} instance.
     *
     * <p>创建 {@code Argon2AesGcmCryptoService} 实例。
     *
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public Argon2AesGcmCryptoService(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length < 12) {
            throw new IllegalArgumentException("master password must contain at least 12 characters");
        }
        this.masterPassword = masterPassword.clone();
    }

    /**
     * Performs the {@code encrypt} operation.
     *
     * <p>执行 {@code encrypt} 操作。
     *
     * @param value the {@code value} value / {@code value} 值
     * @return the operation result / 操作结果
     * @throws Exception if the operation cannot be completed / 无法完成操作时
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
     * Performs the {@code decrypt} operation.
     *
     * <p>执行 {@code decrypt} 操作。
     *
     * @param secret the {@code secret} value / {@code secret} 值
     * @return the operation result / 操作结果
     * @throws Exception if the operation cannot be completed / 无法完成操作时
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

    private byte[] deriveKey(byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withIterations(3).withMemoryAsKB(64 * 1024).withParallelism(1).build();
        byte[] key = new byte[32];
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        generator.generateBytes(masterPassword, key);
        return key;
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static byte[] toUtf8(char[] value) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] copy = new byte[bytes.remaining()];
        bytes.get(copy);
        return copy;
    }

    /** Closes this resource. / 关闭此资源。 */
    @Override
    public void close() {
        Arrays.fill(masterPassword, '\0');
    }

    /**
     * Represents an immutable {@code EncryptedPayload} value.
     *
     * <p>表示不可变的 {@code EncryptedPayload} 值。
     *
     * @param salt the {@code salt} value / {@code salt} 值
     * @param nonce the {@code nonce} value / {@code nonce} 值
     * @param ciphertext the {@code ciphertext} value / {@code ciphertext} 值
     */
    public record EncryptedPayload(byte[] salt, byte[] nonce, byte[] ciphertext) {
        /**
         * Creates a {@code EncryptedPayload} instance.
         *
         * <p>创建 {@code EncryptedPayload} 实例。
         *
         * @param salt the {@code salt} value / {@code salt} 值
         * @param nonce the {@code nonce} value / {@code nonce} 值
         * @param ciphertext the {@code ciphertext} value / {@code ciphertext} 值
         */
        public EncryptedPayload {
            salt = salt.clone();
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        /**
         * Returns a defensive copy of the salt.
         *
         * <p>返回盐值的防御性副本。
         *
         * @return a copy of the salt / 盐值副本
         */
        @Override public byte[] salt() { return salt.clone(); }

        /**
         * Returns a defensive copy of the nonce.
         *
         * <p>返回随机数的防御性副本。
         *
         * @return a copy of the nonce / 随机数副本
         */
        @Override public byte[] nonce() { return nonce.clone(); }

        /**
         * Returns a defensive copy of the ciphertext.
         *
         * <p>返回密文的防御性副本。
         *
         * @return a copy of the ciphertext / 密文副本
         */
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
}

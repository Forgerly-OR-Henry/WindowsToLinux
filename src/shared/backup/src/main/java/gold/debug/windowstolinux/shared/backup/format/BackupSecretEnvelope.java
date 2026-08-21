package gold.debug.windowstolinux.shared.backup.format;

import java.util.Objects;

/** Portable authenticated-encryption envelope for the opaque {@code secrets.enc} member. / 不透明 {@code secrets.enc} 成员的可移植认证加密信封。 */
public record BackupSecretEnvelope(
        String format,
        String keyDerivation,
        int memoryKiB,
        int iterations,
        int parallelism,
        byte[] salt,
        String cipher,
        byte[] nonce,
        byte[] ciphertext
) {
    /** Current secret-envelope format. / 当前秘密信封格式。 */
    public static final String CURRENT_FORMAT = "windowstolinux-secrets";
    /** Current key derivation identifier. / 当前密钥派生标识。 */
    public static final String CURRENT_KEY_DERIVATION = "Argon2id";
    /** Current authenticated cipher identifier. / 当前认证密码标识。 */
    public static final String CURRENT_CIPHER = "AES-256-GCM";

    /** Validates bounded public parameters and defensively copies binary values. / 校验有界公开参数并防御性复制二进制值。 */
    public BackupSecretEnvelope {
        format = Objects.requireNonNull(format, "format");
        keyDerivation = Objects.requireNonNull(keyDerivation, "keyDerivation");
        cipher = Objects.requireNonNull(cipher, "cipher");
        if (!CURRENT_FORMAT.equals(format) || !CURRENT_KEY_DERIVATION.equals(keyDerivation)
                || !CURRENT_CIPHER.equals(cipher)) {
            throw new IllegalArgumentException("unsupported backup secret envelope");
        }
        if (memoryKiB < 32 * 1024 || memoryKiB > 1024 * 1024 || iterations < 1 || iterations > 16
                || parallelism < 1 || parallelism > 16) {
            throw new IllegalArgumentException("backup key derivation parameters are outside safe bounds");
        }
        salt = Objects.requireNonNull(salt, "salt").clone();
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        if (salt.length != 16 || nonce.length != 12 || ciphertext.length < 16) {
            throw new IllegalArgumentException("backup secret envelope has invalid binary lengths");
        }
    }

    @Override public byte[] salt() { return salt.clone(); }
    @Override public byte[] nonce() { return nonce.clone(); }
    @Override public byte[] ciphertext() { return ciphertext.clone(); }
}

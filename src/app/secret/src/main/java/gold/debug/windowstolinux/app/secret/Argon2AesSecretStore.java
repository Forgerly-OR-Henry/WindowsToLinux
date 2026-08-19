package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.app.db.repository.EncryptedSecretRepository;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.crypto.Argon2AesGcmCryptoService;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed secret store using the crypto package for Argon2id and AES-GCM.
 *
 * <p>使用加密包实现 Argon2id 与 AES-GCM 的数据库秘密存储。
 */
public final class Argon2AesSecretStore implements SecretStore {
    private static final String ALGORITHM = "ARGON2ID-AES-256-GCM-V1";
    private final EncryptedSecretRepository secrets;
    private final Argon2AesGcmCryptoService crypto;

    /**
     * Creates a {@code Argon2AesSecretStore} instance.
     *
     * <p>创建 {@code Argon2AesSecretStore} 实例。
     *
     * @param secrets the {@code secrets} value / {@code secrets} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public Argon2AesSecretStore(EncryptedSecretRepository secrets, char[] masterPassword) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.crypto = new Argon2AesGcmCryptoService(masterPassword);
    }

    /** Performs the {@code save} operation. / 执行 {@code save} 操作。 */
    @Override
    public void save(String key, char[] value) throws SecretStoreException {
        validateKey(key);
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("secret value must not be empty");
        }
        try {
            Argon2AesGcmCryptoService.EncryptedPayload encrypted = crypto.encrypt(value);
            secrets.save(new OpaqueSecret(
                    key, ALGORITHM, encrypted.salt(), encrypted.nonce(), encrypted.ciphertext()));
        } catch (Exception exception) {
            throw failure("secret.encryptFailed", "Failed to encrypt and store the credential", exception);
        }
    }

    /** Performs the {@code read} operation. / 执行 {@code read} 操作。 */
    @Override
    public Optional<char[]> read(String key) throws SecretStoreException {
        validateKey(key);
        try {
            Optional<OpaqueSecret> stored = secrets.find(key);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            OpaqueSecret secret = stored.orElseThrow();
            if (!ALGORITHM.equals(secret.algorithm())) {
                throw failure("secret.versionUnsupported", "The stored credential encryption version is unsupported");
            }
            return Optional.of(crypto.decrypt(secret));
        } catch (SQLException exception) {
            throw failure("secret.readFailed", "Failed to read the encrypted credential", exception);
        } catch (SecretStoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("secret.decryptFailed", "The master password is incorrect or the credential is corrupted",
                    exception);
        }
    }

    /** Closes this resource. / 关闭此资源。 */
    @Override
    public void close() {
        crypto.close();
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9/_-]{0,127}")) {
            throw new IllegalArgumentException("secret key is invalid");
        }
    }

    private static SecretStoreException failure(String key, String diagnostic) {
        return new SecretStoreException(LocalizedMessage.of(key), diagnostic);
    }

    private static SecretStoreException failure(String key, String diagnostic, Throwable cause) {
        return new SecretStoreException(LocalizedMessage.of(key), diagnostic, cause);
    }
}

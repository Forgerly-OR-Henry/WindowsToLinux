package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.app.db.persistence.repository.EncryptedSecretRepository;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.crypto.Argon2AesGcmCryptoService;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed secret store using the crypto package for Argon2id and AES-GCM.
 *
 *  <p>使用加密包实现 Argon2id 与 AES-GCM 的数据库秘密存储。
 */
public final class Argon2AesSecretStore implements SecretStore {
    /**
     * Persisted identifier of the Argon2id and AES-GCM envelope format.
     * <p>Argon2id 及 AES-GCM 信封格式的持久化标识。
     */
    private static final String ALGORITHM = "ARGON2ID-AES-256-GCM-V1";
    /**
     * Bound encrypted secret repository collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的加密秘密仓库协作对象。
     */
    private final EncryptedSecretRepository secrets;
    /**
     * Bound argon 2 aes gcm crypto service collaborator for crypto.
     * <p>处理加密的Argon2AesGcm加密服务协作对象。
     */
    private final Argon2AesGcmCryptoService crypto;

    /**
     * Validates and binds the inputs required by argon 2 aes secret store.
     * <p>校验并绑定Argon2Aes秘密存储所需输入。
     *
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public Argon2AesSecretStore(EncryptedSecretRepository secrets, char[] masterPassword) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.crypto = new Argon2AesGcmCryptoService(masterPassword);
    }

    /**
     * Persists argon 2 aes secret store.
     * <p>持久化Argon2Aes秘密存储。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
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
            throw failure(SecretStoreFailureType.ENCRYPT_FAILED, "Failed to encrypt and store the credential", exception);
        }
    }

    /**
     * Reads optional.
     * <p>读取可选。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
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
                throw failure(SecretStoreFailureType.VERSION_UNSUPPORTED, "The stored credential encryption version is unsupported");
            }
            return Optional.of(crypto.decrypt(secret));
        } catch (SQLException exception) {
            throw failure(SecretStoreFailureType.READ_FAILED, "Failed to read the encrypted credential", exception);
        } catch (SecretStoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(SecretStoreFailureType.DECRYPT_FAILED, "The master password is incorrect or the credential is corrupted",
                    exception);
        }
    }

    /**
     * Deletes one exact encrypted database credential. / 删除一个精确的数据库加密凭据。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return true when deletes one exact encrypted database credential, false otherwise / 删除一个精确的数据库加密凭据时为 true，否则为 false
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    @Override
    public boolean delete(String key) throws SecretStoreException {
        validateKey(key);
        try {
            return secrets.delete(key);
        } catch (SQLException exception) {
            throw failure(SecretStoreFailureType.DELETE_FAILED,
                    "Failed to delete the encrypted credential", exception);
        }
    }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public void close() {
        crypto.close();
    }

    /**
     * Validates lookup key within the current contract and rejects inputs outside the declared constraints.
     * <p>校验当前契约内的查找键并拒绝超出已声明约束的输入。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9/_-]{0,127}")) {
            throw new IllegalArgumentException("secret key is invalid");
        }
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static SecretStoreException failure(SecretStoreFailureType type, String diagnostic) {
        return SecretStoreException.create(type, diagnostic);
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static SecretStoreException failure(SecretStoreFailureType type, String diagnostic, Throwable cause) {
        return SecretStoreException.create(type, diagnostic, cause);
    }
}

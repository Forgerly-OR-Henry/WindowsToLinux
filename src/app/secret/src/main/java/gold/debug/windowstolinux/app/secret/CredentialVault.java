package gold.debug.windowstolinux.app.secret;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.security.StoredCredential;
import gold.debug.windowstolinux.app.db.security.StoredSecurityProfile;
import gold.debug.windowstolinux.app.db.security.SecurityRepository;
import gold.debug.windowstolinux.app.secret.crypto.PasswordKeyCrypto;

/** Unified database vault with reference-counted, memory-only key handles. / 使用仅内存引用计数密钥句柄的统一数据库凭据库。 */
public final class CredentialVault {
    /** Persistence boundary for profiles and credentials. / 配置及凭据的持久化边界。
     */
    private final SecurityRepository repository;

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param repository repository supplied to this boundary / 传入当前边界的repository值
     */
    public CredentialVault(SecurityRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /** Checks whether the database contains the singleton security profile. / 检查数据库是否已包含唯一安全配置。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public boolean configured() throws SecretStoreException {
        try {
            return repository.profile().isPresent();
        } catch (SQLException failure) {
            throw failure("security profile unavailable", failure);
        }
    }

    /** Reads the global credential storage policy, defaulting to encryption before setup. / 读取全局凭据策略，首次配置前默认加密。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public boolean encryptionEnabled() throws SecretStoreException {
        try {
            return repository.profile().map(StoredSecurityProfile::encryptionEnabled).orElse(true);
        } catch (SQLException failure) {
            throw failure("security profile unavailable", failure);
        }
    }

    /** Creates the singleton security profile for an unconfigured database. / 为尚未配置的数据库创建唯一安全配置。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Session initialize(char[] password) throws SecretStoreException {
        byte[] dataKey = PasswordKeyCrypto.random(32);
        try {
            repository.initialize(PasswordKeyCrypto.wrap(password, dataKey, true));
            return new Session(new KeyMaterial(dataKey));
        } catch (Exception failure) {
            throw failure("security initialization failed", failure);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            clear(password);
        }
    }

    /** Verifies the password and opens a session without exposing the data key. / 验证密码并建立会话，不暴露数据密钥。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Session unlock(char[] password) throws SecretStoreException {
        byte[] key = null;
        try {
            key = PasswordKeyCrypto.unlock(profile(), password);
            return new Session(new KeyMaterial(key));
        } catch (Exception failure) {
            throw failure("master password or security profile is invalid", failure);
        } finally {
            if (key != null)
                Arrays.fill(key, (byte) 0);
            clear(password);
        }
    }

    /** Rewraps the existing data key after password verification. / 在密码验证后重新包装已有数据密钥。
     * @param current current master-password buffer / 当前主密码缓冲区
     * @param replacement new master-password buffer / 新主密码缓冲区
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void changePassword(char[] current, char[] replacement) throws SecretStoreException {
        byte[] key = null;
        try {
            var profile = profile();
            key = PasswordKeyCrypto.unlock(profile, current);
            var updated = PasswordKeyCrypto.wrap(replacement, key, profile.encryptionEnabled());
            repository.transaction(() -> repository.updateProfile(updated));
        } catch (Exception failure) {
            throw failure("master password change failed", failure);
        } finally {
            if (key != null)
                Arrays.fill(key, (byte) 0);
            clear(current);
            clear(replacement);
        }
    }

    /** Converts every credential and updates its policy atomically. / 原子转换每条凭据并更新存储策略。
     * @param current current master-password buffer / 当前主密码缓冲区
     * @param enabled requested enabled flag / 请求启用标记
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void setEncryption(char[] current, boolean enabled) throws SecretStoreException {
        byte[] key = null;
        try {
            var original = profile();
            key = PasswordKeyCrypto.unlock(original, current);
            byte[] operationKey = key;
            repository.transaction(() -> {
                if (original.encryptionEnabled() != enabled) {
                    for (String id : repository.credentialIds()) {
                        char[] value = null;
                        try (var stored = repository.find(id).orElseThrow()) {
                            value = PasswordKeyCrypto.decode(stored, operationKey, original.encryptionEnabled());
                            try (var updated = PasswordKeyCrypto.encode(id, value, operationKey, enabled)) {
                                repository.save(updated, false);
                            }
                        } catch (java.security.GeneralSecurityException failure) {
                            throw new SQLException("credential conversion failed", failure);
                        } finally {
                            clear(value);
                        }
                    }
                    repository.updateProfile(original.withEncryption(enabled));
                }
            });
        } catch (Exception failure) {
            throw failure("credential storage conversion failed", failure);
        } finally {
            if (key != null)
                Arrays.fill(key, (byte) 0);
            clear(current);
        }
    }

    /** Loads the singleton security profile. / 读取唯一安全配置。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws Exception if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private StoredSecurityProfile profile() throws Exception {
        var profile = repository.profile()
            .orElseThrow(() -> new SQLException("master password has not been configured"));
        PasswordKeyCrypto.validateProfile(profile);
        return profile;
    }

    /** Reference-counted in-memory key shared by scoped handles. / 限定句柄共享的引用计数内存密钥。
     */
    private static final class KeyMaterial {
        /** Sensitive in-memory data key. / 敏感内存数据密钥。
         */
        private final byte[] key;

        /** Number of live key handles. / 存活密钥句柄数。
         */
        private int references;

        /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
         * @param key key supplied to this boundary / 传入当前边界的key值
         */
        private KeyMaterial(byte[] key) {
            this.key = key.clone();
        }
    }

    /** A scoped secret capability; closing the final handle clears the key. / 限定秘密能力，最后一个句柄关闭时清理密钥。 */
    public final class Session implements SecretStore {
        /** Shared reference-counted key material. / 共享引用计数密钥材料。
         */
        private final KeyMaterial material;

        /** Whether this resource has been released. / 当前资源是否已释放。
         */
        private boolean closed;

        /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
         * @param material material supplied to this boundary / 传入当前边界的material值
         */
        private Session(KeyMaterial material) {
            this.material = material;
            synchronized (material) {
                material.references++;
            }
        }

        /** Retains an independent handle to the same in-memory key. / 保留指向同一内存密钥的独立句柄。
         * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
         */
        public Session fork() {
            synchronized (material) {
                requireOpen();
                return new Session(material);
            }
        }

        /** Reads and validates one persisted record by its identifier. / 按标识读取并验证一条持久化记录。
         * @param id stable persisted identifier / 稳定持久化标识
         * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
         * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
         */
        @Override
        public Optional<char[]> read(String id) throws SecretStoreException {
            synchronized (material) {
                requireOpen();
                validateId(id);
                try {
                    var found = repository.find(id);
                    if (found.isEmpty())
                        return Optional.empty();
                    try (var record = found.get()) {
                        return Optional
                            .of(PasswordKeyCrypto.decode(record, material.key, profile().encryptionEnabled()));
                    }
                } catch (Exception failure) {
                    throw failure("credential cannot be read", failure);
                }
            }
        }

        /** Validates and persists the supplied state in its transaction boundary. / 在对应事务边界内验证并保存传入状态。
         * @param id stable persisted identifier / 稳定持久化标识
         * @param value value supplied to this boundary / 传入当前边界的value值
         * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
         */
        @Override
        public void save(String id, char[] value) throws SecretStoreException {
            synchronized (material) {
                requireOpen();
                validateId(id);
                try {
                    repository.transaction(() -> {
                        char[] previous = null;
                        try {
                            var profile = profile();
                            var found = repository.find(id);
                            if (found.isPresent())
                                try (var record = found.get()) {
                                    previous = PasswordKeyCrypto.decode(record, material.key,
                                        profile.encryptionEnabled());
                                }
                            if (previous != null && Arrays.equals(previous, value))
                                return;
                            if (previous != null && repository.isVersioned(id))
                                throw new SQLException("business secret revisions are immutable");
                            try (var record = PasswordKeyCrypto.encode(id, value, material.key,
                                profile.encryptionEnabled())) {
                                repository.save(record, previous != null);
                            }
                        } catch (Exception failure) {
                            if (failure instanceof SQLException sql)
                                throw sql;
                            throw new SQLException("credential write failed", failure);
                        } finally {
                            clear(previous);
                        }
                    });
                } catch (Exception failure) {
                    throw failure("credential cannot be saved", failure);
                }
            }
        }

        /** Deletes an unreferenced credential while preserving foreign-key protection. / 删除未被引用的凭据并保留外键保护。
         * @param id stable persisted identifier / 稳定持久化标识
         * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
         * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
         */
        @Override
        public boolean delete(String id) throws SecretStoreException {
            synchronized (material) {
                requireOpen();
                validateId(id);
                try {
                    return repository.delete(id);
                } catch (SQLException failure) {
                    throw failure("credential cannot be deleted while referenced", failure);
                }
            }
        }

        /** Runs related repository and credential writes on one database transaction. / 在同一数据库事务中执行关联仓库及凭据写入。
         * @param work operation executed within the retained boundary / 保留边界内执行的操作
         * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
         * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
         */
        @Override
        public void transaction(StoreWork work) throws SecretStoreException, SQLException {
            synchronized (material) {
                requireOpen();
                try {
                    repository.transaction(() -> {
                        try {
                            work.run();
                        } catch (SecretStoreException failure) {
                            throw new CredentialWriteException(failure);
                        }
                    });
                } catch (CredentialWriteException failure) {
                    throw (SecretStoreException) failure.getCause();
                }
            }
        }

        /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
         */
        @Override
        public void close() {
            synchronized (material) {
                if (!closed) {
                    closed = true;
                    if (--material.references == 0)
                        Arrays.fill(material.key, (byte) 0);
                }
            }
        }

        /** Rejects access through a released capability. / 拒绝通过已释放的能力进行访问。
         */
        private void requireOpen() {
            if (closed)
                throw new IllegalStateException("credential session is closed");
        }
    }

    /** Preserves a credential failure across the SQL transaction callback. / 在 SQL 事务回调中保留凭据失败。
     */
    private static final class CredentialWriteException extends SQLException {
        /** Exception serialization version. / 异常序列化版本。
         */
        /** Preserves the credential error classification across transaction rollback. / 在事务回滚中保留凭据错误分类。
         * @return original structured credential failure / 原始结构化凭据失败
         */
        public gold.debug.windowstolinux.appapi.task.Problem problem() {
            return ((SecretStoreException) getCause()).problem();
        }

        /** Exception serialization version. / 异常序列化版本。
         */
        private static final long serialVersionUID = 1L;

        /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
         * @param cause cause supplied to this boundary / 传入当前边界的cause值
         */
        private CredentialWriteException(SecretStoreException cause) {
            super("credential transaction failed", cause);
        }
    }

    /** Rejects empty or unsupported credential identifiers. / 拒绝空白或格式不受支持的凭据标识。
     * @param id stable persisted identifier / 稳定持久化标识
     */
    private static void validateId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9/_:-]{0,239}"))
            throw new IllegalArgumentException("invalid credential identifier");
    }

    /** Overwrites the caller-owned sensitive buffer when present. / 覆盖调用方持有的非空敏感缓冲区。
     * @param value value supplied to this boundary / 传入当前边界的value值
     */
    private static void clear(char[] value) {
        if (value != null)
            Arrays.fill(value, '\0');
    }

    /** Maps a vault failure to a safe structured error. / 将凭据库失败映射为安全的结构化错误。
     * @param message message supplied to this boundary / 传入当前边界的message值
     * @param cause cause supplied to this boundary / 传入当前边界的cause值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private static SecretStoreException failure(String message, Exception cause) {
        return SecretStoreException.create(SecretStoreFailureType.DECRYPT_FAILED, message, cause);
    }
}

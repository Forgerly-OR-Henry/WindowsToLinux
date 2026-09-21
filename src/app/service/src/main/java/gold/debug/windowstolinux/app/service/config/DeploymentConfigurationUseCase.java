package gold.debug.windowstolinux.app.service.config;

import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;

import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ConfigurationSnapshotRepository;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates immutable typed deployment configuration and platform-secret references without returning secret values.
 *
 *  <p>协调不可变的部署配置和平台秘密引用，且不返回秘密值。
 */
public final class DeploymentConfigurationUseCase {
    /**
     * Bound configuration snapshot repository collaborator for configurations.
     * <p>处理配置集合的配置快照仓库协作对象。
     */
    private final ConfigurationSnapshotRepository configurations;
    /**
     * Bound application secret repository collaborator for application secrets.
     * <p>处理应用秘密集合的应用秘密仓库协作对象。
     */
    private final ApplicationSecretRepository applicationSecrets;
    /**
     * Bound desktop secret store service collaborator for secret stores.
     * <p>处理秘密存储集合的Desktop秘密存储服务协作对象。
     */
    private final DesktopSecretStoreService secretStores;

    /**
     * Validates and binds the inputs required by deployment configuration use case.
     * <p>校验并绑定部署配置用例所需输入。
     *
     * @param configurations configurations / 配置集合
     * @param applicationSecrets application secrets / 应用秘密集合
     * @param secretStores secret stores / 秘密存储集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentConfigurationUseCase(ConfigurationSnapshotRepository configurations,
                                          ApplicationSecretRepository applicationSecrets,
                                          DesktopSecretStoreService secretStores) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.applicationSecrets = Objects.requireNonNull(applicationSecrets, "applicationSecrets");
        this.secretStores = Objects.requireNonNull(secretStores, "secretStores");
    }

    /**
     * Persists a typed non-secret snapshot; an existing revision cannot be replaced.
     *
     *  <p>持久化类型化的非秘密快照；既有修订不可替换。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveSnapshot(ConfigurationSnapshot snapshot) throws SQLException {
        configurations.save(snapshot);
    }

    /**
     * Stores an immutable secret revision using a caller-owned platform store without returning a plaintext value.
     *
     *  <p>使用调用方持有的平台存储保存不可变秘密修订，且不返回明文值。
     *
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param store store / 存储
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void saveSecretRevision(StoredApplicationSecretRevision revision, SecretStore store, char[] value)
            throws SQLException, SecretStoreException {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(store, "store");
        if (value == null || value.length == 0) {
            throw SecretStoreException.create(SecretStoreFailureType.APPLICATION_VALUE_MISSING,
                    "Application secret values must not be empty");
        }
        try {
            var existing = applicationSecrets.findRevision(revision.reference());
            if (existing.isPresent() && !existing.orElseThrow().equals(revision)) {
                throw new SQLException("application secret revisions are immutable");
            }
            char[] stored = store.read(revision.credentialKey()).orElse(null);
            try {
                if (stored != null) {
                    throw new SQLException("application secret revisions cannot overwrite an existing platform secret");
                }
            } finally {
                clear(stored);
            }
            applicationSecrets.saveRevision(revision);
            store.save(revision.credentialKey(), value);
        } finally {
            clear(value);
        }
    }

    /**
     * Parses user input and creates immutable storage metadata in the service. / 在服务内解析用户输入并创建不可变存储元数据。
     *
     * @param referenceInput reference input / 引用输入
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return user input and creates immutable storage metadata in the service / 在服务内解析用户输入并创建不可变存储元数据
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public SecretReference saveSecretRevision(String referenceInput, gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode,
            char[] masterPassword, char[] value) throws SQLException, SecretStoreException {
        try {
            var references = gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.secrets(referenceInput);
            if (references.size() != 1) throw new IllegalArgumentException("one exact secret revision is required");
            var reference = references.getFirst();
            var revision = new StoredApplicationSecretRevision(reference,
                    "application-secret/" + reference.identifier() + "/" + reference.revision(), mode, java.time.Instant.now());
            saveSecretRevision(revision, mode, masterPassword, value);
            return reference;
        } finally { clear(masterPassword); clear(value); }
    }

    /**
     * Stores one secret revision through the selected desktop-backed secret store. / 通过选定的桌面秘密存储保存一个秘密修订。
     *
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public void saveSecretRevision(StoredApplicationSecretRevision revision, gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode,
                                   char[] masterPassword, char[] value) throws SQLException, SecretStoreException {
        try (SecretStore store = secretStores.open(mode, masterPassword)) {
            saveSecretRevision(revision, store, value);
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Opens the selected platform stores only long enough to verify that every referenced revision remains readable.
     *
     *  <p>仅短暂打开所选平台存储，以验证每个被引用修订仍可读取。
     *
     * @param references references / 引用集合
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void verifySecretReferences(List<SecretReference> references, char[] masterPassword)
            throws SQLException, SecretStoreException {
        try {
            for (SecretReference reference : List.copyOf(Objects.requireNonNull(references, "references"))) {
                StoredApplicationSecretRevision revision = applicationSecrets.findRevision(reference)
                        .orElseThrow(() -> SecretStoreException.create(SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                "Application secret revision metadata is missing"));
                try (SecretStore store = secretStores.open(revision.credentialMode(), masterPassword)) {
                    char[] stored = store.read(revision.credentialKey())
                            .orElseThrow(() -> SecretStoreException.create(SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                    "Application secret revision is not available from the selected platform store"));
                    clear(stored);
                }
            }
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Verifies referenced revisions before creating an immutable release binding used by future rollback.
     *
     *  <p>在创建供未来回滚使用的不可变发布绑定前验证引用修订。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param references references / 引用集合
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public void bindReleaseSecrets(String applicationId, String releaseIdentity, List<SecretReference> references,
                                   char[] masterPassword) throws SQLException, SecretStoreException {
        verifySecretReferences(references, masterPassword);
        applicationSecrets.bindRelease(applicationId, releaseIdentity, references);
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}

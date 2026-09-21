package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Registers authenticated restored revisions without overwriting a different platform secret. / 注册已认证恢复修订且不覆盖不同的平台秘密。
 */
final class RestoredSecretRegistrar {
    /**
     * Bound application secret repository collaborator for metadata.
     * <p>处理元数据的应用秘密仓库协作对象。
     */
    private final ApplicationSecretRepository metadata;
    /**
     * Bound desktop secret store service collaborator for stores.
     * <p>处理存储集合的Desktop秘密存储服务协作对象。
     */
    private final DesktopSecretStoreService stores;

    /**
     * Validates and binds the inputs required by restored secret registrar.
     * <p>校验并绑定已恢复秘密Registrar所需输入。
     *
     * @param metadata metadata / 元数据
     * @param stores stores / 存储集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    RestoredSecretRegistrar(ApplicationSecretRepository metadata, DesktopSecretStoreService stores) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    /**
     * Registers restored secret revisions through the selected credential store and persistent revision metadata.
     * <p>通过所选凭据存储及持久化修订元数据登记已恢复秘密修订。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param revisions revisions / 修订集合
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param createdAt instant at which this record was created / 当前记录创建时刻
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    void register(String applicationId, List<ResolvedSecretRevision> revisions, CredentialStorageMode mode,
                  char[] masterPassword, Instant createdAt) throws SQLException, SecretStoreException {
        try {
            for (ResolvedSecretRevision revision : revisions) {
                var existing = metadata.findRevision(revision.reference());
                CredentialStorageMode selected = existing.map(StoredApplicationSecretRevision::credentialMode)
                        .orElse(mode);
                try (SecretStore store = stores.open(selected, masterPassword)) {
                    register(applicationId, revision, selected, createdAt, store, existing.orElse(null));
                }
            }
        } finally {
            if (masterPassword != null) Arrays.fill(masterPassword, '\0');
        }
    }

    /**
     * Registers restored secret revisions through the selected credential store and persistent revision metadata.
     * <p>通过所选凭据存储及持久化修订元数据登记已恢复秘密修订。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param createdAt instant at which this record was created / 当前记录创建时刻
     * @param store store / 存储
     * @param existing existing / 既有
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    private void register(String applicationId, ResolvedSecretRevision revision, CredentialStorageMode mode,
                          Instant createdAt, SecretStore store, StoredApplicationSecretRevision existing)
            throws SQLException, SecretStoreException {
        String key = existing == null ? "application/" + applicationId + "/" + key(revision)
                : existing.credentialKey();
        StoredApplicationSecretRevision expected = new StoredApplicationSecretRevision(
                revision.reference(), key, mode, createdAt);
        char[] stored = store.read(key).orElse(null);
        try {
            if (stored != null) {
                try (ResolvedSecretRevision comparison = new ResolvedSecretRevision(revision.reference(), stored)) {
                    if (!comparison.digest().equals(revision.digest())) {
                        throw new SQLException("restored secret revision conflicts with an existing platform value");
                    }
                }
            } else {
                char[] value = revision.copyCharacters();
                try { store.save(key, value); } finally { Arrays.fill(value, '\0'); }
            }
            if (existing == null) {
                try {
                    metadata.saveRevision(expected);
                } catch (SQLException | RuntimeException exception) {
                    try { store.delete(key); } catch (SecretStoreException cleanup) { exception.addSuppressed(cleanup); }
                    throw exception;
                }
            }
        } finally {
            if (stored != null) Arrays.fill(stored, '\0');
        }
    }

    /**
     * Derives a stable SHA-256 storage key from the secret identifier and revision.
     * <p>根据秘密标识及修订派生稳定的 SHA-256 存储键。
     *
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @return key text / 键文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String key(ResolvedSecretRevision revision) {
        try {
            String identity = revision.reference().identifier() + "\0" + revision.reference().revision();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}

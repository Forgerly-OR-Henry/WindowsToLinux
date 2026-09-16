package gold.debug.windowstolinux.app.service.config;

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
 * <p>协调不可变的部署配置和平台秘密引用，且不返回秘密值。
 */
public final class DeploymentConfigurationUseCase {
    private final ConfigurationSnapshotRepository configurations;
    private final ApplicationSecretRepository applicationSecrets;
    private final DesktopSecretStoreService secretStores;

    /**
     * Creates a {@code DeploymentConfigurationUseCase} instance.
     *
     * <p>创建 {@code DeploymentConfigurationUseCase} 实例。
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
     * <p>持久化类型化的非秘密快照；既有修订不可替换。
     */
    public void saveSnapshot(ConfigurationSnapshot snapshot) throws SQLException {
        configurations.save(snapshot);
    }

    /**
     * Stores an immutable secret revision using a caller-owned platform store without returning a plaintext value.
     *
     * <p>使用调用方持有的平台存储保存不可变秘密修订，且不返回明文值。
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

    /** Parses user input and creates immutable storage metadata in the service. / 在服务内解析用户输入并创建不可变存储元数据。 */
    public SecretReference saveSecretRevision(String referenceInput, gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode,
            char[] masterPassword, char[] value) throws SQLException, SecretStoreException {
        try {
            var references = gold.debug.windowstolinux.app.service.config.DeploymentConfigurationParser.secrets(referenceInput);
            if (references.size() != 1) throw new IllegalArgumentException("one exact secret revision is required");
            var reference = references.getFirst();
            var revision = new StoredApplicationSecretRevision(reference,
                    "application-secret/" + reference.identifier() + "/" + reference.revision(), mode, java.time.Instant.now());
            saveSecretRevision(revision, mode, masterPassword, value);
            return reference;
        } finally { clear(masterPassword); clear(value); }
    }

    /** Stores one secret revision through the selected desktop-backed secret store. / 通过选定的桌面秘密存储保存一个秘密修订。 */
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
     * <p>仅短暂打开所选平台存储，以验证每个被引用修订仍可读取。
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
     * <p>在创建供未来回滚使用的不可变发布绑定前验证引用修订。
     */
    public void bindReleaseSecrets(String applicationId, String releaseIdentity, List<SecretReference> references,
                                   char[] masterPassword) throws SQLException, SecretStoreException {
        verifySecretReferences(references, masterPassword);
        applicationSecrets.bindRelease(applicationId, releaseIdentity, references);
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}

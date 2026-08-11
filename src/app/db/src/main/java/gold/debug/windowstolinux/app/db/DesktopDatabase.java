package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.migration.DesktopSchemaMigrator;
import gold.debug.windowstolinux.app.db.repository.DesktopRepository;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Stable desktop persistence facade; SQL and migrations remain internal collaborators.
 *
 * <p>稳定的桌面持久化门面；SQL 和迁移仍是内部协作者。
 */
public final class DesktopDatabase implements AutoCloseable {
    /**
     * Exposes the {@code UI_LOCALE_SETTING} constant.
     *
     * <p>公开 {@code UI_LOCALE_SETTING} 常量。
     */
    public static final String UI_LOCALE_SETTING = "ui.locale";
    /**
     * Exposes the {@code UI_THEME_SETTING} constant.
     *
     * <p>公开 {@code UI_THEME_SETTING} 常量。
     */
    public static final String UI_THEME_SETTING = "ui.theme";

    private final DesktopRepository repository;

    private DesktopDatabase(DesktopRepository repository) {
        this.repository = repository;
    }

    /**
     * Performs the {@code open} operation.
     *
     * <p>执行 {@code open} 操作。
     *
     * @param dataDirectory the {@code dataDirectory} value / {@code dataDirectory} 值
     * @return the operation result / 操作结果
     * @throws IOException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public static DesktopDatabase open(Path dataDirectory) throws IOException, SQLException {
        Path normalized = dataDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        DesktopConnectionFactory connections = new DesktopConnectionFactory(
                "jdbc:sqlite:" + normalized.resolve("windowstolinux.db"));
        DesktopSchemaMigrator.migrate(connections);
        return new DesktopDatabase(new DesktopRepository(connections));
    }

    /**
     * Stores data through {@code saveServer}.
     *
     * <p>通过 {@code saveServer} 保存数据。
     *
     * @param server the {@code server} value / {@code server} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveServer(ServerIdentity server) throws SQLException { repository.saveServer(server); }
    /**
     * Returns the value produced by {@code findServer}.
     *
     * <p>返回 {@code findServer} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ServerIdentity> findServer(String serverId) throws SQLException { return repository.findServer(serverId); }
    /**
     * Stores data through {@code saveDesktopPreference}.
     *
     * <p>通过 {@code saveDesktopPreference} 保存数据。
     *
     * @param name the {@code name} value / {@code name} 值
     * @param value the {@code value} value / {@code value} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveDesktopPreference(String name, String value) throws SQLException { repository.saveDesktopPreference(name, value); }
    /**
     * Returns the value produced by {@code findDesktopPreference}.
     *
     * <p>返回 {@code findDesktopPreference} 生成的值。
     *
     * @param name the {@code name} value / {@code name} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<String> findDesktopPreference(String name) throws SQLException { return repository.findDesktopPreference(name); }
    /**
     * Stores data through {@code saveServerProfile}.
     *
     * <p>通过 {@code saveServerProfile} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveServerProfile(StoredServerProfile profile) throws SQLException { repository.saveServerProfile(profile); }
    /**
     * Returns the value produced by {@code findServerProfile}.
     *
     * <p>返回 {@code findServerProfile} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<StoredServerProfile> findServerProfile(String serverId) throws SQLException { return repository.findServerProfile(serverId); }
    /**
     * Stores data through {@code saveAiProfile}.
     *
     * <p>通过 {@code saveAiProfile} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveAiProfile(StoredAiProfile profile) throws SQLException { repository.saveAiProfile(profile); }
    /**
     * Returns the value produced by {@code findAiProfile}.
     *
     * <p>返回 {@code findAiProfile} 生成的值。
     *
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<StoredAiProfile> findAiProfile() throws SQLException { return repository.findAiProfile(); }
    /**
     * Stores non-secret metadata for a named AI provider profile.
     *
     * <p>保存命名 AI 提供者配置的非秘密元数据。
     */
    public void saveAiProviderProfile(StoredAiProviderProfile profile) throws SQLException {
        repository.saveAiProviderProfile(profile);
    }
    /**
     * Lists named AI provider profiles without their credential values.
     *
     * <p>列出不含凭据值的命名 AI 提供者配置。
     */
    public List<StoredAiProviderProfile> listAiProviderProfiles() throws SQLException {
        return repository.listAiProviderProfiles();
    }
    /**
     * Stores one immutable normal configuration snapshot.
     *
     * <p>保存一个不可变普通配置快照。
     */
    public void saveConfigurationSnapshot(ConfigurationSnapshot snapshot) throws SQLException {
        repository.saveConfigurationSnapshot(snapshot);
    }
    /**
     * Finds one immutable normal configuration snapshot.
     *
     * <p>查找一个不可变普通配置快照。
     */
    public Optional<ConfigurationSnapshot> findConfigurationSnapshot(String applicationId, long revision) throws SQLException {
        return repository.findConfigurationSnapshot(applicationId, revision);
    }
    /**
     * Stores immutable application-secret metadata without accepting a secret value.
     *
     * <p>保存不可变应用秘密元数据，且不接收秘密值。
     */
    public void saveApplicationSecretRevision(StoredApplicationSecretRevision revision) throws SQLException {
        repository.saveApplicationSecretRevision(revision);
    }
    /**
     * Finds immutable application-secret metadata without reading a secret value.
     *
     * <p>在不读取秘密值的情况下查找不可变应用秘密元数据。
     */
    public Optional<StoredApplicationSecretRevision> findApplicationSecretRevision(SecretReference reference) throws SQLException {
        return repository.findApplicationSecretRevision(reference);
    }
    /**
     * Binds the exact immutable secret revisions to one release identity.
     *
     * <p>将精确的不可变秘密修订绑定到一个发布标识。
     */
    public void bindApplicationReleaseSecrets(String applicationId, String releaseIdentity, List<SecretReference> references)
            throws SQLException {
        repository.bindApplicationReleaseSecrets(applicationId, releaseIdentity, references);
    }
    /**
     * Reports whether a secret revision remains retained by a release.
     *
     * <p>报告一个秘密修订是否仍由发布保留。
     */
    public boolean isApplicationSecretRevisionReferenced(SecretReference reference) throws SQLException {
        return repository.isApplicationSecretRevisionReferenced(reference);
    }
    /**
     * Stores data through {@code saveManagedApplication}.
     *
     * <p>通过 {@code saveManagedApplication} 保存数据。
     *
     * @param application the {@code application} value / {@code application} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveManagedApplication(ManagedApplication application) throws SQLException { repository.saveManagedApplication(application); }
    /**
     * Stores data through {@code recordSuccessfulDeployment}.
     *
     * <p>通过 {@code recordSuccessfulDeployment} 保存数据。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param runtimeConfiguration the {@code runtimeConfiguration} value / {@code runtimeConfiguration} 值
     * @param release the {@code release} value / {@code release} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void recordSuccessfulDeployment(ManagedApplication application,
                                           ManagedApplicationRuntimeConfiguration runtimeConfiguration,
                                           CurrentRelease release) throws SQLException {
        repository.recordSuccessfulDeployment(application, runtimeConfiguration, release);
    }
    /**
     * Returns the value produced by {@code findManagedApplication}.
     *
     * <p>返回 {@code findManagedApplication} 生成的值。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ManagedApplication> findManagedApplication(String applicationId) throws SQLException {
        return repository.findManagedApplication(applicationId);
    }
    /**
     * Returns the value produced by {@code findManagedApplicationRuntimeConfiguration}.
     *
     * <p>返回 {@code findManagedApplicationRuntimeConfiguration} 生成的值。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ManagedApplicationRuntimeConfiguration> findManagedApplicationRuntimeConfiguration(String applicationId)
            throws SQLException { return repository.findManagedApplicationRuntimeConfiguration(applicationId); }
    /**
     * Stores data through {@code saveCurrentRelease}.
     *
     * <p>通过 {@code saveCurrentRelease} 保存数据。
     *
     * @param release the {@code release} value / {@code release} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveCurrentRelease(CurrentRelease release) throws SQLException { repository.saveCurrentRelease(release); }
    /**
     * Returns the value produced by {@code findCurrentRelease}.
     *
     * <p>返回 {@code findCurrentRelease} 生成的值。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<CurrentRelease> findCurrentRelease(String applicationId) throws SQLException {
        return repository.findCurrentRelease(applicationId);
    }
    /**
     * Returns the values selected by {@code listManagedApplications}.
     *
     * <p>返回 {@code listManagedApplications} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public List<ManagedApplication> listManagedApplications() throws SQLException { return repository.listManagedApplications(); }
    /**
     * Stores data through {@code saveLastObservation}.
     *
     * <p>通过 {@code saveLastObservation} 保存数据。
     *
     * @param observation the {@code observation} value / {@code observation} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveLastObservation(LifecycleObservation observation) throws SQLException { repository.saveLastObservation(observation); }
    /**
     * Returns the value produced by {@code findLastObservation}.
     *
     * <p>返回 {@code findLastObservation} 生成的值。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<LifecycleObservation> findLastObservation(ManagedApplication application) throws SQLException {
        return repository.findLastObservation(application);
    }
    /**
     * Stores data through {@code saveOpaqueSecret}.
     *
     * <p>通过 {@code saveOpaqueSecret} 保存数据。
     *
     * @param secret the {@code secret} value / {@code secret} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveOpaqueSecret(OpaqueSecret secret) throws SQLException { repository.saveOpaqueSecret(secret); }
    /**
     * Returns the value produced by {@code findOpaqueSecret}.
     *
     * <p>返回 {@code findOpaqueSecret} 生成的值。
     *
     * @param key the {@code key} value / {@code key} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<OpaqueSecret> findOpaqueSecret(String key) throws SQLException { return repository.findOpaqueSecret(key); }

    @Override
    public void close() {
        repository.close();
    }
}

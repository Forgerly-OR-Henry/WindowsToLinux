package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stores managed applications, runtime contracts, releases, and lifecycle observations. / 保存受管应用、运行契约、发布和生命周期观测。
 */
public final class ManagedApplicationRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;

    /**
     * Creates the repository. / 创建仓库。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedApplicationRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Saves a managed application identity. / 保存受管应用身份。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void save(ManagedApplication application) throws SQLException {
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                RepositoryTransactionExecutor.upsertServer(connection, application.server());
                upsertApplication(connection, application);
            });
        }
    }

    /**
     * Atomically records all successful-deployment state. / 原子记录全部成功部署状态。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeConfiguration runtime configuration / 运行时配置
     * @param release release / 发布
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void recordSuccessfulDeployment(ManagedApplication application,
                                           ManagedApplicationRuntimeConfiguration runtimeConfiguration,
                                           CurrentRelease release) throws SQLException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        Objects.requireNonNull(release, "release");
        if (!application.id().equals(release.applicationId())) {
            throw new IllegalArgumentException("current release must belong to the managed application");
        }
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                RepositoryTransactionExecutor.upsertServer(connection, application.server());
                upsertApplication(connection, application);
                upsertRuntime(connection, application.id(), runtimeConfiguration);
                upsertRelease(connection, release);
            });
        }
    }

    /**
     * Atomically records every component of one successful whole-application transaction. / 原子记录一次成功整应用事务的全部组件。
     *
     * @param deployments deployments / 部署集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void recordSuccessfulDeployments(List<SuccessfulManagedDeployment> deployments) throws SQLException {
        List<SuccessfulManagedDeployment> records = List.copyOf(Objects.requireNonNull(deployments, "deployments"));
        if (records.isEmpty() || records.stream().map(value -> value.application().id()).distinct().count()
                != records.size()) {
            throw new IllegalArgumentException("whole-application persistence requires unique non-empty components");
        }
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> recordSuccessfulDeployments(connection, records));
        }
    }

    /**
     * Atomically records reviewed success with exact configuration and secret-revision bindings. / 原子记录经审阅的成功状态及精确配置、秘密修订绑定。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeConfiguration runtime configuration / 运行时配置
     * @param release release / 发布
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void recordSuccessfulDeployment(ManagedApplication application,
                                           ManagedApplicationRuntimeConfiguration runtimeConfiguration, CurrentRelease release,
                                           ConfigurationSnapshot configuration, List<SecretReference> secretReferences) throws SQLException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(configuration, "configuration");
        Set<SecretReference> references = Set.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        if (references.size() != secretReferences.size()) {
            throw new IllegalArgumentException("successful release secret references must be unique");
        }
        if (!application.id().equals(release.applicationId()) || !application.id().equals(configuration.applicationId())) {
            throw new IllegalArgumentException("current release and configuration must belong to the managed application");
        }
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                RepositoryTransactionExecutor.upsertServer(connection, application.server());
                upsertApplication(connection, application);
                upsertRuntime(connection, application.id(), runtimeConfiguration);
                upsertRelease(connection, release);
                ConfigurationSnapshotRepository.saveAndBindRelease(connection, configuration, release.releaseSha256());
                ApplicationSecretRepository.bindRelease(connection, application.id(), release.releaseSha256(), references);
            });
        }
    }

    /**
     * Finds a managed application. / 查找受管应用。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ManagedApplication> find(String applicationId) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id, a.systemd_unit, a.release_root, a.ownership_manifest_sha256,
                       s.id AS server_id, s.host, s.ssh_port, s.host_key_sha256
                FROM managed_application a JOIN server s ON a.server_id=s.id WHERE a.id=?
                """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readApplication(result)) : Optional.empty();
            }
        }
    }

    /**
     * Lists managed applications. / 列出受管应用。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<ManagedApplication> list() throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id, a.systemd_unit, a.release_root, a.ownership_manifest_sha256,
                       s.id AS server_id, s.host, s.ssh_port, s.host_key_sha256
                FROM managed_application a JOIN server s ON a.server_id=s.id ORDER BY a.id
                """); ResultSet result = statement.executeQuery()) {
            List<ManagedApplication> applications = new ArrayList<>();
            while (result.next()) {
                applications.add(readApplication(result));
            }
            return List.copyOf(applications);
        }
    }

    /**
     * Finds the persisted runtime contract. / 查找持久化运行契约。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ManagedApplicationRuntimeConfiguration> findRuntime(String applicationId) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT health_kind, http_endpoint, http_expected_status, tcp_port, health_timeout_seconds,
                       tcp_stability_seconds, user_access_url, identity_policy, runtime_payload
                FROM managed_application_runtime_configuration WHERE application_id=?
                """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRuntime(result)) : Optional.empty();
            }
        }
    }

    /**
     * Saves the current release. / 保存当前发布。
     *
     * @param release release / 发布
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveRelease(CurrentRelease release) throws SQLException {
        try (Connection connection = connections.open()) {
            upsertRelease(connection, release);
        }
    }

    /**
     * Finds the current release. / 查找当前发布。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<CurrentRelease> findRelease(String applicationId) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT application_id, release_sha256, published_at FROM managed_application_release WHERE application_id=?
                """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new CurrentRelease(result.getString("application_id"),
                        result.getString("release_sha256"), Instant.ofEpochMilli(result.getLong("published_at"))))
                        : Optional.empty();
            }
        }
    }

    /**
     * Saves the last non-authoritative lifecycle observation. / 保存最后一项非权威生命周期观测。
     *
     * @param observation observation / 观测
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveObservation(LifecycleObservation observation) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_observation (
                    application_id, runtime_state, autostart_state, ownership_verified, observed_at, evidence
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET runtime_state=excluded.runtime_state,
                    autostart_state=excluded.autostart_state, ownership_verified=excluded.ownership_verified,
                    observed_at=excluded.observed_at, evidence=excluded.evidence
                """)) {
            statement.setString(1, observation.application().id());
            statement.setString(2, observation.runtimeState().name());
            statement.setString(3, observation.autostartState().name());
            statement.setInt(4, observation.ownershipVerified() ? 1 : 0);
            statement.setLong(5, observation.observedAt().toEpochMilli());
            statement.setString(6, observation.evidence());
            statement.executeUpdate();
        }
    }

    /**
     * Finds the last non-authoritative lifecycle observation. / 查找最后一项非权威生命周期观测。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<LifecycleObservation> findObservation(ManagedApplication application) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT runtime_state, autostart_state, ownership_verified, observed_at, evidence
                FROM application_observation WHERE application_id=?
                """)) {
            statement.setString(1, application.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new LifecycleObservation(application,
                        RuntimeState.valueOf(result.getString("runtime_state")),
                        AutostartState.valueOf(result.getString("autostart_state")),
                        result.getInt("ownership_verified") == 1,
                        Instant.ofEpochMilli(result.getLong("observed_at")), result.getString("evidence")))
                        : Optional.empty();
            }
        }
    }

    /**
     * Inserts or updates managed target with its server and ownership identity.
     * <p>插入或更新携带服务器及归属身份的受管目标。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static void upsertApplication(Connection connection, ManagedApplication application) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application (id, server_id, systemd_unit, release_root, ownership_manifest_sha256)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET server_id=excluded.server_id, systemd_unit=excluded.systemd_unit,
                    release_root=excluded.release_root, ownership_manifest_sha256=excluded.ownership_manifest_sha256
                """)) {
            statement.setString(1, application.id());
            statement.setString(2, application.server().id());
            statement.setString(3, application.systemdUnit());
            statement.setString(4, application.releaseRoot());
            statement.setString(5, application.ownershipManifestSha256());
            statement.executeUpdate();
        }
    }

    /**
     * Records successful deployments.
     * <p>记录成功部署集合。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param deployments deployments / 部署集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void recordSuccessfulDeployments(Connection connection,
                                            List<SuccessfulManagedDeployment> deployments) throws SQLException {
        for (SuccessfulManagedDeployment deployment : deployments) {
            RepositoryTransactionExecutor.upsertServer(connection, deployment.application().server());
            upsertApplication(connection, deployment.application());
            upsertRuntime(connection, deployment.application().id(), deployment.runtimeConfiguration());
            upsertRelease(connection, deployment.release());
            ConfigurationSnapshotRepository.saveAndBindRelease(connection, deployment.configuration(),
                    deployment.release().releaseSha256());
            ApplicationSecretRepository.bindRelease(connection, deployment.application().id(),
                    deployment.release().releaseSha256(), Set.copyOf(deployment.secretReferences()));
        }
    }

    /**
     * Inserts or updates reviewed language, process and health specification.
     * <p>插入或更新已审阅的语言、进程及健康规格。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param applicationId managed application identifier / 受管应用标识
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static void upsertRuntime(Connection connection, String applicationId,
                                      ManagedApplicationRuntimeConfiguration configuration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_runtime_configuration
                    (application_id, health_kind, health_timeout_seconds, user_access_url, identity_policy, runtime_payload)
                VALUES (?, 'TYPED', ?, ?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET health_kind=excluded.health_kind,
                    health_timeout_seconds=excluded.health_timeout_seconds,user_access_url=excluded.user_access_url,
                    identity_policy=excluded.identity_policy,runtime_payload=excluded.runtime_payload,
                    http_endpoint=NULL,http_expected_status=NULL,tcp_port=NULL,tcp_stability_seconds=NULL
                """)) {
            statement.setString(1, applicationId); statement.setInt(2, configuration.healthCheck().timeoutSeconds());
            statement.setString(3, configuration.userAccessUrl().map(url -> url.url().toString()).orElse(null));
            statement.setString(4, configuration.identityPolicy().name());
            statement.setBytes(5, new gold.debug.windowstolinux.shared.config.persistence.serialization.ApplicationRuntimeConfigurationCodec().write(configuration));
            statement.executeUpdate();
        } catch (java.io.IOException failure) { throw new SQLException("invalid application runtime payload", failure); }
    }

    /**
     * Inserts or updates release.
     * <p>插入或更新发布。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param release release / 发布
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static void upsertRelease(Connection connection, CurrentRelease release) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_release (application_id, release_sha256, published_at) VALUES (?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET release_sha256=excluded.release_sha256,
                    published_at=excluded.published_at
                """)) {
            statement.setString(1, release.applicationId());
            statement.setString(2, release.releaseSha256());
            statement.setLong(3, release.publishedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    /**
     * Reads managed target with its server and ownership identity.
     * <p>读取携带服务器及归属身份的受管目标。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static ManagedApplication readApplication(ResultSet result) throws SQLException {
        return new ManagedApplication(result.getString("id"), new ServerIdentity(result.getString("server_id"),
                result.getString("host"), result.getInt("ssh_port"), result.getString("host_key_sha256")),
                result.getString("systemd_unit"), result.getString("release_root"),
                result.getString("ownership_manifest_sha256"));
    }

    /**
     * Reads reviewed language, process and health specification.
     * <p>读取已审阅的语言、进程及健康规格。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static ManagedApplicationRuntimeConfiguration readRuntime(ResultSet result) throws SQLException {
        try {
            if ("TYPED".equals(result.getString("health_kind"))) {
                try { return new gold.debug.windowstolinux.shared.config.persistence.serialization.ApplicationRuntimeConfigurationCodec().read(result.getBytes("runtime_payload")); }
                catch (java.io.IOException failure) { throw new SQLException("application runtime requires reanalysis", failure); }
            }
            var policy = gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.valueOf(required(result, "identity_policy"));
            String kind = result.getString("health_kind");
            int timeout = result.getInt("health_timeout_seconds");
            return switch (kind) {
                case "HTTP" -> new ManagedApplicationRuntimeConfiguration(new HealthCheck.Http(
                        URI.create(required(result, "http_endpoint")), result.getInt("http_expected_status"), timeout),
                        Optional.of(new UserAccessUrl(URI.create(required(result, "user_access_url")))), policy);
                case "TCP" -> new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(result.getInt("tcp_port"),
                        timeout, result.getInt("tcp_stability_seconds")), Optional.empty(), policy);
                default -> throw new SQLException("saved managed-deployment health-check type is invalid");
            };
        } catch (IllegalArgumentException exception) {
            throw new SQLException("saved managed-deployment runtime configuration violates current validation rules", exception);
        }
    }

    /**
     * Requires the named input to be present and valid before continuing.
     * <p>继续前要求具名输入存在且有效。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param column column / 列
     * @return required text / 必需文本
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static String required(ResultSet result, String column) throws SQLException {
        return Optional.ofNullable(result.getString(column)).filter(value -> !value.isBlank())
                .orElseThrow(() -> new SQLException(
                        "saved managed-deployment runtime configuration is missing " + column));
    }
}

package gold.debug.windowstolinux.app.db.repository;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.OpaqueSecret;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;

import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQLite storage for non-secret identities, deployed runtime configuration and observations.
 *
 * <p>用于保存非秘密身份、已部署运行配置和观测结果的 SQLite 存储。
 */
public final class DesktopRepository implements AutoCloseable {
    private final DesktopConnectionFactory connections;

    /**
     * Creates a {@code DesktopRepository} instance.
     *
     * <p>创建 {@code DesktopRepository} 实例。
     *
     * @param connections the {@code connections} value / {@code connections} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Stores data through {@code saveServer}.
     *
     * <p>通过 {@code saveServer} 保存数据。
     *
     * @param server the {@code server} value / {@code server} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveServer(ServerIdentity server) throws SQLException {
        try (Connection connection = connect()) {
            upsertServer(connection, server);
        }
    }

    /**
     * Returns the value produced by {@code findServer}.
     *
     * <p>返回 {@code findServer} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ServerIdentity> findServer(String serverId) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT id, host, ssh_port, host_key_sha256 FROM server WHERE id=?")) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new ServerIdentity(
                        result.getString("id"), result.getString("host"), result.getInt("ssh_port"), result.getString("host_key_sha256")
                )) : Optional.empty();
            }
        }
    }

    /**
     * Stores a small, non-secret desktop preference such as the selected locale or theme.
     *
     * <p>保存选定区域设置或主题等小型非秘密桌面偏好。
     *
     * @param name the {@code name} value / {@code name} 值
     * @param value the {@code value} value / {@code value} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveDesktopPreference(String name, String value) throws SQLException {
        requirePreferenceText(name, "name");
        requirePreferenceText(value, "value");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO desktop_preference (name, value) VALUES (?, ?)
                     ON CONFLICT(name) DO UPDATE SET value=excluded.value
                     """)) {
            statement.setString(1, name);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    /**
     * Returns a non-secret desktop preference when it has been chosen before.
     *
     * <p>返回此前已经选择的非秘密桌面偏好（如存在）。
     *
     * @param name the {@code name} value / {@code name} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<String> findDesktopPreference(String name) throws SQLException {
        requirePreferenceText(name, "name");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM desktop_preference WHERE name=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("value")) : Optional.empty();
            }
        }
    }

    /**
     * Stores data through {@code saveServerProfile}.
     *
     * <p>通过 {@code saveServerProfile} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveServerProfile(StoredServerProfile profile) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO server_profile (id, host, ssh_port, username, credential_key, credential_mode)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET host=excluded.host, ssh_port=excluded.ssh_port, username=excluded.username,
                     credential_key=excluded.credential_key, credential_mode=excluded.credential_mode
                     """)) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.host());
            statement.setInt(3, profile.sshPort());
            statement.setString(4, profile.username());
            statement.setString(5, profile.credentialKey());
            statement.setString(6, profile.credentialMode());
            statement.executeUpdate();
        }
    }

    /**
     * Returns the value produced by {@code findServerProfile}.
     *
     * <p>返回 {@code findServerProfile} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<StoredServerProfile> findServerProfile(String serverId) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, host, ssh_port, username, credential_key, credential_mode FROM server_profile WHERE id=?
                     """)) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new StoredServerProfile(
                        result.getString("id"), result.getString("host"), result.getInt("ssh_port"),
                        result.getString("username"), result.getString("credential_key"), result.getString("credential_mode")
                )) : Optional.empty();
            }
        }
    }

    /**
     * Stores data through {@code saveAiProfile}.
     *
     * <p>通过 {@code saveAiProfile} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveAiProfile(StoredAiProfile profile) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_profile (id, endpoint, model, credential_key, credential_mode)
                     VALUES ('default', ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET endpoint=excluded.endpoint, model=excluded.model,
                     credential_key=excluded.credential_key, credential_mode=excluded.credential_mode
                     """)) {
            statement.setString(1, profile.endpoint());
            statement.setString(2, profile.model());
            statement.setString(3, profile.credentialKey());
            statement.setString(4, profile.credentialMode());
            statement.executeUpdate();
        }
    }

    /**
     * Returns the value produced by {@code findAiProfile}.
     *
     * <p>返回 {@code findAiProfile} 生成的值。
     *
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<StoredAiProfile> findAiProfile() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT endpoint, model, credential_key, credential_mode FROM ai_profile WHERE id='default'
                     """);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(new StoredAiProfile(
                    result.getString("endpoint"), result.getString("model"), result.getString("credential_key"),
                    result.getString("credential_mode")
            )) : Optional.empty();
        }
    }

    /**
     * Stores data through {@code saveManagedApplication}.
     *
     * <p>通过 {@code saveManagedApplication} 保存数据。
     *
     * @param application the {@code application} value / {@code application} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public void saveManagedApplication(ManagedApplication application) throws SQLException {
        Objects.requireNonNull(application, "application");
        try (Connection connection = connect()) {
            inTransaction(connection, () -> {
                upsertServer(connection, application.server());
                upsertManagedApplication(connection, application);
            });
        }
    }

    /**
     * Persists every fact needed to operate a successfully deployed application together, so a desktop restart cannot replace its health contract with UI defaults.
     *
     * <p>将操作成功部署应用所需的全部事实一起持久化，确保桌面重启不会用界面默认值替换其健康契约。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param runtimeConfiguration the {@code runtimeConfiguration} value / {@code runtimeConfiguration} 值
     * @param release the {@code release} value / {@code release} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public void recordSuccessfulDeployment(
            ManagedApplication application,
            ManagedApplicationRuntimeConfiguration runtimeConfiguration,
            CurrentRelease release
    ) throws SQLException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        Objects.requireNonNull(release, "release");
        if (!application.id().equals(release.applicationId())) {
            throw new IllegalArgumentException("current release must belong to the managed application");
        }
        try (Connection connection = connect()) {
            inTransaction(connection, () -> {
                upsertServer(connection, application.server());
                upsertManagedApplication(connection, application);
                upsertRuntimeConfiguration(connection, application.id(), runtimeConfiguration);
                upsertCurrentRelease(connection, release);
            });
        }
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
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT a.id, a.systemd_unit, a.release_root, a.ownership_manifest_sha256,
                            s.id AS server_id, s.host, s.ssh_port, s.host_key_sha256
                     FROM managed_application a JOIN server s ON a.server_id=s.id WHERE a.id=?
                     """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readManagedApplication(result)) : Optional.empty();
            }
        }
    }

    /**
     * Empty means that this is a legacy record created before runtime configuration was persisted; callers must refuse lifecycle mutation rather than guess a health endpoint.
     *
     * <p>空值表示这是在持久化运行配置之前创建的旧记录；调用方必须拒绝生命周期修改，而不能猜测健康端点。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ManagedApplicationRuntimeConfiguration> findManagedApplicationRuntimeConfiguration(String applicationId)
            throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                      SELECT health_kind, http_endpoint, http_expected_status, tcp_port, health_timeout_seconds,
                             tcp_stability_seconds, user_access_url
                      FROM managed_application_runtime_configuration WHERE application_id=?
                      """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRuntimeConfiguration(result)) : Optional.empty();
            }
        }
    }

    /**
     * Stores data through {@code saveCurrentRelease}.
     *
     * <p>通过 {@code saveCurrentRelease} 保存数据。
     *
     * @param release the {@code release} value / {@code release} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveCurrentRelease(CurrentRelease release) throws SQLException {
        try (Connection connection = connect()) {
            upsertCurrentRelease(connection, release);
        }
    }

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
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT application_id, artifact_sha256, published_at FROM managed_application_release WHERE application_id=?
                     """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new CurrentRelease(result.getString("application_id"),
                        result.getString("artifact_sha256"), Instant.ofEpochMilli(result.getLong("published_at")))) : Optional.empty();
            }
        }
    }

    /**
     * Returns the values selected by {@code listManagedApplications}.
     *
     * <p>返回 {@code listManagedApplications} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public List<ManagedApplication> listManagedApplications() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT a.id, a.systemd_unit, a.release_root, a.ownership_manifest_sha256,
                            s.id AS server_id, s.host, s.ssh_port, s.host_key_sha256
                     FROM managed_application a JOIN server s ON a.server_id=s.id ORDER BY a.id
                     """);
             ResultSet result = statement.executeQuery()) {
            List<ManagedApplication> applications = new ArrayList<>();
            while (result.next()) {
                applications.add(readManagedApplication(result));
            }
            return List.copyOf(applications);
        }
    }

    /**
     * Stores data through {@code saveLastObservation}.
     *
     * <p>通过 {@code saveLastObservation} 保存数据。
     *
     * @param observation the {@code observation} value / {@code observation} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveLastObservation(LifecycleObservation observation) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO application_observation (application_id, runtime_state, autostart_state, ownership_verified, observed_at, evidence)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(application_id) DO UPDATE SET runtime_state=excluded.runtime_state, autostart_state=excluded.autostart_state,
                     ownership_verified=excluded.ownership_verified, observed_at=excluded.observed_at, evidence=excluded.evidence
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
     * History only: callers must still query the server before treating it as current state.
     *
     * <p>仅代表历史记录：调用方仍必须查询服务器，才能将其视为当前状态。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<LifecycleObservation> findLastObservation(ManagedApplication application) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT runtime_state, autostart_state, ownership_verified, observed_at, evidence
                     FROM application_observation WHERE application_id=?
                     """)) {
            statement.setString(1, application.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new LifecycleObservation(
                        application,
                        RuntimeState.valueOf(result.getString("runtime_state")),
                        AutostartState.valueOf(result.getString("autostart_state")),
                        result.getInt("ownership_verified") == 1,
                        Instant.ofEpochMilli(result.getLong("observed_at")),
                        result.getString("evidence")
                )) : Optional.empty();
            }
        }
    }

    /**
     * Stores data through {@code saveOpaqueSecret}.
     *
     * <p>通过 {@code saveOpaqueSecret} 保存数据。
     *
     * @param secret the {@code secret} value / {@code secret} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public void saveOpaqueSecret(OpaqueSecret secret) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO encrypted_secret (secret_key, algorithm, salt, nonce, ciphertext)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(secret_key) DO UPDATE SET algorithm=excluded.algorithm, salt=excluded.salt,
                     nonce=excluded.nonce, ciphertext=excluded.ciphertext
                     """)) {
            statement.setString(1, secret.key());
            statement.setString(2, secret.algorithm());
            statement.setBytes(3, secret.salt());
            statement.setBytes(4, secret.nonce());
            statement.setBytes(5, secret.ciphertext());
            statement.executeUpdate();
        }
    }

    /**
     * Returns the value produced by {@code findOpaqueSecret}.
     *
     * <p>返回 {@code findOpaqueSecret} 生成的值。
     *
     * @param key the {@code key} value / {@code key} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<OpaqueSecret> findOpaqueSecret(String key) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT secret_key, algorithm, salt, nonce, ciphertext FROM encrypted_secret WHERE secret_key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new OpaqueSecret(
                        result.getString("secret_key"), result.getString("algorithm"), result.getBytes("salt"),
                        result.getBytes("nonce"), result.getBytes("ciphertext")
                )) : Optional.empty();
            }
        }
    }

    private static String requirePreferenceText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void upsertServer(Connection connection, ServerIdentity server) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO server (id, host, ssh_port, host_key_sha256)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET host=excluded.host, ssh_port=excluded.ssh_port,
                    host_key_sha256=excluded.host_key_sha256
                """)) {
            statement.setString(1, server.id());
            statement.setString(2, server.host());
            statement.setInt(3, server.sshPort());
            statement.setString(4, server.hostKeySha256());
            statement.executeUpdate();
        }
    }

    private static void upsertManagedApplication(Connection connection, ManagedApplication application) throws SQLException {
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

    private static void upsertRuntimeConfiguration(
            Connection connection,
            String applicationId,
            ManagedApplicationRuntimeConfiguration configuration
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_runtime_configuration (
                    application_id, health_kind, http_endpoint, http_expected_status, tcp_port,
                    health_timeout_seconds, tcp_stability_seconds, user_access_url
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET health_kind=excluded.health_kind,
                    http_endpoint=excluded.http_endpoint, http_expected_status=excluded.http_expected_status,
                    tcp_port=excluded.tcp_port, health_timeout_seconds=excluded.health_timeout_seconds,
                    tcp_stability_seconds=excluded.tcp_stability_seconds, user_access_url=excluded.user_access_url
                """)) {
            statement.setString(1, applicationId);
            if (configuration.healthCheck() instanceof HealthCheck.Http http) {
                statement.setString(2, "HTTP");
                statement.setString(3, http.endpoint().toASCIIString());
                statement.setInt(4, http.expectedStatus());
                statement.setNull(5, Types.INTEGER);
                statement.setInt(6, http.timeoutSeconds());
                statement.setNull(7, Types.INTEGER);
                statement.setString(8, configuration.userAccessUrl().orElseThrow().url().toASCIIString());
            } else if (configuration.healthCheck() instanceof HealthCheck.Tcp tcp) {
                statement.setString(2, "TCP");
                statement.setNull(3, Types.VARCHAR);
                statement.setNull(4, Types.INTEGER);
                statement.setInt(5, tcp.port());
                statement.setInt(6, tcp.timeoutSeconds());
                statement.setInt(7, tcp.stabilitySeconds());
                statement.setNull(8, Types.VARCHAR);
            } else {
                throw new SQLException("unsupported phase-one health-check type");
            }
            statement.executeUpdate();
        }
    }

    private static void upsertCurrentRelease(Connection connection, CurrentRelease release) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_release (application_id, artifact_sha256, published_at)
                VALUES (?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET artifact_sha256=excluded.artifact_sha256,
                    published_at=excluded.published_at
                """)) {
            statement.setString(1, release.applicationId());
            statement.setString(2, release.artifactSha256());
            statement.setLong(3, release.publishedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static ManagedApplicationRuntimeConfiguration readRuntimeConfiguration(ResultSet result) throws SQLException {
        try {
            String kind = result.getString("health_kind");
            int timeoutSeconds = result.getInt("health_timeout_seconds");
            return switch (kind) {
                case "HTTP" -> new ManagedApplicationRuntimeConfiguration(
                        new HealthCheck.Http(
                                URI.create(requiredColumn(result, "http_endpoint")),
                                result.getInt("http_expected_status"), timeoutSeconds
                        ),
                        Optional.of(new UserAccessUrl(URI.create(requiredColumn(result, "user_access_url"))))
                );
                case "TCP" -> new ManagedApplicationRuntimeConfiguration(
                        new HealthCheck.Tcp(result.getInt("tcp_port"), timeoutSeconds,
                                result.getInt("tcp_stability_seconds")),
                        Optional.empty()
                );
                default -> throw new SQLException("saved phase-one health-check type is invalid");
            };
        } catch (IllegalArgumentException exception) {
            throw new SQLException("saved phase-one runtime configuration violates current validation rules", exception);
        }
    }

    private static String requiredColumn(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null || value.isBlank()) {
            throw new SQLException("saved phase-one runtime configuration is missing " + column);
        }
        return value;
    }

    private static void inTransaction(Connection connection, SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            work.execute();
            connection.commit();
        } catch (SQLException exception) {
            rollback(connection, exception);
            throw exception;
        }
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        /**
         * Performs the {@code execute} operation.
         *
         * <p>执行 {@code execute} 操作。
         *
         * @throws SQLException if the operation cannot be completed / 无法完成操作时
         */
        void execute() throws SQLException;
    }

    private Connection connect() throws SQLException {
        return connections.open();
    }

    private static ManagedApplication readManagedApplication(ResultSet result) throws SQLException {
        return new ManagedApplication(
                result.getString("id"),
                new ServerIdentity(result.getString("server_id"), result.getString("host"), result.getInt("ssh_port"), result.getString("host_key_sha256")),
                result.getString("systemd_unit"), result.getString("release_root"), result.getString("ownership_manifest_sha256")
        );
    }

    @Override
    public void close() {
        // Connections are short-lived, so the database has no shared handle to close. / 连接均为短生命周期，因此数据库没有需要关闭的共享句柄。
    }
}

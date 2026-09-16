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

/** Stores managed applications, runtime contracts, releases, and lifecycle observations. / 保存受管应用、运行契约、发布和生命周期观测。 */
public final class ManagedApplicationRepository {
    private final DesktopConnectionFactory connections;

    /** Creates the repository. / 创建仓库。 */
    public ManagedApplicationRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Saves a managed application identity. / 保存受管应用身份。 */
    public void save(ManagedApplication application) throws SQLException {
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                RepositoryTransactionExecutor.upsertServer(connection, application.server());
                upsertApplication(connection, application);
            });
        }
    }

    /** Atomically records all successful-deployment state. / 原子记录全部成功部署状态。 */
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

    /** Atomically records every component of one successful whole-application transaction. / 原子记录一次成功整应用事务的全部组件。 */
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

    /** Atomically records reviewed success with exact configuration and secret-revision bindings. / 原子记录经审阅的成功状态及精确配置、秘密修订绑定。 */
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

    /** Finds a managed application. / 查找受管应用。 */
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

    /** Lists managed applications. / 列出受管应用。 */
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

    /** Finds the persisted runtime contract. / 查找持久化运行契约。 */
    public Optional<ManagedApplicationRuntimeConfiguration> findRuntime(String applicationId) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT health_kind, http_endpoint, http_expected_status, tcp_port, health_timeout_seconds,
                       tcp_stability_seconds, user_access_url, identity_policy
                FROM managed_application_runtime_configuration WHERE application_id=?
                """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRuntime(result)) : Optional.empty();
            }
        }
    }

    /** Saves the current release. / 保存当前发布。 */
    public void saveRelease(CurrentRelease release) throws SQLException {
        try (Connection connection = connections.open()) {
            upsertRelease(connection, release);
        }
    }

    /** Finds the current release. / 查找当前发布。 */
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

    /** Saves the last non-authoritative lifecycle observation. / 保存最后一项非权威生命周期观测。 */
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

    /** Finds the last non-authoritative lifecycle observation. / 查找最后一项非权威生命周期观测。 */
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

    private static void upsertRuntime(Connection connection, String applicationId,
                                      ManagedApplicationRuntimeConfiguration configuration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_runtime_configuration (
                    application_id, health_kind, http_endpoint, http_expected_status, tcp_port,
                    health_timeout_seconds, tcp_stability_seconds, user_access_url, identity_policy
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET health_kind=excluded.health_kind,
                    http_endpoint=excluded.http_endpoint, http_expected_status=excluded.http_expected_status,
                    tcp_port=excluded.tcp_port, health_timeout_seconds=excluded.health_timeout_seconds,
                    tcp_stability_seconds=excluded.tcp_stability_seconds, user_access_url=excluded.user_access_url, identity_policy=excluded.identity_policy
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
                throw new SQLException("unsupported managed-deployment health-check type");
            }
            statement.setString(9, configuration.identityPolicy().name());
            statement.executeUpdate();
        }
    }

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

    static ManagedApplication readApplication(ResultSet result) throws SQLException {
        return new ManagedApplication(result.getString("id"), new ServerIdentity(result.getString("server_id"),
                result.getString("host"), result.getInt("ssh_port"), result.getString("host_key_sha256")),
                result.getString("systemd_unit"), result.getString("release_root"),
                result.getString("ownership_manifest_sha256"));
    }

    static ManagedApplicationRuntimeConfiguration readRuntime(ResultSet result) throws SQLException {
        try {
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

    private static String required(ResultSet result, String column) throws SQLException {
        return Optional.ofNullable(result.getString(column)).filter(value -> !value.isBlank())
                .orElseThrow(() -> new SQLException(
                        "saved managed-deployment runtime configuration is missing " + column));
    }
}

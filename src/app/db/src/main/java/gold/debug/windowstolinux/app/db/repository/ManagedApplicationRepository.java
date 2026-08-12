package gold.debug.windowstolinux.app.db.repository;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

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
            RepositoryTransactions.execute(connection, () -> {
                RepositoryTransactions.upsertServer(connection, application.server());
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
            RepositoryTransactions.execute(connection, () -> {
                RepositoryTransactions.upsertServer(connection, application.server());
                upsertApplication(connection, application);
                upsertRuntime(connection, application.id(), runtimeConfiguration);
                upsertRelease(connection, release);
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
                       tcp_stability_seconds, user_access_url
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
                SELECT application_id, artifact_sha256, published_at FROM managed_application_release WHERE application_id=?
                """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new CurrentRelease(result.getString("application_id"),
                        result.getString("artifact_sha256"), Instant.ofEpochMilli(result.getLong("published_at"))))
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

    private static void upsertRuntime(Connection connection, String applicationId,
                                      ManagedApplicationRuntimeConfiguration configuration) throws SQLException {
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
                throw new SQLException("unsupported managed-deployment health-check type");
            }
            statement.executeUpdate();
        }
    }

    private static void upsertRelease(Connection connection, CurrentRelease release) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_release (application_id, artifact_sha256, published_at) VALUES (?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET artifact_sha256=excluded.artifact_sha256,
                    published_at=excluded.published_at
                """)) {
            statement.setString(1, release.applicationId());
            statement.setString(2, release.artifactSha256());
            statement.setLong(3, release.publishedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static ManagedApplication readApplication(ResultSet result) throws SQLException {
        return new ManagedApplication(result.getString("id"), new ServerIdentity(result.getString("server_id"),
                result.getString("host"), result.getInt("ssh_port"), result.getString("host_key_sha256")),
                result.getString("systemd_unit"), result.getString("release_root"),
                result.getString("ownership_manifest_sha256"));
    }

    private static ManagedApplicationRuntimeConfiguration readRuntime(ResultSet result) throws SQLException {
        try {
            String kind = result.getString("health_kind");
            int timeout = result.getInt("health_timeout_seconds");
            return switch (kind) {
                case "HTTP" -> new ManagedApplicationRuntimeConfiguration(new HealthCheck.Http(
                        URI.create(required(result, "http_endpoint")), result.getInt("http_expected_status"), timeout),
                        Optional.of(new UserAccessUrl(URI.create(required(result, "user_access_url")))));
                case "TCP" -> new ManagedApplicationRuntimeConfiguration(new HealthCheck.Tcp(result.getInt("tcp_port"),
                        timeout, result.getInt("tcp_stability_seconds")), Optional.empty());
                default -> throw new SQLException("saved managed-deployment health-check type is invalid");
            };
        } catch (IllegalArgumentException exception) {
            throw new SQLException("saved managed-deployment runtime configuration violates current validation rules", exception);
        }
    }

    private static String required(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null || value.isBlank()) {
            throw new SQLException("saved managed-deployment runtime configuration is missing " + column);
        }
        return value;
    }
}

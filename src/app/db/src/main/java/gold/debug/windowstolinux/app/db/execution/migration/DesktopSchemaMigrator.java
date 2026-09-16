package gold.debug.windowstolinux.app.db.execution.migration;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Provides the {@code DesktopSchemaMigrator} implementation.
 *
 * <p>提供 {@code DesktopSchemaMigrator} 实现。
 */
public final class DesktopSchemaMigrator {
    /**
     * Exposes the {@code CURRENT_SCHEMA_VERSION} constant.
     *
     * <p>公开 {@code CURRENT_SCHEMA_VERSION} 常量。
     */
    public static final int CURRENT_SCHEMA_VERSION = 14;

    private DesktopSchemaMigrator() {
    }

    /**
     * Performs the {@code migrate} operation.
     *
     * <p>执行 {@code migrate} 操作。
     *
     * @param connections the {@code connections} value / {@code connections} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static void migrate(DesktopConnectionFactory connections) throws SQLException {
        Objects.requireNonNull(connections, "connections");
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            int version = schemaVersion(statement);
            if (version > CURRENT_SCHEMA_VERSION) {
                throw new SQLException("database schema is newer than this WindowsToLinux client; downgrade open is rejected");
            }
            connection.setAutoCommit(false);
            try {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server (
                      id TEXT PRIMARY KEY, host TEXT NOT NULL, ssh_port INTEGER NOT NULL, host_key_sha256 TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS managed_application (
                      id TEXT PRIMARY KEY, server_id TEXT NOT NULL REFERENCES server(id), systemd_unit TEXT NOT NULL,
                      release_root TEXT NOT NULL, ownership_manifest_sha256 TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS managed_application_release (
                      application_id TEXT PRIMARY KEY REFERENCES managed_application(id), release_sha256 TEXT NOT NULL,
                      published_at INTEGER NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_profile (
                      id TEXT PRIMARY KEY, host TEXT NOT NULL, ssh_port INTEGER NOT NULL, username TEXT NOT NULL,
                      credential_key TEXT NOT NULL, credential_mode TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS ai_profile (
                      id TEXT PRIMARY KEY CHECK(id='default'), endpoint TEXT NOT NULL, model TEXT NOT NULL,
                      credential_key TEXT NOT NULL, credential_mode TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS application_observation (
                      application_id TEXT PRIMARY KEY REFERENCES managed_application(id), runtime_state TEXT NOT NULL,
                      autostart_state TEXT NOT NULL, ownership_verified INTEGER NOT NULL, observed_at INTEGER NOT NULL, evidence TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS encrypted_secret (
                      secret_key TEXT PRIMARY KEY, algorithm TEXT NOT NULL, salt BLOB NOT NULL, nonce BLOB NOT NULL, ciphertext BLOB NOT NULL
                    )
                    """);
                if (version < 2) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS managed_application_runtime_configuration (
                              application_id TEXT PRIMARY KEY REFERENCES managed_application(id) ON DELETE CASCADE,
                              health_kind TEXT NOT NULL CHECK(health_kind IN ('HTTP', 'TCP')),
                              http_endpoint TEXT,
                              http_expected_status INTEGER,
                              tcp_port INTEGER,
                              health_timeout_seconds INTEGER NOT NULL CHECK(health_timeout_seconds BETWEEN 1 AND 300),
                              tcp_stability_seconds INTEGER,
                              user_access_url TEXT,
                              CHECK(
                                (health_kind = 'HTTP' AND http_endpoint IS NOT NULL
                                  AND http_expected_status BETWEEN 200 AND 399
                                  AND tcp_port IS NULL AND tcp_stability_seconds IS NULL
                                  AND user_access_url IS NOT NULL)
                                OR
                                (health_kind = 'TCP' AND tcp_port BETWEEN 1 AND 65535
                                  AND tcp_stability_seconds BETWEEN 1 AND 300
                                  AND http_endpoint IS NULL AND http_expected_status IS NULL
                                  AND user_access_url IS NULL)
                              )
                            )
                            """);
                }
                if (version < 3) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS desktop_preference (
                              name TEXT PRIMARY KEY,
                              value TEXT NOT NULL
                            )
                            """);
                }
                if (version < 4) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS application_configuration_snapshot (
                              application_id TEXT NOT NULL,
                              revision TEXT NOT NULL,
                              schema_version TEXT NOT NULL,
                              created_at INTEGER NOT NULL,
                              sha256 TEXT NOT NULL,
                              PRIMARY KEY (application_id, revision)
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS application_configuration_entry (
                              application_id TEXT NOT NULL,
                              revision TEXT NOT NULL,
                              config_key TEXT NOT NULL,
                              value_type TEXT NOT NULL,
                              config_scope TEXT NOT NULL,
                              value_text TEXT NOT NULL,
                              PRIMARY KEY (application_id, revision, config_key),
                              FOREIGN KEY (application_id, revision)
                                REFERENCES application_configuration_snapshot(application_id, revision) ON DELETE RESTRICT
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS application_secret_revision (
                              secret_identifier TEXT NOT NULL,
                              revision TEXT NOT NULL,
                              credential_key TEXT NOT NULL,
                              credential_mode TEXT NOT NULL,
                              created_at INTEGER NOT NULL,
                              PRIMARY KEY (secret_identifier, revision)
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS application_release_secret_binding (
                              application_id TEXT NOT NULL,
                              release_identity TEXT NOT NULL,
                              PRIMARY KEY (application_id, release_identity)
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS application_release_secret_reference (
                              application_id TEXT NOT NULL,
                              release_identity TEXT NOT NULL,
                              secret_identifier TEXT NOT NULL,
                              secret_revision TEXT NOT NULL,
                              PRIMARY KEY (application_id, release_identity, secret_identifier, secret_revision),
                              FOREIGN KEY (secret_identifier, secret_revision)
                                REFERENCES application_secret_revision(secret_identifier, revision) ON DELETE RESTRICT
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS ai_provider_profile (
                              profile_id TEXT PRIMARY KEY,
                              endpoint TEXT NOT NULL,
                              model TEXT NOT NULL,
                              credential_key TEXT NOT NULL,
                              credential_mode TEXT NOT NULL
                            )
                            """);
                    statement.execute("""
                            INSERT OR IGNORE INTO ai_provider_profile (
                              profile_id, endpoint, model, credential_key, credential_mode
                            ) SELECT id, endpoint, model, credential_key, credential_mode FROM ai_profile
                            """);
                }
                if (version < 5 && hasColumn(statement, "managed_application_release", "artifact_sha256")) {
                    statement.execute("""
                            ALTER TABLE managed_application_release
                            RENAME COLUMN artifact_sha256 TO release_sha256
                            """);
                }
                if (version < 6) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS ai_role_assignment (
                              role TEXT PRIMARY KEY CHECK(role IN (
                                'PROJECT_ANALYSIS', 'DEPLOYMENT_RISK_REVIEW', 'ERROR_EXPLANATION'
                              )),
                              profile_id TEXT NOT NULL REFERENCES ai_provider_profile(profile_id) ON DELETE RESTRICT
                            )
                            """);
                }
                if (version < 7) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS managed_application_graph (
                              application_id TEXT PRIMARY KEY,
                              server_id TEXT NOT NULL REFERENCES server(id) ON DELETE RESTRICT,
                              health_component_id TEXT NOT NULL,
                              updated_at INTEGER NOT NULL
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS managed_application_graph_component (
                              application_id TEXT NOT NULL REFERENCES managed_application_graph(application_id)
                                ON DELETE CASCADE,
                              component_id TEXT NOT NULL,
                              managed_application_id TEXT NOT NULL UNIQUE
                                REFERENCES managed_application(id) ON DELETE RESTRICT,
                              PRIMARY KEY (application_id, component_id)
                            )
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS managed_application_graph_dependency (
                              application_id TEXT NOT NULL,
                              component_id TEXT NOT NULL,
                              dependency_component_id TEXT NOT NULL,
                              PRIMARY KEY (application_id, component_id, dependency_component_id),
                              FOREIGN KEY (application_id, component_id)
                                REFERENCES managed_application_graph_component(application_id, component_id)
                                ON DELETE CASCADE,
                              FOREIGN KEY (application_id, dependency_component_id)
                                REFERENCES managed_application_graph_component(application_id, component_id)
                                ON DELETE RESTRICT,
                              CHECK(component_id <> dependency_component_id)
                            )
                            """);
                }
                if (version < 8 && !hasColumn(statement,
                        "managed_application_graph_component", "reviewed_runtime")) {
                    statement.execute("""
                            ALTER TABLE managed_application_graph_component
                            ADD COLUMN reviewed_runtime BLOB
                            """);
                }
                if (version < 9 && !hasColumn(statement,
                        "managed_application_graph_component", "reviewed_data_paths")) {
                    statement.execute("""
                            ALTER TABLE managed_application_graph_component
                            ADD COLUMN reviewed_data_paths BLOB
                            """);
                }
                if (version < 9) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS application_release_configuration_binding (
                              application_id TEXT NOT NULL,
                              release_identity TEXT NOT NULL,
                              configuration_revision INTEGER NOT NULL,
                              configuration_sha256 TEXT NOT NULL,
                              PRIMARY KEY (application_id, release_identity),
                              FOREIGN KEY (application_id, configuration_revision)
                                REFERENCES application_configuration_snapshot(application_id, revision)
                                ON DELETE RESTRICT
                            )
                            """);
                }
                if (version < 10 && !hasColumn(statement,
                        "managed_application_graph_component", "reviewed_resource_bindings")) {
                    statement.execute("""
                            ALTER TABLE managed_application_graph_component
                            ADD COLUMN reviewed_resource_bindings BLOB
                            """);
                }
                if (version < 11 && !hasColumn(statement,
                        "managed_application_graph", "application_health_check")) {
                    statement.execute("""
                            ALTER TABLE managed_application_graph
                            ADD COLUMN application_health_check BLOB
                            """);
                }
                if (version < 12) {
                    if (!hasColumn(statement, "managed_application_runtime_configuration", "identity_policy")) {
                        statement.execute("ALTER TABLE managed_application_runtime_configuration ADD COLUMN identity_policy TEXT NOT NULL DEFAULT 'LEGACY_UNSPECIFIED'");
                    }
                    if (!hasColumn(statement, "server", "host_key_format")) {
                        statement.execute("ALTER TABLE server ADD COLUMN host_key_format TEXT NOT NULL DEFAULT 'LEGACY_X509'");
                    }
                }
                if (version < 13) {
                    if (!hasColumn(statement, "server_profile", "display_name")) {
                        statement.execute("ALTER TABLE server_profile ADD COLUMN display_name TEXT NOT NULL DEFAULT ''");
                        statement.execute("UPDATE server_profile SET display_name=id");
                    }
                    if (!hasColumn(statement, "server_profile", "last_checked"))
                        statement.execute("ALTER TABLE server_profile ADD COLUMN last_checked TEXT");
                    if (!hasColumn(statement, "server_profile", "connected"))
                        statement.execute("ALTER TABLE server_profile ADD COLUMN connected INTEGER NOT NULL DEFAULT 0");
                    if (!hasColumn(statement, "server_profile", "operating_system"))
                        statement.execute("ALTER TABLE server_profile ADD COLUMN operating_system TEXT NOT NULL DEFAULT ''");
                }
                if (version < 14) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS external_application (
                              id TEXT PRIMARY KEY, server_id TEXT NOT NULL REFERENCES server_profile(id),
                              host TEXT NOT NULL, ssh_port INTEGER NOT NULL, username TEXT NOT NULL,
                              kind TEXT NOT NULL CHECK(kind IN ('SYSTEMD','DOCKER')), runtime_identity TEXT NOT NULL,
                              fingerprint TEXT NOT NULL, display_name TEXT NOT NULL, runtime_state TEXT NOT NULL,
                              can_start INTEGER NOT NULL, can_stop INTEGER NOT NULL, adopted_at TEXT NOT NULL,
                              observed_at TEXT NOT NULL, UNIQUE(server_id, kind, runtime_identity))
                            """);
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS application_presentation (
                              application_key TEXT PRIMARY KEY, display_name TEXT NOT NULL,
                              category TEXT NOT NULL CHECK(category IN ('WEBSITE','APP')), access_url TEXT)
                            """);
                }
                statement.execute("PRAGMA user_version = " + CURRENT_SCHEMA_VERSION);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static int schemaVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static boolean hasColumn(Statement statement, String table, String column) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}

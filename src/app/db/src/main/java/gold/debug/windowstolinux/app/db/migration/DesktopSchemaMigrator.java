package gold.debug.windowstolinux.app.db.migration;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;

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
    public static final int CURRENT_SCHEMA_VERSION = 3;

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
                      application_id TEXT PRIMARY KEY REFERENCES managed_application(id), artifact_sha256 TEXT NOT NULL,
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

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}

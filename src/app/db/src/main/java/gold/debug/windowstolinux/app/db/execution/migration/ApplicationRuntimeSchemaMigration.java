package gold.debug.windowstolinux.app.db.execution.migration;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Preserves old records for inspection while allowing complete typed runtime payloads. / 保留旧记录供检查并保存完整新运行契约。
 */
final class ApplicationRuntimeSchemaMigration {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ApplicationRuntimeSchemaMigration() { }
    /**
     * Applies application runtime schema migration.
     * <p>应用应用运行时结构迁移。
     *
     * @param statement statement / 语句
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void apply(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE managed_application_runtime_next (
                  application_id TEXT PRIMARY KEY REFERENCES managed_application(id) ON DELETE CASCADE,
                  health_kind TEXT NOT NULL CHECK(health_kind IN ('HTTP','TCP','TYPED')),
                  http_endpoint TEXT, http_expected_status INTEGER, tcp_port INTEGER,
                  health_timeout_seconds INTEGER NOT NULL CHECK(health_timeout_seconds BETWEEN 1 AND 300),
                  tcp_stability_seconds INTEGER, user_access_url TEXT,
                  identity_policy TEXT NOT NULL, runtime_payload BLOB,
                  CHECK(health_kind <> 'TYPED' OR runtime_payload IS NOT NULL))
                """);
        statement.execute("""
                INSERT INTO managed_application_runtime_next
                  (application_id,health_kind,http_endpoint,http_expected_status,tcp_port,health_timeout_seconds,
                   tcp_stability_seconds,user_access_url,identity_policy)
                SELECT application_id,health_kind,http_endpoint,http_expected_status,tcp_port,health_timeout_seconds,
                   tcp_stability_seconds,user_access_url,identity_policy FROM managed_application_runtime_configuration
                """);
        statement.execute("DROP TABLE managed_application_runtime_configuration");
        statement.execute("ALTER TABLE managed_application_runtime_next RENAME TO managed_application_runtime_configuration");
    }
}

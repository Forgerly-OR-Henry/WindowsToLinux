package gold.debug.windowstolinux.app.db.execution.migration;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Adds global ordering while preserving all legacy providers, role bindings and credential references. / 增加全局顺序，保留全部旧提供者、角色绑定及凭据引用。
 */
final class AiPrioritySchemaMigration {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private AiPrioritySchemaMigration() { }
    /**
     * Applies ai priority schema migration.
     * <p>应用AI优先级结构迁移。
     *
     * @param statement statement / 语句
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void apply(Statement statement) throws SQLException {
        statement.execute("""
                INSERT OR IGNORE INTO ai_provider_profile (profile_id, endpoint, model, credential_key, credential_mode)
                SELECT id, endpoint, model, credential_key, credential_mode FROM ai_profile
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS ai_provider_control (
                  profile_id TEXT PRIMARY KEY REFERENCES ai_provider_profile(profile_id) ON DELETE RESTRICT,
                  display_name TEXT NOT NULL, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
                  priority INTEGER NOT NULL CHECK(priority>=0), verified_at TEXT)
                """);
        statement.execute("""
                INSERT OR IGNORE INTO ai_provider_control (profile_id, display_name, enabled, priority, verified_at)
                SELECT p.profile_id, p.profile_id,
                  CASE WHEN EXISTS(SELECT 1 FROM ai_role_assignment r WHERE r.profile_id=p.profile_id) THEN 1 ELSE 0 END,
                  ROW_NUMBER() OVER (ORDER BY
                    CASE WHEN EXISTS(SELECT 1 FROM ai_role_assignment r WHERE r.profile_id=p.profile_id AND r.role='PROJECT_ANALYSIS') THEN 0
                         WHEN EXISTS(SELECT 1 FROM ai_role_assignment r WHERE r.profile_id=p.profile_id) THEN 1 ELSE 2 END,
                    p.profile_id) - 1, NULL
                FROM ai_provider_profile p
                """);
    }
}

package gold.debug.windowstolinux.app.db.execution.migration;

import java.sql.SQLException;
import java.sql.Statement;

/** Separates inventory ordering, verified capabilities and purpose membership. / 分离清单排序、已验证能力和用途成员。
 */
final class AiPurposeSchemaMigration {
    /** Prevents construction. / 禁止实例化。 */
    private AiPurposeSchemaMigration() {
    }

    /** Migrates existing profiles without changing credential references. / 迁移既有模型且不改变凭据引用。
     * @param statement current migration transaction / 当前迁移事务
     * @throws SQLException if migration fails / 迁移失败时
     */
    static void apply(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE ai_model_inventory (
                  profile_id TEXT PRIMARY KEY REFERENCES ai_provider_profile(profile_id) ON DELETE RESTRICT,
                  display_name TEXT NOT NULL, display_order INTEGER NOT NULL CHECK(display_order>=0),
                  revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0))
                """);
        statement.execute("""
                CREATE TABLE ai_model_verification (
                  profile_id TEXT NOT NULL REFERENCES ai_model_inventory(profile_id) ON DELETE CASCADE,
                  capability TEXT NOT NULL CHECK(capability IN ('TEXT','VISION')),
                  revision INTEGER NOT NULL, verified_at TEXT NOT NULL,
                  PRIMARY KEY(profile_id,capability))
                """);
        statement.execute("""
                CREATE TABLE ai_model_purpose (
                  purpose TEXT NOT NULL CHECK(purpose IN ('DEPLOYMENT','APPROVAL','VISION')),
                  profile_id TEXT NOT NULL REFERENCES ai_model_inventory(profile_id) ON DELETE RESTRICT,
                  priority INTEGER NOT NULL CHECK(priority>=0), enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
                  PRIMARY KEY(purpose,profile_id), UNIQUE(purpose,priority))
                """);
        statement.execute("""
                INSERT INTO ai_model_inventory(profile_id,display_name,display_order)
                SELECT profile_id,display_name,ROW_NUMBER() OVER(ORDER BY model_group,priority,profile_id)-1
                FROM ai_provider_control
                """);
        statement.execute("""
                INSERT INTO ai_model_verification(profile_id,capability,revision,verified_at)
                SELECT profile_id,CASE model_group WHEN 'VISION' THEN 'VISION' ELSE 'TEXT' END,1,verified_at
                FROM ai_provider_control WHERE verified_at IS NOT NULL
                """);
        statement.execute("""
                INSERT INTO ai_model_purpose(purpose,profile_id,priority,enabled)
                SELECT CASE model_group WHEN 'VISION' THEN 'VISION' ELSE 'DEPLOYMENT' END,profile_id,
                  ROW_NUMBER() OVER(PARTITION BY model_group ORDER BY priority,profile_id)-1,enabled
                FROM ai_provider_control
                """);
        statement.execute("DROP TABLE ai_provider_control");
    }
}

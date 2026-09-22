package gold.debug.windowstolinux.app.db.execution.migration;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Preserves existing models and records nonsecret rescue state. / 保留已有模型并记录非秘密救援状态。
 */
final class BrowserRecoverySchemaMigration {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private BrowserRecoverySchemaMigration() {
    }

    /**
     * Applies browser recovery schema migration.
     * <p>应用浏览器恢复结构迁移。
     *
     * @param statement statement / 语句
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void apply(Statement statement) throws SQLException {
        boolean grouped = false;
        try (var columns = statement.executeQuery("PRAGMA table_info(ai_provider_control)")) {
            while (columns.next())
                if (columns.getString("name").equals("model_group"))
                    grouped = true;
        }
        if (!grouped)
            statement.execute(
                    "ALTER TABLE ai_provider_control ADD COLUMN model_group TEXT NOT NULL DEFAULT 'REGULAR' CHECK(model_group IN ('REGULAR','VISION'))");
        statement.execute(
                "CREATE TABLE IF NOT EXISTS browser_recovery (id TEXT PRIMARY KEY, server_id TEXT NOT NULL, operation_id TEXT NOT NULL, state TEXT NOT NULL, action_digest TEXT NOT NULL DEFAULT '', event_code TEXT NOT NULL, updated_at TEXT NOT NULL)");
        statement.execute(
                "CREATE TABLE IF NOT EXISTS browser_recovery_event (sequence INTEGER PRIMARY KEY, recovery_id TEXT NOT NULL, state TEXT NOT NULL, action_digest TEXT NOT NULL, event_code TEXT NOT NULL, created_at TEXT NOT NULL)");
    }
}

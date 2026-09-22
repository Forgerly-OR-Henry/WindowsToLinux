package gold.debug.windowstolinux.app.db.execution.migration;

import java.sql.*;

/** Adds nonsecret task and append-only action evidence in the migration transaction. / 在迁移事务内增加非秘密任务及追加动作证据。 */
final class AgentTaskSchemaMigration {
    /** Prevents construction. / 禁止实例化。 */
    private AgentTaskSchemaMigration() {
    }

    /** Creates schema 19 without altering existing model credentials. / 创建 schema 19，不更改既有模型凭据。
     * @param statement current migration statement / 当前迁移语句
     * @throws SQLException when any schema change fails / 任一模式更改失败时
     */
    static void apply(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE deployment_agent_task(
                  id TEXT PRIMARY KEY, server_id TEXT NOT NULL, target_digest TEXT NOT NULL,
                  automation_mode TEXT NOT NULL, approval_mode TEXT NOT NULL, configuration_digest TEXT NOT NULL,
                  deployment_skill TEXT NOT NULL, approval_skill TEXT NOT NULL,
                  state TEXT NOT NULL, started_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                """);
        statement.execute("""
                CREATE TABLE deployment_agent_event(
                  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_id TEXT NOT NULL REFERENCES deployment_agent_task(id) ON DELETE RESTRICT,
                  event_type TEXT NOT NULL, action_id TEXT NOT NULL, detail TEXT NOT NULL, created_at TEXT NOT NULL)
                """);
        statement.execute("CREATE INDEX deployment_agent_events_by_task ON deployment_agent_event(task_id,sequence)");
    }
}

package gold.debug.windowstolinux.app.db.execution.migration;

import java.sql.*;

/** Preserves legacy execution meaning while versioning new task evidence. / 保留旧执行含义并对新任务证据分版本。 */
final class AutonomousTaskSchemaMigration {
    /** Prevents construction. / 禁止实例化。 */
    private AutonomousTaskSchemaMigration() {
    }

    /** Runs inside the owning schema transaction. / 在所属模式事务中运行。
     * @param statement migration statement / 迁移语句
     * @throws SQLException when migration cannot complete atomically / 迁移无法原子完成时
     */
    static void apply(Statement statement) throws SQLException {
        statement.execute(
                "ALTER TABLE deployment_agent_task ADD COLUMN execution_semantics_version INTEGER NOT NULL DEFAULT 1 CHECK(execution_semantics_version IN (1,2))");
        statement.execute(
                "CREATE INDEX deployment_agent_operation_evidence ON deployment_agent_event(task_id,action_id,event_type,sequence)");
    }
}

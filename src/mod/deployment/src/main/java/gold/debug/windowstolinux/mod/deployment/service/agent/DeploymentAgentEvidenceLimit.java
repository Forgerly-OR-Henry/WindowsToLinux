package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Bounds new observations while retaining every existing formal recovery proof. / 保留每项既有正式恢复证明，同时限制新增观察。 */
final class DeploymentAgentEvidenceLimit {
    /** Prevents construction of a JDBC-only policy. / 阻止构造仅JDBC策略。 */
    private DeploymentAgentEvidenceLimit() {}
    /** Locks one original row and reserves room for mandatory final evidence. / 锁定一项原行，并为必需最终依据保留空间。
     * @param connection actual transactional owner connection / 实际事务所有者连接
     * @param task original selected UUID / 原所选UUID
     * @param optional whether this is optional control or inspection evidence / 是否可选控制或检查依据
     * @throws SQLException if identity or bounded capacity cannot be proved / 无法证明身份或有界容量时 */
    static void require(Connection connection, UUID task, boolean optional) throws SQLException {
        try (var lock = connection.prepareStatement("SELECT task_id FROM deployment_agent_task WHERE task_id=? FOR UPDATE")) {
            lock.setString(1, task.toString());
            try (var rows = lock.executeQuery()) { if (!rows.next()) throw new SQLException("Original agent missing"); }
        }
        try (var count = connection.prepareStatement("SELECT COUNT(*) FROM deployment_agent_event WHERE task_id=?")) {
            count.setString(1, task.toString());
            try (var rows = count.executeQuery()) {
                if (!rows.next() || rows.getLong(1) >= (optional ? 9800 : 10000))
                    throw new SQLException("Original agent evidence capacity exhausted; no proof is deleted");
            }
        }
    }
}

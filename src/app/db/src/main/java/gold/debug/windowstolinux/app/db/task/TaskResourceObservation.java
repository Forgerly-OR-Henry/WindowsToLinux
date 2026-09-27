package gold.debug.windowstolinux.app.db.task;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;

/** Read-only observation of the same explicit-resource predicate used by task finalization. / 对任务收尾所用相同显式资源判据进行只读观察。 */
public final class TaskResourceObservation {
    /** Original platform database. / 原平台库。 */
    private final PlatformDatabase database;

    /** Binds platform storage without taking ownership of task cleanup. / 绑定平台存储但不接管任务清理。
     * @param database actual platform database / 实际平台库 */
    public TaskResourceObservation(PlatformDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Checks all actual explicit leases, independent of resource kind or owner. / 不分资源类别或所有者检查全部真实显式占用。
     * @param taskId authenticated original task / 已鉴权原任务
     * @return true only if no explicit lease remains / 仅无显式占用残留时为真
     * @throws SQLException if the task is absent or storage cannot be read / 任务不存在或存储不可读时 */
    public boolean released(UUID taskId) throws SQLException {
        try (var connection = database.openConnection();
                var query = connection.prepareStatement(
                        "SELECT COUNT(r.lease_id) FROM platform_task t LEFT JOIN platform_task_resource r ON r.task_id=t.task_id AND r.released_at IS NULL AND (r.recovery_json IS NULL OR r.recovery_json<>?) WHERE t.task_id=? GROUP BY t.task_id")) {
            query.setString(1, ResourceRecovery.TASK_END);
            query.setObject(2, Objects.requireNonNull(taskId));
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    throw new SQLException("Original task is absent");
                return rows.getLong(1) == 0;
            }
        }
    }
}

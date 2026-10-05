package gold.debug.windowstolinux.app.db.task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;

/** Releases only previous-boot host workflow occupancy, never permission. / 仅释放前次启动宿主流程占用，不授予权限。 */
public final class WorkflowRecoveryStore {
    /** Original protected engine. / 原受保护引擎。 */
    private final PlatformDatabase database;

    /** Serialized transactional connection owner. / 串行事务连接所有者。 */
    private final StoreConnections connections;

    /** Binds a host-only recovery store without changing schema. / 绑定仅宿主恢复仓库，不改变结构。
     * @param database actual protected engine / 实际受保护引擎 */
    public WorkflowRecoveryStore(PlatformDatabase database) {
        this.database = java.util.Objects.requireNonNull(database);
        connections = new StoreConnections(database::openConnection);
    }

    /** Releases exact expired leases after original-root recovery authenticates the callback. / 原根恢复认证回调后释放精确过期占用。
     * @param taskId original retained task / 原保留任务
     * @param owner exact original root / 精确原根
     * @param version exact original root release / 精确原根发布
     * @throws SQLException if any original fact or durable release is uncertain / 任一原依据或持久释放不确定时 */
    public void releasePrevious(UUID taskId, String owner, String version) throws SQLException {
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
                UUID originalBoot = originalBoot(connection, taskId, owner, version);
                if (database.bootId().equals(originalBoot)) return;
                var leases = new LinkedHashMap<UUID, ResourceRecovery>();
                try (var query = connection.prepareStatement("SELECT lease_id,resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='CUSTOM' AND released_at IS NULL")) {
                    query.setObject(1, taskId);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            String id = rows.getString(2);
                            if (!id.startsWith("workflow-delegation-")) continue;
                            try {
                                var expected = ResourceRecovery.decode(rows.getString(5));
                                var fields = expected.fields();
                                if (!id.equals("workflow-delegation-" + UUID.fromString(id.substring("workflow-delegation-".length())))
                                    || !owner.equals(rows.getString(3)) || !"SHARED".equals(rows.getString(4))
                                    || !expected.protocol().equals("workflow.v1") || !fields.keySet().equals(Set.of("taskId", "owner", "version", "bootId"))
                                    || !fields.equals(Map.of("taskId", taskId.toString(), "owner", owner, "version", version, "bootId", UUID.fromString(fields.get("bootId")).toString())))
                                    throw new IllegalArgumentException("Expired workflow identity differs");
                                if (database.bootId().equals(UUID.fromString(fields.get("bootId")))) continue;
                                leases.put(rows.getObject(1, UUID.class), expected);
                            } catch (IllegalArgumentException invalid) {
                                throw new SQLException("Expired workflow identity cannot be proved", invalid);
                            }
                        }
                    }
                }
                try (var update = connection.prepareStatement("UPDATE platform_task_resource SET released_at=? WHERE task_id=? AND lease_id=? AND resource_kind='CUSTOM' AND owner_id=? AND access_mode='SHARED' AND recovery_json=? AND released_at IS NULL")) {
                    for (var lease : leases.entrySet()) {
                        update.setLong(1, System.currentTimeMillis());
                        update.setObject(2, taskId);
                        update.setObject(3, lease.getKey());
                        update.setString(4, owner);
                        update.setString(5, lease.getValue().encode());
                        if (update.executeUpdate() != 1) throw new SQLException("Expired workflow lease changed");
                    }
                }
            }
        });
    }

    /** Reads only the exact original recoverable root, retaining its previous boot. / 仅读取精确原可恢复根并保留其前次启动。
     * @param connection serialized transaction / 串行事务
     * @param taskId original task / 原任务
     * @param owner original root / 原根
     * @param version original release / 原发布
     * @return original boot identity / 原启动身份
     * @throws SQLException if the original root or recovery state differs / 原根或恢复状态不同 */
    private static UUID originalBoot(Connection connection, UUID taskId, String owner, String version) throws SQLException {
        try (var query = connection.prepareStatement("SELECT boot_id,owner_id,owner_version,state FROM platform_task WHERE task_id=?")) {
            query.setObject(1, taskId);
            try (var row = query.executeQuery()) {
                if (!row.next() || !owner.equals(row.getString(2)) || !version.equals(row.getString(3))
                    || !Set.of("UNKNOWN", "FINISHING").contains(row.getString(4)))
                    throw new SQLException("Original workflow root is not recoverable");
                return row.getObject(1, UUID.class);
            }
        }
    }
}

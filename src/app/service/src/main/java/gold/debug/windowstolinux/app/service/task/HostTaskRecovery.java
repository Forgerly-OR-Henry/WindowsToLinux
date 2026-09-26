package gold.debug.windowstolinux.app.service.task;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;

/** Verifies vanished process-local resources of a known host protocol. / 核验已知宿主协议消失的进程内资源。 */
public final class HostTaskRecovery {
    /** Prevents instances of the stateless verifier. / 禁止无状态核验器实例。 */
    private HostTaskRecovery() {
    }

    /** Releases only a previous boot's exact protocol and allowlisted resource identities. / 仅释放前次启动的精确协议及允许的资源身份。
     * @param database exclusively owned current engine / 独占持有的当前引擎
     * @param task authenticated recovery snapshot / 已认证恢复快照
     * @param allowedPrefixes allowed resource kinds and UUID prefixes / 允许的资源种类及 UUID 前缀
     * @return whether previous-process cleanup was committed / 是否提交前次进程清理
     * @throws SQLException if ownership or cleanup cannot be proved / 无法证明归属或完成清理时 */
    public static boolean releasePreviousProcess(PlatformDatabase database, TaskSnapshot task,
            Map<String, String> allowedPrefixes) throws SQLException {
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections.lockPlatformState(connection);
            try (var statement = connection.prepareStatement(
                    "SELECT boot_id,owner_id,owner_version,task_type,state FROM platform_task WHERE task_id=?")) {
                statement.setObject(1, task.id());
                try (var row = statement.executeQuery()) {
                    if (!row.next() || !PlatformTaskAdmission.PLATFORM_OWNER.equals(row.getString(2))
                            || !task.ownerVersion().equals(row.getString(3)) || !task.type().equals(row.getString(4))
                            || !Set.of("UNKNOWN", "FINISHING").contains(row.getString(5)))
                        throw new SQLException("Host recovery identity changed");
                    if (database.bootId().equals(row.getObject(1, UUID.class)))
                        return false;
                }
            }
            var leases = new ArrayList<UUID>();
            try (var statement = connection.prepareStatement(
                    "SELECT lease_id,resource_kind,resource_id,owner_id,recovery_json FROM platform_task_resource WHERE task_id=? AND released_at IS NULL")) {
                statement.setObject(1, task.id());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if ("{\"release\":\"TASK_END\"}".equals(rows.getString(5)))
                            continue;
                        String kind = rows.getString(2), id = rows.getString(3);
                        String prefix = allowedPrefixes.get(kind);
                        boolean explicit = "{\"release\":\"EXPLICIT\"}".equals(rows.getString(5));
                        if (kind.equals("CONNECTION") && "ssh-".equals(prefix)) {
                            var facts = gold.debug.windowstolinux.app.db.task.ResourceRecovery
                                    .decode(rows.getString(5));
                            try {
                                explicit = facts.protocol().equals("ssh.v1")
                                        && facts.fields().keySet().equals(Set.of("bootId", "serverId"))
                                        && !facts.fields().get("serverId").isBlank()
                                        && !database.bootId().equals(UUID.fromString(facts.fields().get("bootId")));
                            } catch (IllegalArgumentException | NullPointerException invalid) {
                                throw new SQLException("SSH physical boot is invalid", invalid);
                            }
                        }
                        if (!PlatformTaskAdmission.PLATFORM_OWNER.equals(rows.getString(4)) || !explicit
                                || prefix == null || !id.startsWith(prefix))
                            throw new SQLException("Host recovery contains an unknown resource");
                        try {
                            UUID.fromString(id.substring(prefix.length()));
                        } catch (IllegalArgumentException invalid) {
                            throw new SQLException("Host resource identity is invalid");
                        }
                        leases.add(rows.getObject(1, UUID.class));
                    }
                }
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE platform_task_resource SET released_at=? WHERE lease_id=? AND task_id=? AND released_at IS NULL")) {
                for (var lease : leases) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setObject(2, lease);
                    statement.setObject(3, task.id());
                    if (statement.executeUpdate() != 1)
                        throw new SQLException("Host resource changed during recovery");
                }
            }
            connection.commit();
            return true;
        }
    }
}

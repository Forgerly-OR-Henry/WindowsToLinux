package gold.debug.windowstolinux.app.db.task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Orders resource claims and writes lease rows on the caller's original transaction. / 在调用方原事务上整理资源申请并写入占用行。 */
final class TaskResourceAdmission {
    /** Prevents construction. / 禁止构造。 */
    private TaskResourceAdmission() {
    }

    /** Sorts and rejects duplicate claims in the required global order. / 按要求的全局顺序排序并拒绝重复申请。
     * @param claims complete claim set / 完整申请集合
     * @return immutable ordered claims / 不可变有序申请
     * @throws SQLException if a resource appears twice / 同一资源重复出现时
     */
    static List<TaskResource> orderedClaims(List<TaskResource> claims) throws SQLException {
        var keys = new HashSet<String>();
        for (var r : claims)
            if (!keys.add(r.kind() + "\0" + r.id()))
                throw new SQLException("Duplicate task resource");
        return claims.stream().sorted(Comparator.comparingInt((TaskResource r) -> switch (r.kind()) {
            case "MOD" -> 0;
            case "SCHEMA" -> 1;
            case "SERVER" -> 2;
            default -> 3;
        }).thenComparing(TaskResource::id)).toList();
    }

    /** Inserts a claim within the already serialized transaction. / 在已串行化的事务内写入申请。
     * @param c scoped platform connection / 作用域平台连接
     * @param taskId owning task / 所属任务
     * @param resource exact resource claim / 精确资源申请
     * @param metadata validated release and recovery envelope / 已校验释放及恢复封套
     * @return unique lease identity / 唯一占用身份
     * @throws SQLException if persistence fails / 持久化失败时
     */
    static UUID insertLease(Connection c, UUID taskId, TaskResource resource, String metadata) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var p = c.prepareStatement(
            "INSERT INTO platform_task_resource(lease_id,task_id,resource_kind,resource_id,owner_id,access_mode,acquired_at,recovery_json) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setObject(1, id);
            p.setObject(2, taskId);
            p.setString(3, resource.kind());
            p.setString(4, resource.id());
            p.setString(5, resource.ownerId());
            p.setString(6, resource.exclusive() ? "EXCLUSIVE" : "SHARED");
            p.setLong(7, System.currentTimeMillis());
            p.setString(8, metadata);
            p.executeUpdate();
        }
        return id;
    }
}

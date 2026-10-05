package gold.debug.windowstolinux.app.service.file;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;

/** Reads exact retained material resources without inventing absent custody. / 读取精确保留素材资源，不虚构缺失接管。 */
final class PlatformMaterialStore {
    /** Root resource identity prefix. / 根资源身份前缀。 */
    static final String ROOT = "material-v1:";

    /** Original hold identity prefix. / 原持有身份前缀。 */
    static final String HOLD = "material-hold-v1:";

    /** Original engine used only for bounded metadata. / 仅用于有界元数据的原引擎。 */
    private final PlatformDatabase database;

    /** Borrows the original platform engine. / 借用原平台引擎。
     * @param database original engine / 原引擎 */
    PlatformMaterialStore(PlatformDatabase database) {
        this.database = database;
    }

    /** Reads one exact original root, including finalized metadata. / 读取一个精确原根，包括已终结元数据。
     * @param task original task / 原任务
     * @param id original material identity / 原素材身份
     * @return exact retained root / 精确保留根
     * @throws SQLException if missing, duplicated or malformed / 缺失、重复或损坏时 */
    Root root(UUID task, UUID id) throws SQLException {
        return findRoot(task, id).orElseThrow(() -> new SQLException("Original material root is absent"));
    }

    /** Distinguishes proven absence from malformed original custody. / 区分已证缺失与损坏原接管。
     * @param task original task / 原任务
     * @param id original material identity / 原素材身份
     * @return exact retained row or proven absence / 精确保留行或已证缺失
     * @throws SQLException if duplicated or malformed / 重复或损坏时 */
    Optional<Root> findRoot(UUID task, UUID id) throws SQLException {
        try (var c = database.openConnection(); var p = c.prepareStatement(
            "SELECT lease_id,owner_id,recovery_json,released_at,task_id FROM platform_task_resource WHERE resource_kind='FILE' AND resource_id=?")) {
            p.setString(1, ROOT + id);
            try (var rows = p.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                if (!rows.getObject(5, UUID.class).equals(task))
                    throw new SQLException("Material belongs to another original task");
                var header = new PlatformMaterialHeader(ResourceRecovery.decode(rows.getString(3)));
                var resource = new TaskResource("FILE", ROOT + id, rows.getString(2), false);
                var result = new Root(rows.getObject(1, UUID.class), resource, header, rows.getObject(4) != null);
                if (rows.next() || !header.field("materialId").equals(id.toString())
                    || !header.field("taskId").equals(task.toString()) || !header.field("consumerId").equals(resource.ownerId())
                    || result.released() != header.state().equals("RELEASED"))
                    throw new SQLException("Original material root differs");
                return Optional.of(result);
            }
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Invalid original material root", failure);
        }
    }

    /** Reads all exact unreleased holds of this original root. / 读取此原根全部精确未释放持有。
     * @param task original task / 原任务
     * @param id original material / 原素材
     * @return exact persisted holds / 精确持久持有
     * @throws SQLException if any original row is invalid / 任一原行无效时 */
    List<Hold> holds(UUID task, UUID id) throws SQLException {
        var result = new ArrayList<Hold>();
        try (var c = database.openConnection(); var p = c.prepareStatement(
            "SELECT lease_id,resource_id,owner_id,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE' AND resource_id LIKE ? AND released_at IS NULL")) {
            p.setObject(1, task);
            p.setString(2, HOLD + id + "/%");
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var recovery = ResourceRecovery.decode(rows.getString(4));
                    var fields = recovery.fields();
                    if (!recovery.protocol().equals("material-hold.v1") || !fields.keySet().equals(Set.of("materialId",
                        "holdId", "taskId", "consumerId", "consumerVersion", "materialDigest", "revision", "bootId", "state"))
                        || !fields.get("materialId").equals(id.toString()) || !fields.get("taskId").equals(task.toString())
                        || !fields.get("consumerId").equals(rows.getString(3)) || !fields.get("state").equals("ACTIVE")
                        || !rows.getString(2).equals(HOLD + id + "/" + PlatformMaterialHeader.uuid(fields.get("holdId")))
                        || !fields.get("materialDigest").matches("[0-9a-f]{64}") || fields.get("revision").isBlank())
                        throw new SQLException("Invalid original material hold");
                    PlatformMaterialHeader.uuid(fields.get("bootId"));
                    result.add(new Hold(rows.getObject(1, UUID.class), new TaskResource("FILE", rows.getString(2), rows.getString(3), false), recovery));
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Invalid original material hold", failure);
        }
        return List.copyOf(result);
    }

    /** Protects every workspace already named by retained root custody. / 保护每个已被保留根接管命名的工作目录。
     * @param task original task / 原任务
     * @param workspace original physical identity / 原物理身份
     * @return whether custody was persisted / 是否已持久接管
     * @throws SQLException if custody cannot be safely inspected / 无法安全核查接管时 */
    boolean custody(UUID task, UUID workspace) throws SQLException {
        try (var c = database.openConnection(); var p = c.prepareStatement(
            "SELECT recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE' AND resource_id LIKE 'material-v1:%'")) {
            p.setObject(1, task);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var header = new PlatformMaterialHeader(ResourceRecovery.decode(rows.getString(1)));
                    if (header.field("workspaceId").equals(workspace.toString()))
                        return true;
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Material custody evidence is uncertain", failure);
        }
        return false;
    }

    /** Rejects publication while any original producer process remains durably occupied. / 任一原生产方进程仍持久占用时拒绝发布。
     * @param task authentic original task / 真实原任务
     * @param producer exact original producer / 精确原生产方
     * @return whether all original producer processes have been released / 是否全部原生产方进程已释放
     * @throws SQLException if original occupancy cannot be read / 无法读取原占用时 */
    boolean quiescent(UUID task, String producer) throws SQLException {
        try (var c = database.openConnection(); var p = c.prepareStatement(
            "SELECT lease_id FROM platform_task_resource WHERE task_id=? AND owner_id=? AND resource_kind='PROCESS' AND released_at IS NULL")) {
            p.setObject(1, task);
            p.setString(2, producer);
            try (var rows = p.executeQuery()) {
                return !rows.next();
            }
        }
    }

    /** Reads the exact original workspace row without choosing a substitute. / 读取精确原工作目录行，不选择替代项。
     * @param header trusted original custody / 可信原接管
     * @return precise retained workspace lease / 精确保留工作目录占用
     * @throws SQLException if missing, released or mismatched / 缺失、已释放或不匹配时 */
    UUID workspaceLease(PlatformMaterialHeader header) throws SQLException {
        try (var c = database.openConnection(); var p = c.prepareStatement(
            "SELECT lease_id,owner_id,released_at FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE' AND resource_id=?")) {
            p.setObject(1, PlatformMaterialHeader.uuid(header.field("taskId")));
            p.setString(2, "source-workspace-v1:" + header.field("workspaceId"));
            try (var rows = p.executeQuery()) {
                if (!rows.next() || !rows.getString(2).equals(header.field("physicalOwnerId")) || rows.getObject(3) != null)
                    throw new SQLException("Original unpublished workspace differs");
                UUID lease = rows.getObject(1, UUID.class);
                if (rows.next())
                    throw new SQLException("Original unpublished workspace is duplicated");
                return lease;
            }
        }
    }

    /** Exact retained root row. / 精确保留根行。
     * @param lease original lease / 原占用
     * @param resource original resource / 原资源
     * @param header original facts / 原事实
     * @param released durable release confirmed / 持久释放已确认 */
    record Root(UUID lease, TaskResource resource, PlatformMaterialHeader header, boolean released) {
    }

    /** Exact retained read row. / 精确保留读行。
     * @param lease original lease / 原占用
     * @param resource original resource / 原资源
     * @param recovery original hold facts / 原持有事实 */
    record Hold(UUID lease, TaskResource resource, ResourceRecovery recovery) {
    }
}

package gold.debug.windowstolinux.app.db.task;

import java.sql.*;
import java.util.*;

/** Checks exact FILE custody transitions inside the original transaction. / 在原事务内检查精确 FILE 保管转移。
 */
final class FileMaterialTransition {
    /** Exact material root fields. / 精确素材根字段。
     */
    private static final Set<String> ROOT = Set.of("materialId", "operationId", "taskId", "producerId", "producerVersion", "producerDigest", "producerSchemaVersion", "producerSchemaGeneration", "consumerId", "consumerVersion", "consumerDigest", "consumerSchemaVersion", "consumerSchemaGeneration", "workspaceId", "physicalOwnerId", "physicalIdentity", "revision", "manifestSha256", "byteCount", "fileCount", "state", "recordRevision");

    /** Exact read hold fields. / 精确读取持有字段。
     */
    private static final Set<String> HOLD = Set.of("materialId", "holdId", "taskId", "consumerId", "consumerVersion", "materialDigest", "revision", "bootId", "state");

    /** Keeps transition implementation construction private. / 保持转移实现构造私有。
     */
    private FileMaterialTransition() {
    }

    /** Requires original root and provider release identities for this material. / 要求此素材的原根及提供方发布身份。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task identity / 原已接纳任务身份
     * @param resource exact original resource / 精确原资源
     * @param value bounded exact recovery value / 有界精确恢复值
     * @param graph exact retained original task graph / 精确保留原任务图
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void rootIdentity(Connection c, UUID task, TaskResource resource, ResourceRecovery value, TaskRecoveryGraph graph) throws SQLException {
        var f = value.fields();
        if (!value.protocol().equals("material.v1") || !f.keySet().equals(ROOT) || !resource.kind().equals("FILE") || resource.exclusive()
            || !resource.id().equals("material-v1:" + f.get("materialId")) || !resource.ownerId().equals(f.get("consumerId")) || !task.toString().equals(f.get("taskId")))
            throw new SQLException("Invalid material root identity");
        try {
            canonicalUuid(f.get("materialId"));
            canonicalUuid(f.get("operationId"));
            canonicalUuid(f.get("workspaceId"));
            if (!Set.of("CAPTURING", "PUBLISHED", "RELEASE_REQUESTED", "RELEASED").contains(f.get("state")) || !f.get("physicalOwnerId").equals(f.get("producerId")) || f.get("physicalIdentity").isBlank() || f.get("revision").isBlank())
                throw new IllegalArgumentException();
            number(f.get("recordRevision"));
            number(f.get("byteCount"));
            number(f.get("fileCount"));
            if (f.get("state").equals("CAPTURING") && !f.get("manifestSha256").isEmpty())
                throw new IllegalArgumentException();
            if (f.get("manifestSha256").isEmpty() && !f.get("state").equals("PUBLISHED")) {
                if (!f.get("byteCount").equals("0") || !f.get("fileCount").equals("0"))
                    throw new IllegalArgumentException();
            } else if (!f.get("manifestSha256").matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            if (!graph.owner().equals(f.get("consumerId"))) throw new IllegalArgumentException();
            release(graph, f, "consumer");
            release(graph, f, "producer");
        } catch (IllegalArgumentException | NoSuchElementException invalid) {
            throw new SQLException("Material differs from original task graph", invalid);
        }
        try (var p = c.prepareStatement("SELECT owner_id,owner_version,state FROM platform_task WHERE task_id=?")) {
            p.setObject(1, task);
            try (var r = p.executeQuery()) {
                if (!r.next() || !r.getString(1).equals(f.get("consumerId")) || !r.getString(2).equals(f.get("consumerVersion")) || !Set.of("RUNNING", "CANCEL_REQUESTED", "UNKNOWN", "FINISHING").contains(r.getString(3)))
                    throw new SQLException("Original material task is unavailable");
            }
        }
    }

    /** Compares one material party with its retained original release. / 比较一个素材参与方与其保留原发布。
     * @param graph exact retained original task graph / 精确保留原任务图
     * @param f validated material identity fields / 已校验素材身份字段
     * @param prefix original producer or consumer field prefix / 原生产或消费方字段前缀
     */
    private static void release(TaskRecoveryGraph graph, Map<String, String> f, String prefix) {
        var r = graph.release(f.get(prefix + "Id"));
        if (!r.version().equals(f.get(prefix + "Version")) || !r.digest().equals(f.get(prefix + "Digest"))
            || !r.schema().map(s -> s.schemaVersion()).orElse("").equals(f.get(prefix + "SchemaVersion"))
            || !r.schema().map(s -> s.generation().toString()).orElse("").equals(f.get(prefix + "SchemaGeneration")))
            throw new IllegalArgumentException("Original release changed");
    }

    /** Closes new hold acquisition using an exact published-root CAS. / 通过精确已发布根比较更新封闭新持有获取。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task identity / 原已接纳任务身份
     * @param lease exact durable lease identity / 精确持久占用身份
     * @param resource exact original resource / 精确原资源
     * @param expected complete expected prior recovery facts / 完整预期先前恢复依据
     * @param next validated next recovery facts / 已校验下一恢复依据
     * @param graph exact retained original task graph / 精确保留原任务图
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void checkpoint(Connection c, UUID task, UUID lease, TaskResource resource, ResourceRecovery expected, ResourceRecovery next, TaskRecoveryGraph graph) throws SQLException {
        rootIdentity(c, task, resource, expected, graph);
        rootIdentity(c, task, resource, next, graph);
        if (!Set.of("CAPTURING", "PUBLISHED").contains(expected.fields().get("state")))
            throw new SQLException("Material cannot request release in this state");
        transition(expected, next, expected.fields().get("state"), "RELEASE_REQUESTED", Set.of("state", "recordRevision"));
        var row = row(c, task, lease, resource);
        if (row.released) throw new SQLException("Material root is already released");
        if (row.header.equals(next)) return;
        if (!row.header.equals(expected)) throw new SQLException("Material checkpoint changed");
        update(c, lease, next, false);
    }

    /** Publishes custody and detaches the original workspace atomically. / 原子发布保管并脱离原工作区。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task identity / 原已接纳任务身份
     * @param workspaceLease original workspace lease / 原工作区占用
     * @param workspace original source workspace identity / 原源码工作区身份
     * @param materialLease original material root lease / 原素材根占用
     * @param material original material root identity / 原素材根身份
     * @param expected complete expected prior recovery facts / 完整预期先前恢复依据
     * @param published complete immutable published header / 完整不可变发布头
     * @param graph exact retained original task graph / 精确保留原任务图
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void transfer(Connection c, UUID task, UUID workspaceLease, TaskResource workspace, UUID materialLease, TaskResource material, ResourceRecovery expected, ResourceRecovery published, TaskRecoveryGraph graph) throws SQLException {
        rootIdentity(c, task, material, expected, graph);
        rootIdentity(c, task, material, published, graph);
        transition(expected, published, "CAPTURING", "PUBLISHED", Set.of("state", "recordRevision", "manifestSha256", "byteCount", "fileCount"));
        if (!workspace.kind().equals("FILE") || workspace.exclusive() || !workspace.id().equals("source-workspace-v1:" + expected.fields().get("workspaceId")) || !workspace.ownerId().equals(expected.fields().get("physicalOwnerId")))
            throw new SQLException("Transferred workspace identity differs");
        var source = row(c, task, workspaceLease, workspace);
        var root = row(c, task, materialLease, material);
        if (!ResourceRecovery.EXPLICIT.equals(source.raw)) throw new SQLException("Workspace is not an explicit lease");
        if (root.header.equals(published) && source.released && !root.released) return;
        if (source.released || root.released || !root.header.equals(expected))
            throw new SQLException("Workspace custody changed before publication");
        update(c, materialLease, published, false);
        try (var p = c.prepareStatement("UPDATE platform_task_resource SET released_at=? WHERE lease_id=? AND released_at IS NULL")) {
            p.setLong(1, System.currentTimeMillis());
            p.setObject(2, workspaceLease);
            if (p.executeUpdate() != 1) throw new SQLException("Workspace detach was not committed");
        }
    }

    /** Binds one read hold to its exact original consumer and material. / 将一个读取持有绑定到精确原消费方及素材。
     * @param task original accepted task identity / 原已接纳任务身份
     * @param hold exact consumer read hold / 精确消费方读取持有
     * @param value bounded exact recovery value / 有界精确恢复值
     * @param root exact original material root / 精确原素材根
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void holdIdentity(UUID task, TaskResource hold, ResourceRecovery value, ResourceRecovery root) throws SQLException {
        var f = value.fields();
        var rf = root.fields();
        if (!value.protocol().equals("material-hold.v1") || !f.keySet().equals(HOLD) || !hold.kind().equals("FILE") || hold.exclusive()
            || !hold.id().equals("material-hold-v1:" + f.get("materialId") + "/" + f.get("holdId")) || !hold.ownerId().equals(f.get("consumerId"))
            || !task.toString().equals(f.get("taskId")) || !f.get("materialId").equals(rf.get("materialId")) || !f.get("consumerId").equals(rf.get("consumerId"))
            || !f.get("consumerVersion").equals(rf.get("consumerVersion")) || !f.get("materialDigest").equals(rf.get("manifestSha256"))
            || !f.get("revision").equals(rf.get("revision")) || !Set.of("ACTIVE", "RELEASED").contains(f.get("state")))
            throw new SQLException("Invalid material hold identity");
        try {
            canonicalUuid(f.get("holdId"));
            canonicalUuid(f.get("bootId"));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Invalid material hold UUID", invalid);
        }
    }

    /** Requires the exact root to remain published and unreleased. / 要求精确根仍已发布且未释放。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task identity / 原已接纳任务身份
     * @param rootLease original root lease / 原根占用
     * @param root exact original material root / 精确原素材根
     * @param expected complete expected prior recovery facts / 完整预期先前恢复依据
     * @param graph exact retained original task graph / 精确保留原任务图
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void requirePublished(Connection c, UUID task, UUID rootLease, TaskResource root, ResourceRecovery expected, TaskRecoveryGraph graph) throws SQLException {
        rootIdentity(c, task, root, expected, graph);
        var row = row(c, task, rootLease, root);
        if (row.released || !row.header.equals(expected) || !expected.fields().get("state").equals("PUBLISHED"))
            throw new SQLException("Material root no longer accepts a hold");
    }

    /** Releases only a confirmed closed original hold. / 仅释放已确认关闭的原持有。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task identity / 原已接纳任务身份
     * @param lease exact durable lease identity / 精确持久占用身份
     * @param hold exact consumer read hold / 精确消费方读取持有
     * @param expected complete expected prior recovery facts / 完整预期先前恢复依据
     * @param released confirmed original release facts / 已确认原释放依据
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void releaseHold(Connection c, UUID task, UUID lease, TaskResource hold, ResourceRecovery expected, ResourceRecovery released) throws SQLException {
        if (!expected.protocol().equals("material-hold.v1") || !expected.fields().keySet().equals(HOLD) || !released.protocol().equals("material-hold.v1") || !released.fields().keySet().equals(HOLD) || !expected.fields().get("state").equals("ACTIVE") || !released.fields().get("state").equals("RELEASED"))
            throw new SQLException("Invalid material hold release");
        var changes = new HashMap<>(expected.fields());
        changes.put("state", "RELEASED");
        if (!changes.equals(released.fields())) throw new SQLException("Material hold release changed identity");
        var row = row(c, task, lease, hold);
        if (row.released && row.header.equals(released)) return;
        if (row.released || !row.header.equals(expected)) throw new SQLException("Material hold changed");
        update(c, lease, released, true);
    }

    /** Confirms physical root deletion only after all holds release. / 仅在全部持有释放后确认物理根删除。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task identity / 原已接纳任务身份
     * @param lease exact durable lease identity / 精确持久占用身份
     * @param root exact original material root / 精确原素材根
     * @param expected complete expected prior recovery facts / 完整预期先前恢复依据
     * @param released confirmed original release facts / 已确认原释放依据
     * @param graph exact retained original task graph / 精确保留原任务图
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void releaseRoot(Connection c, UUID task, UUID lease, TaskResource root, ResourceRecovery expected, ResourceRecovery released, TaskRecoveryGraph graph) throws SQLException {
        rootIdentity(c, task, root, expected, graph);
        rootIdentity(c, task, root, released, graph);
        if (expected.fields().get("manifestSha256").isEmpty())
            throw new SQLException("Unpublished custody requires original workspace release");
        transition(expected, released, "RELEASE_REQUESTED", "RELEASED", Set.of("state", "recordRevision"));
        var row = row(c, task, lease, root);
        if (row.released && row.header.equals(released)) return;
        if (row.released || !row.header.equals(expected)) throw new SQLException("Material root changed");
        try (var p = c.prepareStatement("SELECT resource_id,owner_id,access_mode,recovery_json,released_at FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE'")) {
            p.setObject(1, task);
            try (var r = p.executeQuery()) {
                String prefix = "material-hold-v1:" + expected.fields().get("materialId") + "/";
                while (r.next()) if (r.getString(1).startsWith(prefix)) {
                    var hold = new TaskResource("FILE", r.getString(1), r.getString(2), r.getString(3).equals("EXCLUSIVE"));
                    var header = ResourceRecovery.decode(r.getString(4));
                    holdIdentity(task, hold, header, expected);
                    if (r.getObject(5) == null || !header.fields().get("state").equals("RELEASED"))
                        throw new SQLException("Material still has an unreleased or unverifiable hold");
                }
            }
        }
        update(c, lease, released, true);
    }

    /** Releases abandoned unpublished custody and its exact original workspace together. / 一同释放已放弃的未发布保管及精确原工作区。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task / 原已接纳任务
     * @param workspaceLease original workspace lease / 原工作区占用
     * @param workspace original source workspace / 原源码工作区
     * @param rootLease original material root lease / 原素材根占用
     * @param root exact original material root / 精确原素材根
     * @param expected exact release-requested unpublished header / 精确请求释放未发布头
     * @param released confirmed unpublished release header / 已确认未发布释放头
     * @param graph exact original task graph / 精确原任务图
     * @throws SQLException if original custody or zero writer evidence cannot be confirmed / 无法确认原保管或零写入方依据时 */
    static void unpublished(Connection c, UUID task, UUID workspaceLease, TaskResource workspace, UUID rootLease,
                            TaskResource root, ResourceRecovery expected, ResourceRecovery released,
                            TaskRecoveryGraph graph) throws SQLException {
        rootIdentity(c, task, root, expected, graph);
        rootIdentity(c, task, root, released, graph);
        if (!expected.fields().get("manifestSha256").isEmpty()) throw new SQLException("Material is already published");
        transition(expected, released, "RELEASE_REQUESTED", "RELEASED", Set.of("state", "recordRevision"));
        if (!workspace.kind().equals("FILE") || workspace.exclusive()
            || !workspace.id().equals("source-workspace-v1:" + expected.fields().get("workspaceId"))
            || !workspace.ownerId().equals(expected.fields().get("physicalOwnerId")))
            throw new SQLException("Original unpublished workspace differs");
        var original = row(c, task, workspaceLease, workspace);
        var material = row(c, task, rootLease, root);
        if (!ResourceRecovery.EXPLICIT.equals(original.raw))
            throw new SQLException("Original workspace is not explicit");
        if (material.released && material.header.equals(released) && original.released) return;
        if (material.released || !material.header.equals(expected))
            throw new SQLException("Unpublished custody changed");
        try (var p = c.prepareStatement("SELECT resource_kind,resource_id,owner_id FROM platform_task_resource WHERE task_id=? AND released_at IS NULL")) {
            p.setObject(1, task);
            try (var r = p.executeQuery()) {
                while (r.next())
                    if (r.getString(1).equals("PROCESS") && r.getString(3).equals(expected.fields().get("producerId"))
                        || r.getString(1).equals("FILE") && r.getString(2).startsWith("material-hold-v1:" + expected.fields().get("materialId") + "/"))
                        throw new SQLException("Original unpublished custody still has a writer or hold");
            }
        }
        update(c, rootLease, released, true);
        try (var p = c.prepareStatement("UPDATE platform_task_resource SET released_at=COALESCE(released_at,?) WHERE lease_id=?")) {
            p.setLong(1, System.currentTimeMillis());
            p.setObject(2, workspaceLease);
            if (p.executeUpdate() != 1) throw new SQLException("Original workspace release was not confirmed");
        }
    }

    /** Reads and verifies one exact original FILE lease. / 读取并核验一个精确原 FILE 占用。
     * @param c serialized original platform connection / 串行原平台连接
     * @param task original accepted task identity / 原已接纳任务身份
     * @param lease exact durable lease identity / 精确持久占用身份
     * @param resource exact original resource / 精确原资源
     * @return verified original result / 已核验原结果
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static Row row(Connection c, UUID task, UUID lease, TaskResource resource) throws SQLException {
        try (var p = c.prepareStatement("SELECT task_id,resource_kind,resource_id,owner_id,access_mode,recovery_json,released_at FROM platform_task_resource WHERE lease_id=?")) {
            p.setObject(1, lease);
            try (var r = p.executeQuery()) {
                if (!r.next() || !task.equals(r.getObject(1, UUID.class)) || !resource.kind().equals(r.getString(2)) || !resource.id().equals(r.getString(3)) || !resource.ownerId().equals(r.getString(4)) || !(resource.exclusive() ? "EXCLUSIVE" : "SHARED").equals(r.getString(5)))
                    throw new SQLException("Original FILE lease identity differs");
                String raw = r.getString(6);
                return new Row(raw, ResourceRecovery.EXPLICIT.equals(raw) ? null : ResourceRecovery.decode(raw), r.getObject(7) != null);
            }
        }
    }

    /** Writes one validated recovery header and release fact. / 写入一个已校验恢复头及释放事实。
     * @param c serialized original platform connection / 串行原平台连接
     * @param lease exact durable lease identity / 精确持久占用身份
     * @param header decoded original recovery header / 已解码原恢复头
     * @param release whether actual closure is confirmed / 是否已确认实际关闭
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    static void update(Connection c, UUID lease, ResourceRecovery header, boolean release) throws SQLException {
        try (var p = c.prepareStatement("UPDATE platform_task_resource SET recovery_json=?,released_at=? WHERE lease_id=?")) {
            p.setString(1, header.encode());
            if (release) p.setLong(2, System.currentTimeMillis());
            else p.setNull(2, Types.BIGINT);
            p.setObject(3, lease);
            if (p.executeUpdate() != 1) throw new SQLException("Original FILE lease disappeared");
        }
    }

    /** Requires one monotone transition without changing immutable identity. / 要求单调转移且不改变不可变身份。
     * @param expected complete expected prior recovery facts / 完整预期先前恢复依据
     * @param next validated next recovery facts / 已校验下一恢复依据
     * @param from required prior state / 要求的先前状态
     * @param to required next state / 要求的下一状态
     * @param mutable fields permitted to change in this transition / 此转移可更改字段
     * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
     */
    private static void transition(ResourceRecovery expected, ResourceRecovery next, String from, String to, Set<String> mutable) throws SQLException {
        if (!expected.fields().get("state").equals(from) || !next.fields().get("state").equals(to) || number(next.fields().get("recordRevision")) != number(expected.fields().get("recordRevision")) + 1)
            throw new SQLException("Invalid material state transition");
        var before = new HashMap<>(expected.fields());
        var after = new HashMap<>(next.fields());
        mutable.forEach(before::remove);
        mutable.forEach(after::remove);
        if (!before.equals(after)) throw new SQLException("Material transition changed immutable identity");
    }

    /** Rejects noncanonical material UUID spellings. / 拒绝非规范素材 UUID 拼写。
     * @param value bounded exact recovery value / 有界精确恢复值
     */
    private static void canonicalUuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
    }

    /** Reads a canonical nonnegative material count. / 读取规范非负素材计数。
     * @param value bounded exact recovery value / 有界精确恢复值
     * @return verified original result / 已核验原结果
     */
    private static long number(String value) {
        long n = Long.parseLong(value);
        if (n < 0 || !Long.toString(n).equals(value)) throw new IllegalArgumentException("Noncanonical count");
        return n;
    }

    /** Original recovery header and actual release observation. / 原恢复头及实际释放观察。
     * @param raw original recovery JSON / 原恢复 JSON
     * @param header decoded original recovery header / 已解码原恢复头
     * @param released confirmed original release facts / 已确认原释放依据
     */
    record Row(String raw, ResourceRecovery header, boolean released) {
    }
}

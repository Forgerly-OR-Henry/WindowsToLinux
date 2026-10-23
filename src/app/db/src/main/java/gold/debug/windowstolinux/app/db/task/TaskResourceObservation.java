package gold.debug.windowstolinux.app.db.task;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;

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

    /** Checks exact checkpoint envelopes and all remaining leases under one platform operation lock. / 在同一平台操作锁下核对精确检查点封套及其余全部占用。
     * @param taskId authenticated original task / 已认证原任务
     * @param owner exact root owner / 精确根所有者
     * @param version original root release / 原根发布
     * @param keys bounded distinct canonical checkpoint keys / 有界且不同的规范检查点键
     * @return true only when no other explicit resource remains / 仅无其他显式资源时为真
     * @throws SQLException if any excluded identity or observation is unconfirmed / 任一排除身份或观测无法确认时 */
    public boolean releasedExceptCheckpoints(UUID taskId, String owner, String version, Set<String> keys) throws SQLException {
        return observe(taskId, owner, version, keys, null);
    }

    /** Observes the exact complete author set, including released rows, without exposing their contents. */
    public boolean releasedExceptCheckpoints(UUID taskId, String owner, String version, Set<String> keys,
                                             Set<String> exactAuthorKeys) throws SQLException {
        var authors = boundedKeys(exactAuthorKeys);
        if (keys == null || !authors.containsAll(keys))
            throw new TaskAuthorCheckpoint.RejectedException("Excluded checkpoints are outside the complete author set");
        return observe(taskId, owner, version, keys, authors);
    }

    private static Set<String> boundedKeys(Set<String> keys) throws SQLException {
        if (keys == null || keys.isEmpty() || keys.size() > 16)
            throw new TaskAuthorCheckpoint.RejectedException("Invalid checkpoint exclusion set");
        Set<String> exact;
        try {
            exact = Set.copyOf(keys);
        } catch (NullPointerException invalid) {
            throw new TaskAuthorCheckpoint.RejectedException("Invalid checkpoint exclusion key");
        }
        if (exact.isEmpty() || exact.size() > 16)
            throw new TaskAuthorCheckpoint.RejectedException("Invalid checkpoint exclusion set");
        for (String key : exact)
            if (!key.matches("[a-z][a-z0-9_.-]{0,63}"))
                throw new TaskAuthorCheckpoint.RejectedException("Invalid author checkpoint key");
        return exact;
    }

    private boolean observe(UUID taskId, String owner, String version, Set<String> keys, Set<String> authorKeys) throws SQLException {
        var exact = boundedKeys(keys);
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            StoreConnections.lockPlatformState(connection);
            try (var query = connection.prepareStatement("SELECT owner_id,owner_version FROM platform_task WHERE task_id=?")) {
                query.setObject(1, Objects.requireNonNull(taskId));
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !Objects.equals(owner, rows.getString(1)) || !Objects.equals(version, rows.getString(2)))
                        throw new SQLException("Original resource observation root differs");
                }
            }
            var excluded = new HashSet<UUID>();
            var checkpoints = new TaskAuthorCheckpoint(database);
            if (authorKeys != null) {
                var actual = new HashSet<String>();
                try (var query = connection.prepareStatement("SELECT lease_id,resource_kind,resource_id,recovery_json FROM platform_task_resource WHERE task_id=?")) {
                    query.setObject(1, taskId);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            String metadata = rows.getString(4);
                            boolean checkpointName = "CUSTOM".equals(rows.getString(2))
                                && rows.getString(3).startsWith(owner + "/checkpoint/");
                            if (ResourceRecovery.TASK_END.equals(metadata) || ResourceRecovery.EXPLICIT.equals(metadata)) {
                                if (checkpointName) throw new SQLException("Author checkpoint envelope is absent");
                                continue;
                            }
                            var facts = ResourceRecovery.decode(metadata);
                            if (!facts.protocol().equals("author-checkpoint.v1") && !checkpointName) continue;
                            String key = facts.fields().get("key");
                            var entry = checkpoints.find(connection, taskId, owner, version, key)
                                .orElseThrow(() -> new SQLException("Original author checkpoint identity differs"));
                            if (!"CUSTOM".equals(rows.getString(2)) || !entry.lease().equals(rows.getObject(1, UUID.class))
                                || !actual.add(key)) throw new SQLException("Original author checkpoint identity is duplicated or substituted");
                        }
                    }
                }
                if (!actual.equals(authorKeys)) {
                    connection.commit();
                    return false;
                }
            }
            for (String key : exact) {
                var entry = checkpoints.find(connection, taskId, owner, version, key)
                    .orElseThrow(() -> new SQLException("Excluded author checkpoint is absent"));
                if (!excluded.add(entry.lease())) throw new SQLException("Excluded checkpoint lease is duplicated");
            }
            boolean released = true;
            try (var query = connection.prepareStatement("SELECT lease_id FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND (recovery_json IS NULL OR recovery_json<>?)")) {
                query.setObject(1, taskId);
                query.setString(2, ResourceRecovery.TASK_END);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) if (!excluded.contains(rows.getObject(1, UUID.class))) released = false;
                }
            }
            connection.commit();
            return released;
        }
    }
}

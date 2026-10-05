package gold.debug.windowstolinux.app.db.task;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;

/** Exact owner-bound author facts stored on one platform CUSTOM lease without exposing host recovery protocols. / 存于一个平台 CUSTOM 占用、且不暴露宿主恢复协议的精确作者依据。 */
public final class TaskAuthorCheckpoint {
    /** Definitive validation or CAS rejection before any write. / 任何写入前已确定的校验或 CAS 拒绝。 */
    public static final class RejectedException extends SQLException {
        /** Creates a safe rejection. / 创建安全拒绝。
         * @param message stable diagnosis / 稳定诊断 */
        public RejectedException(String message) {
            super(message);
        }
    }

    /** Fixed host-controlled envelope, never supplied by a Mod. / 固定宿主控制封套，绝不由 Mod 指定。 */
    private static final String PROTOCOL = "author-checkpoint.v1";

    /** Bounded author JSON before it enters the platform ledger. / 进入平台账本前有界的作者 JSON。 */
    private static final int MAX_PAYLOAD_BYTES = 12000;

    /** Strict object parser for author checkpoint syntax. / 严格的作者检查点对象解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Original platform store. / 原平台存储。 */
    private final PlatformDatabase database;

    /** Binds the original exclusive platform generation. / 绑定原独占平台代次。
     * @param database actual platform engine / 实际平台引擎 */
    public TaskAuthorCheckpoint(PlatformDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Persisted exact author row. / 已持久化的精确作者行。
     * @param lease original lease UUID / 原占用 UUID
     * @param snapshot exact current value / 精确当前值 */
    public record Entry(UUID lease, TaskCheckpoint.Snapshot snapshot) {
        /** Requires a complete immutable row. / 要求完整不可变行。
         * @param lease original lease UUID / 原占用 UUID
         * @param snapshot exact current value / 精确当前值 */
        public Entry {
            Objects.requireNonNull(lease);
            Objects.requireNonNull(snapshot);
        }
    }

    /** Creates the first author fact atomically with its never-reused resource identity. / 将首个作者事实与不可复用资源身份原子创建。
     * @param task original task / 原任务
     * @param owner bound owner / 绑定所有者
     * @param version bound release / 绑定发布
     * @param key owner-local stable key / 所有者内部稳定键
     * @param initial bounded non-secret JSON / 初始有界非秘密 JSON
     * @return original persisted row / 原持久行
     * @throws SQLException if the task, identity or persistence cannot be confirmed / 无法确认任务、身份或持久化时 */
    public Entry begin(UUID task, String owner, String version, String key, String initial) throws SQLException {
        validateKey(key);
        validatePayload(initial);
        try (var connection = database.openConnection()) {
            requireTask(connection, task, owner, version);
        }
        var facts = facts(task, owner, version, key, 0, initial);
        UUID lease = new TaskLedger(database).retain(task,
            new TaskResource("CUSTOM", resourceId(owner, key), owner, false), facts, true);
        return new Entry(lease, new TaskCheckpoint.Snapshot(0, initial, false));
    }

    /** Reads one current or released row while the original task still exists. / 原任务仍存在时读取当前或已释放行。
     * @param task original task / 原任务
     * @param owner bound owner / 绑定所有者
     * @param version bound release / 绑定发布
     * @param key exact owner-local key / 精确所有者内部键
     * @return matching row or absent / 匹配行或不存在
     * @throws SQLException if persisted identities conflict or cannot be read / 持久身份冲突或无法读取时 */
    public Optional<Entry> find(UUID task, String owner, String version, String key) throws SQLException {
        validateKey(key);
        try (var connection = database.openConnection()) {
            requireTask(connection, task, owner, version);
            try (var query = connection.prepareStatement(
                "SELECT lease_id,owner_id,access_mode,recovery_json,released_at FROM platform_task_resource WHERE task_id=? AND resource_kind='CUSTOM' AND resource_id=?")) {
                query.setObject(1, task);
                query.setString(2, resourceId(owner, key));
                try (var rows = query.executeQuery()) {
                    if (!rows.next())
                        return Optional.empty();
                    if (!owner.equals(rows.getString(2)) || !"SHARED".equals(rows.getString(3)))
                        throw new SQLException("Author checkpoint ownership differs");
                    var value = decode(rows.getString(4), task, owner, version, key, rows.getObject(5) != null);
                    var entry = new Entry(rows.getObject(1, UUID.class), value);
                    if (rows.next())
                        throw new SQLException("Author checkpoint identity is duplicated");
                    return Optional.of(entry);
                }
            }
        }
    }

    /** Compares the original version and full value under the platform operation lock. / 在平台操作锁下比较原修订及完整值。
     * @param task original task / 原任务
     * @param owner bound owner / 绑定所有者
     * @param version bound release / 绑定发布
     * @param key exact stable key / 精确稳定键
     * @param lease original lease / 原占用
     * @param expected exact old value / 精确旧值
     * @param next new bounded JSON / 新有界 JSON
     * @return confirmed next value / 已确认的新值
     * @throws SQLException if compare-and-set or durability cannot be confirmed / 比较更新或持久性无法确认时 */
    public TaskCheckpoint.Snapshot advance(UUID task, String owner, String version, String key, UUID lease,
                                           TaskCheckpoint.Snapshot expected, String next) throws SQLException {
        validateKey(key);
        if (expected == null || expected.revision() < 0)
            throw new RejectedException("Author checkpoint expected value is invalid");
        validatePayload(expected.payload());
        validatePayload(next);
        if (expected.released() || expected.revision() == Long.MAX_VALUE)
            throw new RejectedException("Author checkpoint cannot advance after release or revision exhaustion");
        var after = facts(task, owner, version, key, expected.revision() + 1, next).encode();
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            StoreConnections.lockPlatformState(connection);
            requireTask(connection, task, owner, version);
            String before;
            try (var query = connection.prepareStatement(
                "SELECT recovery_json,released_at FROM platform_task_resource WHERE lease_id=? AND task_id=? AND resource_kind='CUSTOM' AND resource_id=? AND owner_id=? AND access_mode='SHARED'")) {
                query.setObject(1, lease);
                query.setObject(2, task);
                query.setString(3, resourceId(owner, key));
                query.setString(4, owner);
                try (var rows = query.executeQuery()) {
                    if (!rows.next())
                        throw new RejectedException("Author checkpoint expected value differs");
                    before = rows.getString(1);
                    if (!decode(before, task, owner, version, key, rows.getObject(2) != null).equals(expected))
                        throw new RejectedException("Author checkpoint expected value differs");
                }
            }
            try (var update = connection.prepareStatement(
                "UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=? AND task_id=? AND resource_kind='CUSTOM' AND resource_id=? AND owner_id=? AND access_mode='SHARED' AND recovery_json=? AND released_at IS NULL")) {
                update.setString(1, after);
                update.setObject(2, lease);
                update.setObject(3, task);
                update.setString(4, resourceId(owner, key));
                update.setString(5, owner);
                update.setString(6, before);
                if (update.executeUpdate() != 1)
                    throw new RejectedException("Author checkpoint expected value differs");
            }
            connection.commit();
            return new TaskCheckpoint.Snapshot(expected.revision() + 1, next, false);
        }
    }

    /** Releases the exact author row after rereading all ownership facts. / 重读全部归属依据后释放精确作者行。
     * @param task original task / 原任务
     * @param owner bound owner / 绑定所有者
     * @param version bound release / 绑定发布
     * @param key exact stable key / 精确稳定键
     * @param lease original lease / 原占用
     * @throws SQLException if ownership or release is unconfirmed / 归属或释放未确认时 */
    public void release(UUID task, String owner, String version, String key, UUID lease) throws SQLException {
        var existing = find(task, owner, version, key).orElseThrow(() -> new SQLException("Author checkpoint absent"));
        if (!existing.lease().equals(lease))
            throw new SQLException("Author checkpoint lease differs");
        new TaskLedger(database).release(task, lease);
    }

    /** Uses only one fixed namespace for author facts. / 作者依据只使用一个固定命名空间。
     * @param owner bound owner / 绑定所有者
     * @param key validated stable key / 已验证稳定键
     * @return exact task resource identity / 精确任务资源身份 */
    private static String resourceId(String owner, String key) {
        return owner + "/checkpoint/" + key;
    }

    /** Validates the key without exposing a host protocol name. / 校验键且不暴露宿主协议名。
     * @param key owner-local key / 所有者内部键
     * @throws SQLException if key is not bounded / 键无界时 */
    private static void validateKey(String key) throws SQLException {
        if (key == null || !key.matches("[a-z][a-z0-9_.-]{0,63}"))
            throw new RejectedException("Invalid author checkpoint key");
    }

    /** Accepts only a bounded complete JSON object. / 只接纳有界完整 JSON 对象。
     * @param payload non-secret author value / 非秘密作者值
     * @throws SQLException if syntax or size differs / 语法或大小不符时 */
    private static void validatePayload(String payload) throws SQLException {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES)
            throw new RejectedException("Author checkpoint is too large");
        try {
            var value = JSON.readTree(payload);
            if (value == null || !value.isObject())
                throw new RejectedException("Author checkpoint must be an object");
        } catch (java.io.IOException invalid) {
            throw new RejectedException("Author checkpoint is not valid JSON");
        }
    }

    /** Constructs a host-controlled recovery envelope. / 构造宿主控制的恢复封套。
     * @param task original task / 原任务
     * @param owner bound owner / 绑定所有者
     * @param version bound release / 绑定发布
     * @param key exact key / 精确键
     * @param revision monotone revision / 单调修订
     * @param payload author value / 作者值
     * @return exact host envelope / 精确宿主封套 */
    private static ResourceRecovery facts(UUID task, String owner, String version, String key, long revision,
                                          String payload) {
        return new ResourceRecovery(PROTOCOL, Map.of("taskId", task.toString(), "ownerId", owner, "ownerVersion",
            version, "key", key, "revision", Long.toString(revision), "payload", payload));
    }

    /** Decodes only this exact row's fixed host protocol and immutable identity. / 仅解码此精确行的固定宿主协议和不可变身份。
     * @param value stored envelope / 已存封套
     * @param task original task / 原任务
     * @param owner bound owner / 绑定所有者
     * @param version bound release / 绑定发布
     * @param key exact key / 精确键
     * @param released actual release column / 实际释放列
     * @return validated public snapshot / 已核验的公开快照
     * @throws SQLException if any fact differs / 任一依据不符时 */
    private static TaskCheckpoint.Snapshot decode(String value, UUID task, String owner, String version, String key,
                                                  boolean released) throws SQLException {
        try {
            var decoded = ResourceRecovery.decode(value);
            var fields = decoded.fields();
            if (!decoded.protocol().equals(PROTOCOL)
                || !fields.keySet()
                .equals(Set.of("taskId", "ownerId", "ownerVersion", "key", "revision", "payload"))
                || !fields.get("taskId").equals(task.toString()) || !fields.get("ownerId").equals(owner)
                || !fields.get("ownerVersion").equals(version) || !fields.get("key").equals(key))
                throw new IllegalArgumentException("Author checkpoint identity differs");
            long revision = Long.parseLong(fields.get("revision"));
            validatePayload(fields.get("payload"));
            if (revision < 0 || !Long.toString(revision).equals(fields.get("revision"))
                || !decoded.equals(facts(task, owner, version, key, revision, fields.get("payload"))))
                throw new IllegalArgumentException("Author checkpoint revision differs");
            return new TaskCheckpoint.Snapshot(revision, fields.get("payload"), released);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Author checkpoint is malformed", invalid);
        }
    }

    /** Requires the same persisted owner and release before every operation. / 每次操作前要求相同持久所有者与发布。
     * @param connection platform connection / 平台连接
     * @param task original task / 原任务
     * @param owner bound owner / 绑定所有者
     * @param version bound release / 绑定发布
     * @throws SQLException if identity differs / 身份不符时 */
    private static void requireTask(Connection connection, UUID task, String owner, String version)
        throws SQLException {
        try (var query = connection
            .prepareStatement("SELECT owner_id,owner_version,recovery_json FROM platform_task WHERE task_id=?")) {
            query.setObject(1, task);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Original checkpoint task is absent");
                if (owner.equals(rows.getString(1)) && version.equals(rows.getString(2))) return;
                try {
                    var document = JSON.readTree(rows.getString(3));
                    if (!Set.of(2, 3).contains(document.path("formatVersion").asInt()))
                        throw new IllegalArgumentException();
                    var original = TaskRecoveryGraph.decode(document.path("graph")).release(owner);
                    if (!original.version().equals(version)) throw new IllegalArgumentException();
                } catch (java.io.IOException | IllegalArgumentException | NoSuchElementException invalid) {
                    throw new SQLException("Original checkpoint provider identity differs", invalid);
                }
            }
        }
    }
}

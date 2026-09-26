package gold.debug.windowstolinux.app.db.mod;

import java.sql.*;
import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.Problem;

/** Journals one actual activation without changing the user's enabled flag or version selection. / 记录一次实际启用，不改变用户开关或版本选择。 */
public final class ModActivationLedger {
    /** Serialized platform transactions shared with task admission. / 与任务准入共用的串行平台事务。 */
    private final RepositoryConnections connections;

    /** Exact current engine boot. / 精确当前引擎启动身份。 */
    private final UUID boot;

    /** Original owner identity. / 原所有者身份。 */
    private final String owner;

    /** Original loaded release. / 原加载发布版本。 */
    private final String version;

    /** Original prepared data generation. / 原已准备数据代次。 */
    private final Optional<SchemaIdentity> schema;

    /** Whether this instance attempted to acquire the durable activation. / 此实例是否尝试取得持久启用归属。 */
    private boolean attempted;

    /** Whether this attempt reached its own durable write boundary. / 此尝试是否到达自身持久写入边界。 */
    private boolean claimed;

    /** Safe diagnostic encoding, independent of author exception messages. / 独立于作者异常消息的安全诊断编码。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Binds one original activation before any lifecycle callback. / 在任一生命周期回调前绑定一次原启用。
     * @param database current platform engine / 当前平台引擎
     * @param owner exact Mod ID / 精确 Mod ID
     * @param version loaded release / 加载版本
     * @param schema selected data generation / 选定数据代次 */
    public ModActivationLedger(PlatformDatabase database, String owner, String version,
            Optional<SchemaIdentity> schema) {
        connections = new RepositoryConnections(database::openConnection);
        boot = database.bootId();
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.schema = Objects.requireNonNull(schema);
    }

    /** Persists STARTING before handing the context to author code. / 向作者代码交付上下文前持久化 STARTING。
     * @throws SQLException if intent, data, retained work or persistence prevents startup / 意图、数据、保留工作或持久化阻止启动时 */
    public synchronized void starting() throws SQLException {
        if (attempted)
            throw new SQLException("This activation cannot be replayed in the same boot");
        attempted = true;
        locked(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT r.enabled,r.selected_version,r.last_boot_id,v.state,v.schema_version,r.last_runtime_state,r.actual_version FROM mod_registry r JOIN mod_version v ON v.mod_id=r.mod_id AND v.version=? WHERE r.mod_id=?")) {
                query.setString(1, version);
                query.setString(2, owner);
                try (var row = query.executeQuery()) {
                    if (!row.next() || !row.getBoolean(1) || !version.equals(row.getString(2))
                            || boot.equals(row.getObject(3, UUID.class)) || !"READY".equals(row.getString(4))
                            || !Set.of("STOPPED", "FAILED").contains(row.getString(6)) || row.getString(7) != null
                            || !Objects.equals(schema.map(SchemaIdentity::schemaVersion).orElse(null),
                                    row.getString(5)))
                        throw new SQLException("Selected Mod is not eligible for a new boot activation");
                }
            }
            requireDrained(connection);
            try (var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND state<>'ENDED' AND NOT COALESCE(operation_type IN ('ENABLE','SWITCH') AND state='RUNNING' AND outcome IS NULL AND phase='APPLYING' AND target_version=? AND accepted_revision=(SELECT revision FROM mod_registry WHERE mod_id=mod_operation.mod_id),FALSE)")) {
                query.setString(1, owner);
                query.setString(2, version);
                try (var row = query.executeQuery()) {
                    row.next();
                    if (row.getLong(1) != 0)
                        throw new SQLException("Mod intent or maintenance has not reached startup application");
                }
            }
            requireApplicationBoot(connection);
            if (schema.isPresent()) {
                var identity = schema.get();
                var coordinates = new SchemaCoordinates(identity);
                try (var update = connection.prepareStatement(
                        "UPDATE mod_schema SET ever_used=TRUE WHERE mod_id=? AND schema_version=? AND generation=? AND physical_name=? AND database_user=? AND state='READY'")) {
                    update.setString(1, owner);
                    update.setString(2, identity.schemaVersion());
                    update.setObject(3, identity.generation());
                    update.setString(4, coordinates.physicalName());
                    update.setString(5, coordinates.databaseUser());
                    if (!owner.equals(identity.ownerId()) || update.executeUpdate() != 1)
                        throw new SQLException("Prepared Mod schema is not the registered ready generation");
                }
            }
            claimed = true;
            write(connection, "STARTING", version, null);
        });
    }

    /** Requires the restart exception to belong to this exact executing boot. / 要求重启例外属于本次精确执行启动。
     * @param connection locked platform transaction / 已锁定平台事务
     * @throws SQLException if retained application ownership is absent, duplicated or stale / 保留应用归属缺失、重复或过期时 */
    private void requireApplicationBoot(Connection connection) throws SQLException {
        try (var query = connection
                .prepareStatement("SELECT recovery_json FROM mod_operation WHERE mod_id=? AND state<>'ENDED'")) {
            query.setString(1, owner);
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return;
                String encoded = rows.getString(1);
                if (encoded == null || encoded.length() > 1024 * 1024)
                    throw new SQLException("Invalid restart application evidence");
                try {
                    var evidence = JSON.readTree(encoded);
                    if (evidence == null || !evidence.isObject() || evidence.size() != 2
                            || !evidence.path("formatVersion").isInt() || evidence.path("formatVersion").intValue() != 1
                            || !boot.toString().equals(evidence.path("applicationBootId").textValue()) || rows.next())
                        throw new SQLException("Restart application does not belong to this boot");
                } catch (JsonProcessingException invalid) {
                    throw new SQLException("Invalid restart application evidence", invalid);
                }
            }
        }
    }

    /** Confirms publication while preserving independently changed intent. / 确认发布，同时保留独立变更的意图。
     * @throws SQLException if original activation or commit cannot be confirmed / 无法确认原启用或提交时 */
    public synchronized void running() throws SQLException {
        if (!claimed)
            throw new SQLException("This journal has not acquired an activation");
        locked(connection -> {
            String state = ownedState(connection);
            if (!Set.of("STARTING", "RUNNING").contains(state))
                throw new SQLException("Mod startup has already entered cleanup");
            write(connection, "RUNNING", version, null);
        });
    }

    /** Seals durable task admission before testing resource drain. / 检查资源排空前封闭持久任务准入。
     * @throws SQLException if a recorded activation cannot be sealed / 无法封闭已登记启用时 */
    public void stopping() throws SQLException {
        if (!claimed)
            return;
        locked(connection -> {
            if (!owns(connection))
                return;
            ownedState(connection);
            write(connection, "STOPPING", version, null);
        });
    }

    /** Records known failure only after all original resources are physically closed. / 仅在所有原资源物理关闭后记录已知失败。
     * @param failure original safe startup failure, absent after a normal stop / 原安全启动失败，正常停止时不存在
     * @throws SQLException if task occupancy or the final write remains unresolved / 任务占用或最终写入仍未解决时 */
    public void closed(Optional<Problem> failure) throws SQLException {
        locked(connection -> {
            if (!owns(connection)) {
                // Rejected admission must never overwrite another activation or a new user choice. / 被拒准入不得覆盖其他启用或新的用户选择。
                return;
            }
            requireDrained(connection);
            write(connection, failure.isPresent() ? "FAILED" : "STOPPED", null, failure.orElse(null));
        });
    }

    /** Retains unknown ownership after cleanup failure, or STOPPING for known busy work. / 清理失败后保留未知归属，已知繁忙工作保持 STOPPING。
     * @param failure safe retained reason / 安全保留原因
     * @param busy whether original work is known to remain / 是否明确仍有原工作
     * @throws SQLException if the retained status cannot be written / 无法写入保留状态时 */
    public void retained(Problem failure, boolean busy) throws SQLException {
        locked(connection -> {
            if (owns(connection))
                write(connection, busy ? "STOPPING" : "UNKNOWN", version, Objects.requireNonNull(failure));
        });
    }

    /** Reads state only for the original boot and loaded release. / 仅为原启动及加载版本读取状态。
     * @param connection locked platform connection / 已锁定平台连接
     * @return original durable state / 原持久状态
     * @throws SQLException if original ownership cannot be proven / 无法证明原归属时 */
    private String ownedState(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT last_runtime_state FROM mod_registry WHERE mod_id=? AND last_boot_id=? AND actual_version=?")) {
            query.setString(1, owner);
            query.setObject(2, boot);
            query.setString(3, version);
            try (var row = query.executeQuery()) {
                if (!row.next())
                    throw new SQLException("Original Mod activation is no longer registered");
                return row.getString(1);
            }
        }
    }

    /** Checks this boot's original release, including an acknowledged physical close. / 检查本次启动的原版本，包括已确认物理关闭。
     * @param connection locked platform connection / 已锁定平台连接
     * @return whether this attempt still owns the row / 此尝试是否仍持有该行
     * @throws SQLException if state cannot be read / 无法读取状态时 */
    private boolean owns(Connection connection) throws SQLException {
        if (!claimed)
            return false;
        try (var query = connection.prepareStatement(
                "SELECT actual_version,last_runtime_state FROM mod_registry WHERE mod_id=? AND last_boot_id=?")) {
            query.setString(1, owner);
            query.setObject(2, boot);
            try (var row = query.executeQuery()) {
                return row.next() && (version.equals(row.getString(1))
                        || row.getString(1) == null && Set.of("STOPPED", "FAILED").contains(row.getString(2)));
            }
        }
    }

    /** Requires original and cross-Mod task resources to be fully released. / 要求原任务及跨 Mod 任务资源全部释放。
     * @param connection locked platform connection / 已锁定平台连接
     * @throws SQLException if unresolved work still owns this Mod / 未解决工作仍持有此 Mod 时 */
    private void requireDrained(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM platform_task WHERE owner_id=? AND state<>'ENDED')+(SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=?))")) {
            query.setString(1, owner);
            query.setString(2, owner);
            query.setString(3, owner);
            try (var row = query.executeQuery()) {
                row.next();
                if (row.getLong(1) != 0)
                    throw new SQLException("Mod work or physical resources still require reconciliation");
            }
        }
    }

    /** Updates only actual state, never intent or restart-disable acknowledgement. / 仅更新实际状态，不更新意图或重启停用确认。
     * @param connection locked platform connection / 已锁定平台连接
     * @param state proven runtime state / 已证明运行状态
     * @param actual still-owned release, or null after physical close / 仍持有版本，物理关闭后为空
     * @param problem safe retained evidence / 安全保留证据
     * @throws SQLException if encoding or persistence fails / 编码或持久化失败时 */
    private void write(Connection connection, String state, String actual, Problem problem) throws SQLException {
        String detail = null;
        if (problem != null) {
            try {
                var evidence = JSON.createObjectNode().put("formatVersion", 1).put("code", problem.code().name())
                        .put("eventCode", problem.eventCode()).put("message", problem.message());
                problem.diagnosticId().ifPresent(id -> evidence.put("diagnosticId", id.toString()));
                evidence.set("context", JSON.valueToTree(problem.context()));
                detail = JSON.writeValueAsString(evidence);
            } catch (JsonProcessingException failure) {
                throw new SQLException("Mod diagnostic could not be encoded", failure);
            }
        }
        try (var update = connection.prepareStatement(
                "UPDATE mod_registry SET last_boot_id=?,last_runtime_state=?,actual_version=?,failure_code=?,failure_detail=?,updated_at=? WHERE mod_id=?")) {
            update.setObject(1, boot);
            update.setString(2, state);
            update.setString(3, actual);
            update.setString(4, problem == null ? null : problem.eventCode());
            update.setString(5, detail);
            update.setLong(6, System.currentTimeMillis());
            update.setString(7, owner);
            if (update.executeUpdate() != 1)
                throw new SQLException("Mod runtime registration disappeared");
        }
    }

    /** Serializes one state transition with task admission and verifies the actual boot. / 将一个状态转换与任务准入串行化，并核对实际启动身份。
     * @param work internal state transition / 内部状态转换
     * @throws SQLException if locking, boot validation or commit fails / 加锁、启动核对或提交失败时 */
    private void locked(Write work) throws SQLException {
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                RepositoryConnections.lockPlatformState(connection);
                try (var query = connection.prepareStatement("SELECT boot_id FROM app_state WHERE singleton_id=1");
                        var row = query.executeQuery()) {
                    if (!row.next() || !boot.equals(row.getObject(1, UUID.class)))
                        throw new SQLException("Mod journal belongs to another platform boot");
                }
                work.run(connection);
            }
        });
    }

    /** Internal SQL mutation without author callbacks. / 不含作者回调的内部 SQL 变更。 */
    @FunctionalInterface
    private interface Write {
        /** Applies one transition within the owning transaction. / 在所属事务内应用一个转换。
         * @param connection locked platform connection / 已锁定平台连接
         * @throws SQLException if the transition cannot commit / 转换无法提交时 */
        void run(Connection connection) throws SQLException;
    }
}

package gold.debug.windowstolinux.app.db.mod;

import java.io.IOException;
import java.sql.*;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared stopped-owner admission for installed program replacement and deletion. / 安装程序替换与删除共用的已停止所有者准入。 */
final class ModProgramMaintenance {
    /** Strict bounded dependency parser. / 严格有界依赖解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Prevents construction of this platform transaction helper. / 禁止构造此平台事务助手。 */
    private ModProgramMaintenance() {
    }

    /** Original acknowledged disabled intention, without changing runtime or failure fields. / 原已确认禁用意图，不改变运行或故障字段。
     * @param revision disabled intention revision / 禁用意图修订
     * @param selected saved selected release / 已存单选发布
     * @param initialized whether the first choice was initialized / 是否已初始化首次选择
     * @param ackBoot boot that recorded actual stop / 记录实际停止的启动 */
    record Stopped(long revision, String selected, boolean initialized, String ackBoot) {
    }

    /** Reads only a disabled owner whose exact revision has completed restart shutdown. / 仅读取精确修订已完成重启停用的禁用所有者。
     * @param c serialized platform transaction / 串行平台事务
     * @param owner exact owner / 精确所有者
     * @return immutable original stopped evidence / 不可变原停止依据
     * @throws SQLException if any required stopping fact is unconfirmed / 任一所需停止事实未确认时 */
    static Stopped stopped(Connection c, String owner) throws SQLException {
        try (var p = c.prepareStatement(
            "SELECT enabled,selected_version,selection_initialized,revision,disable_ack_revision,last_boot_id,last_runtime_state,actual_version FROM mod_registry WHERE mod_id=?")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                require(r.next() && !r.getBoolean(1) && r.getObject(5) != null && r.getLong(4) == r.getLong(5)
                        && r.getObject(6) != null && "STOPPED".equals(r.getString(7)) && r.getString(8) == null,
                    "Whole Mod must be disabled and restart-stop acknowledged before program maintenance");
                return new Stopped(r.getLong(4), r.getString(2), r.getBoolean(3),
                    r.getObject(6, UUID.class).toString());
            }
        }
    }

    /** Rejects unfinished own or cross-owner resources and competing maintenance. / 拒绝未收尾自有或跨所有者资源以及竞争维护。
     * @param c serialized transaction / 串行事务
     * @param owner exact owner / 精确所有者
     * @param operation current operation, null before admission / 当前操作，准入前为空
     * @throws SQLException if actual retained ownership prevents maintenance / 实际保留归属阻止维护时 */
    static void drained(Connection c, String owner, UUID operation) throws SQLException {
        require(count(c, "SELECT COUNT(*) FROM platform_task WHERE owner_id=?", owner) == 0,
            "Owned task records still require final cleanup");
        require(count(c,
            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=? OR resource_kind='SCHEMA' AND resource_id IN (SELECT CAST(generation AS VARCHAR) FROM mod_schema WHERE mod_id=?))",
            owner, owner, owner) == 0, "Owned or cross-Mod resources still require release");
        require(count(c, "SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND (? IS NULL OR operation_id<>?)", owner,
            operation, operation) == 0, "Other retained maintenance still requires cleanup");
    }

    /** Requires enabled consumers to be handled by the explicit cascade-disable flow. / 要求通过明确连带禁用流程处理开启的使用方。
     * @param c serialized transaction / 串行事务
     * @param owner maintained provider / 被维护提供方
     * @throws SQLException if an enabled dependent or unreadable declaration remains / 开启依赖者或不可读声明仍在时 */
    static void dependents(Connection c, String owner) throws SQLException {
        try (var p = c.prepareStatement(
            "SELECT v.manifest_json FROM mod_registry r JOIN mod_version v ON r.mod_id=v.mod_id AND r.selected_version=v.version WHERE r.enabled AND r.mod_id<>?")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    try {
                        String value = r.getString(1);
                        require(value.length() <= 1024 * 1024, "Enabled dependency evidence exceeds bounds");
                        var manifest = JSON.readTree(value);
                        require(manifest != null && manifest.isObject()
                                && (!manifest.has("dependencies") || manifest.path("dependencies").isArray()
                                && manifest.path("dependencies").size() <= 128),
                            "Enabled dependency evidence is invalid");
                        for (var dependency : manifest.path("dependencies")) {
                            require(dependency.path("id").isTextual(), "Enabled dependency identity is invalid");
                            require(!owner.equals(dependency.path("id").textValue()),
                                "Enabled dependents must first be explicitly disabled and restarted");
                        }
                    } catch (IOException invalid) {
                        throw new SQLException("Invalid enabled dependency evidence", invalid);
                    }
                }
            }
        }
    }

    /** Reads one bounded platform count using bound values. / 使用绑定值读取一个有界平台计数。
     * @param c original transaction / 原事务
     * @param sql fixed platform query / 固定平台查询
     * @param values bound identities / 绑定身份
     * @return authoritative count / 权威计数
     * @throws SQLException if unavailable / 不可用时 */
    static long count(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                p.setObject(i + 1, values[i]);
            try (var r = p.executeQuery()) {
                require(r.next(), "Missing platform count");
                return r.getLong(1);
            }
        }
    }

    /** Enforces a fact without discarding its failed state. / 强制事实条件，不丢弃失败状态。
     * @param condition required fact / 所需事实
     * @param message bounded host explanation / 有界宿主说明
     * @throws SQLException if the fact is not established / 未确立事实时 */
    static void require(boolean condition, String message) throws SQLException {
        if (!condition)
            throw new SQLException(message);
    }
}

package gold.debug.windowstolinux.app.service.task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.execution.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Resolves task ownership from the current boot's authoritative platform registry. / 根据当前启动的权威平台登记解析任务归属。 */
public final class PlatformTaskAdmission implements PlatformTaskRuntime.Admission {
    /** Reserved platform identity outside the valid Mod identifier syntax. / 不属于合法 Mod 标识语法的保留平台身份。 */
    public static final String PLATFORM_OWNER = "@platform";

    /** Host rescue protocol allowed to use the fixed read-only SSH verification. / 允许使用固定只读 SSH 复核的宿主救援协议。 */
    public static final String RESCUE_TYPE = gold.debug.windowstolinux.app.db.task.BrowserRecoveryArchival.TASK_TYPE;

    /** Exact boot whose running Mod instances may accept business. / 可以接纳业务的运行 Mod 实例所属精确启动。 */
    private final UUID bootId;

    /** Optional actual application release allowed to submit host-owned work. / 可提交宿主工作的实际应用版本，可为空以只接纳 Mod。 */
    private final String platformVersion;

    /**
     * Binds the policy to the engine owning this desktop. / 将策略绑定到当前桌面所属引擎。
     * @param database exclusively owned platform database / 独占的平台数据库
     */
    public PlatformTaskAdmission(PlatformDatabase database) {
        this(database, null);
    }

    /**
     * Binds host task admission to the actual application artifact version. / 将宿主任务准入绑定到实际应用产物版本。
     * @param database exclusively owned engine / 独占所属引擎
     * @param platformVersion actual application release, or null for Mod-only admission / 实际应用版本，null 表示只接纳 Mod
     */
    public PlatformTaskAdmission(PlatformDatabase database, String platformVersion) {
        bootId = Objects.requireNonNull(database).bootId();
        this.platformVersion = platformVersion;
    }

    /**
     * Requires an enabled running release, its ready data generation and all saved servers. / 要求已启用的运行版本、就绪数据代次及全部已保存服务器。
     * @param connection transaction holding the platform admission lock / 持有平台准入锁的事务
     * @param ownerId exact Mod identity bound by the host / 宿主绑定的精确 Mod 身份
     * @param version exact loaded release / 精确已加载版本
     * @param request complete author request / 完整作者请求
     * @return all initial claims in logical resource order / 按逻辑资源顺序排列的全部初始申请
     * @throws ApiException if any required identity is unavailable / 任一所需身份不可用时
     * @throws SQLException if authoritative state cannot be read / 无法读取权威状态时
     */
    @Override
    public List<TaskResource> claims(Connection connection, String ownerId, String version, TaskRequest request)
            throws ApiException, SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Task admission requires a platform transaction");
        Optional<SchemaIdentity> schema = Optional.empty();
        if (ownerId.equals(PLATFORM_OWNER)) {
            if (platformVersion == null || !platformVersion.equals(version))
                throw unavailable("PLATFORM_VERSION", "The host task does not match the running application release");
            try (var statement = connection
                    .prepareStatement("SELECT boot_id,shutdown_state FROM app_state WHERE singleton_id=1");
                    var rows = statement.executeQuery()) {
                if (!rows.next() || !bootId.equals(rows.getObject(1, UUID.class))
                        || !"RUNNING".equals(rows.getString(2)))
                    throw unavailable("PLATFORM_NOT_RUNNING", "The platform has not completed startup");
            }
        } else {
            requireNoPendingIntent(connection, ownerId);
            schema = requireRunningGraph(connection, ownerId, version).release(ownerId).schema();
        }
        var claims = new ArrayList<TaskResource>();
        claims.add(new TaskResource("MOD", ownerId, ownerId, false));
        schema.ifPresent(
                identity -> claims.add(new TaskResource("SCHEMA", identity.generation().toString(), ownerId, false)));
        try (var statement = connection.prepareStatement(
                "SELECT s.host,s.ssh_port,s.username,s.credential_id FROM server_profile s JOIN credential c ON c.credential_id=s.credential_id WHERE s.server_profile_id=?")) {
            for (var id : request.serverIds()) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1).isBlank() || rows.getInt(2) < 1 || rows.getInt(2) > 65535
                            || rows.getString(3).isBlank() || rows.getString(4).isBlank())
                        throw unavailable("SERVER_NOT_AVAILABLE", "A requested saved server is unavailable");
                }
                claims.add(new TaskResource("SERVER", id, ownerId, true));
            }
        }
        return List.copyOf(claims);
    }

    /** Captures the complete original running Mod graph in the same transaction as task admission. / 在任务准入同一事务捕获完整原运行 Mod 图。
     * @param connection original serialized transaction / 原串行事务
     * @param ownerId exact admitted owner / 精确准入所有者
     * @param version original running release / 原运行发布
     * @return original code and data graph, absent for host tasks / 原代码及数据图，宿主任务不存在
     * @throws ApiException if any original provider is unavailable / 任一原提供方不可用时
     * @throws SQLException if graph state cannot be read / 无法读取图状态时 */
    @Override
    public Optional<TaskRecoveryGraph> recoveryGraph(Connection connection, String ownerId, String version)
            throws ApiException, SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Recovery capture requires a platform admission transaction");
        return ownerId.equals(PLATFORM_OWNER)
                ? Optional.empty()
                : Optional.of(requireRunningGraph(connection, ownerId, version));
    }

    /** Rechecks every declared provider's actual state before accepting new consumer work. / 接纳使用方新工作前重新检查每个声明提供方的实际状态。
     * @param connection original admission transaction / 原准入事务
     * @param owner original consumer / 原使用方
     * @param version exact loaded consumer release / 精确已加载使用方版本
     * @return original releases and data generations / 原发布及数据代次
     * @throws SQLException if the graph cannot be read / 无法读取图时
     * @throws ApiException if any original provider is unavailable or incompatible / 任一原提供方不可用或不兼容时 */
    private TaskRecoveryGraph requireRunningGraph(Connection connection, String owner, String version)
            throws SQLException, ApiException {
        var remaining = new ArrayDeque<Map.Entry<String, String>>();
        remaining.add(Map.entry(owner, version));
        var visited = new HashSet<String>();
        var releases = new ArrayList<TaskRecoveryGraph.Release>();
        while (!remaining.isEmpty()) {
            var selected = remaining.removeFirst();
            if (!visited.add(selected.getKey()))
                continue;
            gold.debug.windowstolinux.app.mod.manifest.ModManifest manifest;
            try (var statement = connection.prepareStatement(
                    "SELECT r.enabled,r.selected_version,r.actual_version,r.last_runtime_state,r.last_boot_id,v.state,v.schema_version,v.manifest_json,v.content_sha256 FROM mod_registry r JOIN mod_version v ON v.mod_id=r.mod_id AND v.version=? WHERE r.mod_id=?")) {
                statement.setString(1, selected.getValue());
                statement.setString(2, selected.getKey());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1) || !selected.getValue().equals(rows.getString(2))
                            || !selected.getValue().equals(rows.getString(3)) || !"RUNNING".equals(rows.getString(4))
                            || !bootId.equals(rows.getObject(5, UUID.class)) || !"READY".equals(rows.getString(6)))
                        throw unavailable(
                                selected.getKey().equals(owner) ? "MOD_NOT_RUNNING" : "MOD_PROVIDER_NOT_RUNNING",
                                "The consumer or a required original provider is not running in this boot");
                    try {
                        manifest = gold.debug.windowstolinux.app.mod.manifest.ManifestReader
                                .read(rows.getString(8).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        if (!manifest.id().equals(selected.getKey())
                                || !manifest.version().toString().equals(selected.getValue())
                                || !manifest.schemaVersion().equals(Optional.ofNullable(rows.getString(7))))
                            throw new IllegalArgumentException("Original declaration identity changed");
                        releases.add(new TaskRecoveryGraph.Release(selected.getKey(), selected.getValue(),
                                rows.getString(9), schema(connection, selected.getKey(), rows.getString(7))));
                    } catch (java.io.IOException | IllegalArgumentException malformed) {
                        throw unavailable("MOD_RUNNING_GRAPH_UNVERIFIED",
                                "The original running dependency graph cannot be verified");
                    }
                }
            }
            for (var dependency : manifest.dependencies()) {
                try (var p = connection.prepareStatement("SELECT actual_version FROM mod_registry WHERE mod_id=?")) {
                    p.setString(1, dependency.id());
                    try (var r = p.executeQuery()) {
                        if (!r.next() || r.getString(1) == null)
                            throw unavailable("MOD_PROVIDER_NOT_RUNNING",
                                    "A required original provider is unavailable");
                        String actual = r.getString(1);
                        try {
                            if (!dependency.versionRange()
                                    .contains(gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse(actual)))
                                throw new IllegalArgumentException("Provider release is outside its declared range");
                        } catch (IllegalArgumentException incompatible) {
                            throw unavailable("MOD_PROVIDER_INCOMPATIBLE",
                                    "A required original provider does not match its declared release range");
                        }
                        remaining.add(Map.entry(dependency.id(), actual));
                    }
                }
            }
        }
        return new TaskRecoveryGraph(owner, releases);
    }

    /** Reads the exact ready data generation of an original release. / 读取原发布的精确就绪数据代次。
     * @param connection original serialized transaction / 原串行事务
     * @param owner exact Mod / 精确 Mod
     * @param version declared schema version, or null for stateless code / 声明数据版本，无状态代码为 null
     * @return original optional data identity / 原可选数据身份
     * @throws SQLException if state cannot be read / 无法读取状态时
     * @throws ApiException if the original data is unavailable or inconsistent / 原数据不可用或不一致时 */
    private static Optional<SchemaIdentity> schema(Connection connection, String owner, String version)
            throws SQLException, ApiException {
        if (version == null)
            return Optional.empty();
        try (var statement = connection.prepareStatement(
                "SELECT generation,physical_name,database_user,state FROM mod_schema WHERE mod_id=? AND schema_version=?")) {
            statement.setString(1, owner);
            statement.setString(2, version);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"READY".equals(rows.getString(4)))
                    throw unavailable("SCHEMA_NOT_READY", "The Mod data generation is not ready");
                var identity = new SchemaIdentity(owner, version, rows.getObject(1, UUID.class));
                var coordinates = new SchemaCoordinates(identity);
                if (!coordinates.physicalName().equals(rows.getString(2))
                        || !coordinates.databaseUser().equals(rows.getString(3)))
                    throw unavailable("SCHEMA_IDENTITY", "The registered data identity does not match its owner");
                return Optional.of(identity);
            }
        }
    }

    /** Rejects new work in any retained restart-request scope without changing accepted tasks. / 拒绝保留重启请求范围内的新工作，不改变已接纳任务。
     * @param connection original platform admission transaction / 原平台准入事务
     * @param owner exact new task owner / 精确新任务所有者
     * @throws SQLException if stored evidence cannot be read / 无法读取已存证据时
     * @throws ApiException if the owner is affected or scope cannot be verified / 所有者受影响或范围无法核验时 */
    private static void requireNoPendingIntent(Connection connection, String owner) throws SQLException, ApiException {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try (var statement = connection.prepareStatement(
                "SELECT mod_id,request_json FROM mod_operation WHERE operation_type IN ('ENABLE','DISABLE','SWITCH') AND state<>'ENDED'");
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                String stored = rows.getString(2);
                try {
                    if (stored == null || stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
                        throw new IllegalArgumentException();
                    var request = json.readTree(stored);
                    if (!request.path("formatVersion").isInt() || request.path("formatVersion").intValue() != 1
                            || !rows.getString(1).equals(request.path("ownerId").asText())
                            || !request.path("affected").isArray())
                        throw new IllegalArgumentException();
                    var affected = new HashSet<String>();
                    for (var item : request.path("affected"))
                        if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 512
                                || !affected.add(item.textValue()))
                            throw new IllegalArgumentException();
                    if (!affected.contains(rows.getString(1)))
                        throw new IllegalArgumentException();
                    if (affected.contains(owner))
                        throw unavailable("MOD_RESTART_PENDING", "This Mod is affected by a retained restart request");
                } catch (java.io.IOException | IllegalArgumentException invalid) {
                    throw unavailable("MOD_INTENT_UNVERIFIED",
                            "The retained restart admission scope cannot be verified");
                }
            }
        }
    }

    /**
     * Describes rejected admission without exposing configuration or secrets. / 描述被拒绝的准入，不暴露配置或秘密。
     * @param event stable diagnostic identifier / 稳定诊断标识
     * @param message non-secret explanation / 不含秘密的说明
     * @return public unavailable error / 公开不可用错误
     */
    private static ApiException unavailable(String event, String message) {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, event, message, Map.of(), Optional.empty()));
    }
}

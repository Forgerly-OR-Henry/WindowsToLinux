package gold.debug.windowstolinux.app.service.task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Resolves task ownership from the current boot's authoritative platform registry. / 根据当前启动的权威平台登记解析任务归属。 */
public final class PlatformTaskAdmission implements PlatformTaskRuntime.Admission {
    /** Reserved platform identity outside the valid Mod identifier syntax. / 不属于合法 Mod 标识语法的保留平台身份。 */
    public static final String PLATFORM_OWNER = "@platform";

    /** Host rescue protocol allowed to use the fixed read-only SSH verification. / 允许使用固定只读 SSH 复核的宿主救援协议。 */
    public static final String RESCUE_TYPE = "platform.ssh.rescue.v1";

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
        String schemaVersion = null;
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
            try (var statement = connection.prepareStatement(
                    "SELECT r.enabled,r.selected_version,r.actual_version,r.last_runtime_state,r.last_boot_id,v.state,v.schema_version FROM mod_registry r JOIN mod_version v ON v.mod_id=r.mod_id AND v.version=? WHERE r.mod_id=?")) {
                statement.setString(1, version);
                statement.setString(2, ownerId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1) || !version.equals(rows.getString(2))
                            || !version.equals(rows.getString(3)) || !"RUNNING".equals(rows.getString(4))
                            || !bootId.equals(rows.getObject(5, UUID.class)) || !"READY".equals(rows.getString(6)))
                        throw unavailable("MOD_NOT_RUNNING", "The bound Mod release is not running in this boot");
                    schemaVersion = rows.getString(7);
                }
            }
        }
        var claims = new ArrayList<TaskResource>();
        claims.add(new TaskResource("MOD", ownerId, ownerId, false));
        if (schemaVersion != null) {
            try (var statement = connection.prepareStatement(
                    "SELECT generation,physical_name,database_user,state FROM mod_schema WHERE mod_id=? AND schema_version=?")) {
                statement.setString(1, ownerId);
                statement.setString(2, schemaVersion);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !"READY".equals(rows.getString(4)))
                        throw unavailable("SCHEMA_NOT_READY", "The Mod data generation is not ready");
                    var identity = new SchemaIdentity(ownerId, schemaVersion, rows.getObject(1, UUID.class));
                    var coordinates = new SchemaCoordinates(identity);
                    if (!coordinates.physicalName().equals(rows.getString(2))
                            || !coordinates.databaseUser().equals(rows.getString(3)))
                        throw unavailable("SCHEMA_IDENTITY", "The registered data identity does not match its owner");
                    claims.add(new TaskResource("SCHEMA", identity.generation().toString(), ownerId, false));
                }
            }
        }
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

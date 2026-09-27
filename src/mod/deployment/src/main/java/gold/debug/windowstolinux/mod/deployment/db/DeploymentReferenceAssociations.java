package gold.debug.windowstolinux.mod.deployment.db;

import java.sql.*;
import java.util.Objects;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.*;

/** Reads authoritative retained business rows for one original platform association. / 读取一个原平台关联的权威保留业务行。 */
public final class DeploymentReferenceAssociations {
    /** Original business database. / 原业务库。 */
    private final ModDatabase database;

    /** Binds the original generation. / 绑定原代次。
     * @param database original database / 原数据库 */
    public DeploymentReferenceAssociations(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Reads one exact retained row without treating a query failure as absence. / 读取一个精确保留行，不把查询失败视为不存在。
     * @param task original current task / 原当前任务
     * @param identity original execution / 原执行
     * @param intent retained exact intent / 留存精确意图
     * @return authoritative association observation / 权威关联观测
     * @throws SQLException if evidence is inconsistent or unavailable / 证据不一致或不可用时
     * @throws ApiException if task database access is unavailable / 任务数据库访问不可用时 */
    public AssociationState observe(TaskContext task, DeploymentExecutionIdentity identity,
            DeploymentReferenceIntent intent) throws SQLException, ApiException {
        DeploymentReferenceKey.requireOriginal(intent, identity);
        try (var connection = database.openConnection(task)) {
            String key = intent.businessKey();
            if (intent.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SECRET) {
                var revision = DeploymentReferenceKey.revision(intent);
                try (var query = connection.prepareStatement(
                        "SELECT credential_id FROM secret_revision WHERE secret_id=? AND secret_revision=?")) {
                    query.setString(1, revision.identifier());
                    query.setLong(2, revision.revision());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next())
                            return AssociationState.ABSENT;
                        return intent.resourceId().equals(rows.getString(1))
                                ? AssociationState.REFERENCED
                                : AssociationState.CONFLICT;
                    }
                }
            }
            if (key.equals(DeploymentReferenceKey.applicationKey(identity.applicationId()))) {
                var current = server(connection,
                        "SELECT server_profile_id FROM managed_application WHERE application_id=?",
                        identity.applicationId(), intent.resourceId());
                if (current == AssociationState.REFERENCED
                        || retainedRelease(connection, identity.applicationId(), null, intent.resourceId()))
                    return AssociationState.REFERENCED;
                return current;
            }
            String unit = key.substring("deployment:v1:unit:".length());
            var observed = server(connection,
                    "SELECT server_profile_id FROM deployment_unit WHERE deployment_unit_id=?", unit,
                    intent.resourceId());
            if (observed == AssociationState.REFERENCED || retainedRelease(connection, null, unit, intent.resourceId()))
                return AssociationState.REFERENCED;
            if (observed != AssociationState.ABSENT)
                return observed;
            try (var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM deployment_unit_release_binding WHERE deployment_unit_id=?")) {
                query.setString(1, unit);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    return rows.getLong(1) == 0 ? AssociationState.ABSENT : AssociationState.CONFLICT;
                }
            }
        }
    }

    /** Reads immutable history rather than assuming current graph rows cover retired releases. / 读取不可变历史，不假设当前图行覆盖已退役发布。
     * @param connection current read connection / 当前读取连接
     * @param application exact application or null for unit lookup / 精确应用，单元查找时为空
     * @param unit exact unit or null for application lookup / 精确单元，应用查找时为空
     * @param server original server / 原服务器
     * @return whether retained history proves this server association / 保留历史是否证明此服务器关联
     * @throws SQLException if history is malformed or unsupported / 历史无效或格式不受支持时 */
    private static boolean retainedRelease(Connection connection, String application, String unit, String server)
            throws SQLException {
        String sql = application == null
                ? "SELECT * FROM deployment_release"
                : "SELECT * FROM deployment_release WHERE application_id=?";
        try (var query = connection.prepareStatement(sql)) {
            if (application != null)
                query.setString(1, application);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var graph = DeploymentReleaseCodec.snapshot(DeploymentReleaseRepository.row(rows)).graph();
                    if (graph.components().stream().anyMatch(component -> component.unit().server().id().equals(server)
                            && (unit == null || component.unit().id().equals(unit))))
                        return true;
                }
            }
        }
        return false;
    }

    /** Reads a server-bearing row. / 读取携带服务器身份的行。
     * @param connection current read connection / 当前读取连接
     * @param sql exact table query / 精确表查询
     * @param id original business row ID / 原业务行ID
     * @param server original platform server / 原平台服务器
     * @return matched, absent or inconsistent / 匹配、不存在或冲突
     * @throws SQLException if reading fails / 读取失败时 */
    private static AssociationState server(Connection connection, String sql, String id, String server)
            throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return AssociationState.ABSENT;
                return server.equals(rows.getString(1)) ? AssociationState.REFERENCED : AssociationState.CONFLICT;
            }
        }
    }

    /** Exact retained-business-row states. / 精确保留业务行状态。 */
    public enum AssociationState {
        /** Matching durable row remains. / 匹配持久行仍在。 */
        REFERENCED,
        /** Exact row is absent and no retained row was found. / 精确行及保留行均不存在。 */
        ABSENT,
        /** A row exists but cannot prove this association. / 有行存在但无法证明此关联。 */
        CONFLICT
    }
}

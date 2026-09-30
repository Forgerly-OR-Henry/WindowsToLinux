package gold.debug.windowstolinux.mod.deployment.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/** Stores the actual deployment-page selection in this Mod's owned preference table. / 在此Mod所属偏好表保存实际部署页面选择。 */
public final class DeploymentPreferences {
    /** Original task-bound business database. / 原任务绑定业务数据库。 */
    private final ModDatabase database;

    /** Binds only this Mod's current generation. / 仅绑定此Mod当前代次。
     * @param database owned schema capability / 所属Schema能力 */
    public DeploymentPreferences(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** One atomic, validated nonsecret selection. / 一项原子且已校验的不含秘密选择。
     * @param mode actual static, assisted or agent page / 实际静态、辅助或自主页面
     * @param approval actual manual, automatic or full-control policy / 实际人工、自动或全控策略 */
    public record Selection(String mode, String approval) {
        /** Rejects an invalid selection without repairing or hiding stored values. / 拒绝非法选择，不修复或隐藏存储值。
         * @param mode actual selected page mode / 实际所选页面模式
         * @param approval selected approval policy / 所选审批策略
         * @throws IllegalArgumentException if either value is unsupported / 任一值不受支持时 */
        public Selection {
            if (!Set.of("STATIC", "ASSISTED", "AGENT").contains(mode)
                    || !Set.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL").contains(approval))
                throw new IllegalArgumentException("Invalid deployment preference selection");
        }
    }

    /** Reads the stored pair, filling only absent values with ASSISTED/AUTOMATIC. / 读取存储配对，仅为缺失值补ASSISTED/AUTOMATIC。
     * @param task actual original query task / 实际原查询任务
     * @return validated stored selection / 已校验存储选择
     * @throws SQLException if original SQL facts cannot be read / 无法读取原SQL事实时
     * @throws ApiException if original authority or durability is unconfirmed / 原权限或持久性未确认时
     * @throws IllegalArgumentException if an existing value is invalid / 既有值非法时 */
    public Selection read(TaskContext task) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        var result = new Selection[1];
        var connection = database.openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            lock(connection);
            missing(connection, "deploy_mode", "ASSISTED");
            missing(connection, "deploy_approval", "AUTOMATIC");
            result[0] = new Selection(value(connection, "deploy_mode"), value(connection, "deploy_approval"));
        });
        return result[0];
    }

    /** Saves the user's actual page and approval choice together on the original task. / 在原任务一并保存用户实际页面与审批选择。
     * @param task original accepted publication task / 原已接纳发布任务
     * @param selection captured actual user selection / 已抓取实际用户选择
     * @throws SQLException if writes fail with confirmed rollback / 写入失败且已确认回滚时
     * @throws ApiException if original authority or durability is unconfirmed / 原权限或持久性未确认时 */
    public void save(TaskContext task, Selection selection) throws SQLException, ApiException {
        Objects.requireNonNull(selection);
        DeploymentSchema.requireIdentity(database.identity());
        var connection = database.openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            lock(connection);
            put(connection, "deploy_mode", selection.mode());
            put(connection, "deploy_approval", selection.approval());
        });
    }

    /** Serializes this pair using one private stable row, independent of agent-target gates. / 用一项私有稳定行串行化此配对，独立于自主目标门禁。
     * @param connection original transaction / 原事务
     * @throws SQLException if the pair lock is unavailable / 配对锁不可用时 */
    private static void lock(Connection connection) throws SQLException {
        put(connection, "deployment_preferences_guard", "1");
        try (var statement = connection.prepareStatement(
                "SELECT preference_value FROM preference WHERE preference_key='deployment_preferences_guard' FOR UPDATE");
                var rows = statement.executeQuery()) {
            if (!rows.next() || !rows.getString(1).equals("1"))
                throw new SQLException("Deployment preference pair lock is unavailable");
        }
    }

    /** Inserts one missing default without overwriting an existing selection. / 插入一项缺失默认值，不覆盖既有选择。
     * @param connection original transaction / 原事务
     * @param key exact owned preference key / 精确所属偏好键
     * @param fallback value used only when absent / 仅在缺失时使用的值
     * @throws SQLException if the original insertion fails / 原插入失败时 */
    private static void missing(Connection connection, String key, String fallback) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO preference(preference_key,preference_value) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM preference WHERE preference_key=?)")) {
            statement.setString(1, key);
            statement.setString(2, fallback);
            statement.setString(3, key);
            statement.executeUpdate();
        }
    }

    /** Reads the exact owned value inside the locked transaction. / 在持锁事务内读取精确所属值。
     * @param connection original transaction / 原事务
     * @param key exact owned preference key / 精确所属偏好键
     * @return unmodified stored value / 未修改存储值
     * @throws SQLException if the expected value is missing / 预期值缺失时 */
    private static String value(Connection connection, String key) throws SQLException {
        try (var statement = connection
                .prepareStatement("SELECT preference_value FROM preference WHERE preference_key=?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new SQLException("Expected deployment preference is missing");
                return rows.getString(1);
            }
        }
    }

    /** Writes one exact value inside the already owned pair transaction. / 在已持有的配对事务内写入一项精确值。
     * @param connection original transaction / 原事务
     * @param key exact owned preference key / 精确所属偏好键
     * @param value captured value / 已抓取值
     * @throws SQLException if the original write fails / 原写入失败时 */
    private static void put(Connection connection, String key, String value) throws SQLException {
        try (var statement = connection.prepareStatement(
                "MERGE INTO preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }
}

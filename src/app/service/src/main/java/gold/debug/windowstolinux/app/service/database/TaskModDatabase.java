package gold.debug.windowstolinux.app.service.database;

import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.api.db.*;
import gold.debug.windowstolinux.api.task.*;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;

/** Connects owned business JDBC directly to the original task's resource ledger. / 将所属业务 JDBC 直接连接到原始任务的资源账本。 */
public final class TaskModDatabase implements ModDatabase {
    /** Host-owned engine containing the actual registered schema. / 包含实际登记数据结构的宿主引擎。 */
    private final PlatformDatabase database;

    /** Authentic task and connection resource coordinator. / 真实任务及连接资源协调器。 */
    private final PlatformTaskRuntime tasks;

    /** Immutable bound identity, never supplied by individual author calls. / 不可变绑定身份，不由单次作者调用提供。 */
    private final SchemaCoordinates coordinates;

    /**
     * Binds one prepared caller schema to the platform task runtime. / 将一个已准备的调用方数据结构绑定到平台任务运行时。
     * @param database host-owned engine / 宿主持有的引擎
     * @param tasks host task runtime / 宿主任务运行时
     * @param identity exact prepared Mod data identity / 精确且已准备的 Mod 数据身份
     */
    public TaskModDatabase(PlatformDatabase database, PlatformTaskRuntime tasks, SchemaIdentity identity) {
        this.database = Objects.requireNonNull(database);
        this.tasks = Objects.requireNonNull(tasks);
        coordinates = new SchemaCoordinates(identity);
    }

    /**
     * Returns the bound generation only while the registry still accepts it. / 仅在登记仍接受此代次时返回绑定身份。
     * @return bound immutable schema identity / 绑定的不可变数据身份
     * @throws ApiException if the schema is stale or unavailable / 数据结构过期或不可用时
     */
    @Override
    public SchemaIdentity identity() throws ApiException {
        try {
            coordinates.requireRegistered(database, Set.of("READY"));
            return coordinates.identity();
        } catch (SQLException failure) {
            throw unavailable("The bound Mod schema is unavailable");
        }
    }

    /**
     * Marks business use before exposing a non-admin task-tracked connection. / 暴露非管理员任务跟踪连接前标记业务使用。
     * @param task authentic task already holding the exact schema generation / 已持有精确数据代次的真实任务
     * @return one independent standard JDBC identity view / 单个独立的标准 JDBC 身份视图
     * @throws ApiException if caller or task admission fails / 调用方或任务准入失败时
     * @throws SQLException if registration, opening or cleanup cannot be confirmed / 无法确认登记、打开或清理时
     */
    @Override
    public Connection openConnection(TaskContext task) throws ApiException, SQLException {
        if (javax.swing.SwingUtilities.isEventDispatchThread())
            throw new ApiException(new Problem(ProblemCode.CONTRACT_VIOLATION, "MOD_JDBC_REQUIRES_BACKGROUND",
                    "Task JDBC access must run on a background thread", Map.of(), Optional.empty()));
        coordinates.requireRegistered(database, Set.of("READY"));
        String password;
        try {
            password = database.schemaPassword(coordinates);
        } catch (java.security.GeneralSecurityException failure) {
            throw new SQLException("Schema credentials are unavailable", failure);
        }
        var lease = tasks.retainSchemaConnection(task, coordinates.identity());
        var tag = lease.id().toString();
        try {
            try (var c = database.openConnection();
                    var p = c.prepareStatement(
                            "UPDATE mod_schema SET ever_used=TRUE WHERE mod_id=? AND schema_version=? AND generation=? AND state='READY'")) {
                p.setString(1, coordinates.identity().ownerId());
                p.setString(2, coordinates.identity().schemaVersion());
                p.setObject(3, coordinates.identity().generation());
                if (p.executeUpdate() != 1)
                    throw new SQLException("Schema generation is stale or unavailable");
            }
            return database.openInternalConnection(coordinates.physicalName(), coordinates.databaseUser(), password,
                    tag, () -> {
                        try {
                            lease.close();
                        } catch (ApiException failure) {
                            throw new SQLException("Connection physically closed but task release requires retry",
                                    failure);
                        }
                    });
        } catch (SQLException failure) {
            if (database.openConnections(tag) == 0)
                try {
                    lease.close();
                } catch (ApiException closing) {
                    failure.addSuppressed(
                            new SQLException("Connection preparation cleanup requires reconciliation", closing));
                }
            throw failure;
        }
    }

    /**
     * Builds a stable public diagnostic without a driver exception chain. / 构造稳定的公开诊断，不附带驱动异常链。
     * @param message non-secret host diagnostic / 不含秘密的宿主诊断
     * @return unavailable capability exception / 能力不可用异常
     */
    private static ApiException unavailable(String message) {
        return new ApiException(
                new Problem(ProblemCode.UNAVAILABLE, "MOD_SCHEMA_UNAVAILABLE", message, Map.of(), Optional.empty()));
    }
}

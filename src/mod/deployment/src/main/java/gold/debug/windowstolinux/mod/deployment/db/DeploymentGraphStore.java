package gold.debug.windowstolinux.mod.deployment.db;

import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Stores complete topology over existing owned units without querying platform-private tables. / 在既有所属单元上保存完整拓扑，不查询平台私有表。 */
public final class DeploymentGraphStore {
    /** Original task-bound database capability. / 原任务绑定数据库能力。 */
    private final ModDatabase database;

    /** Binds the original owned generation. / 绑定原所属代次。
     * @param database task-bound author database / 任务绑定作者数据库 */
    public DeploymentGraphStore(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Replaces topology atomically, requiring previously persisted matching units and runtime contracts. / 原子替换拓扑，要求先前持久化的单元及运行契约完全匹配。
     * @param task original active task / 原活动任务
     * @param graph complete reviewed graph / 完整审阅图
     * @param updatedAt time of this business observation / 此业务观测时间
     * @throws SQLException if units, ownership or SQL cannot be confirmed / 无法确认单元、归属或SQL时
     * @throws ApiException if task access or transaction acknowledgement is unavailable / 任务访问或事务确认不可用时 */
    public void save(TaskContext task, DeploymentApplicationGraph graph, Instant updatedAt)
        throws SQLException, ApiException {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(updatedAt);
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> save(connection, graph, updatedAt));
    }

    /** Reads one consistent complete graph; uncommitted or retained drafts are not applications. / 读取一个一致的完整图，未提交或保留草稿不算应用。
     * @param task original active task / 原活动任务
     * @param applicationId exact business identity / 精确业务身份
     * @return complete graph, or empty for absent or draft rows / 完整图，不存在或草稿时为空
     * @throws SQLException if persisted facts cannot form a valid graph / 持久事实无法构成有效图时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public Optional<DeploymentApplicationGraph> find(TaskContext task, String applicationId)
        throws SQLException, ApiException {
        Objects.requireNonNull(applicationId);
        try (var connection = connection(task)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                return read(connection, applicationId);
            } finally {
                connection.rollback();
            }
        }
    }

    /** Opens only the current official deployment generation. / 仅打开当前官方部署代次。
     * @param task original task / 原任务
     * @return owned connection / 所属连接
     * @throws SQLException if JDBC is unavailable / JDBC不可用时
     * @throws ApiException if identity or task access is unavailable / 身份或任务访问不可用时 */
    private Connection connection(TaskContext task) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        return database.openConnection(task);
    }

    /** Writes within the original caller transaction so future release recording can share atomicity. / 在原调用方事务内写入，使发布记录可共用原子性。
     * @param connection original active business transaction / 原活动业务事务
     * @param graph complete reviewed graph / 完整审阅图
     * @param updatedAt original observation time / 原观测时间
     * @throws SQLException if existing facts or writes are inconsistent / 既有事实或写入不一致时 */
    static void save(Connection connection, DeploymentApplicationGraph graph, Instant updatedAt) throws SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Application graph requires a business transaction");
        for (var component : graph.components().stream().sorted(Comparator.comparing(c -> c.unit().id())).toList())
            requireUnit(connection, component);
        prepare(connection, graph, updatedAt);
        try (var dependencies = connection
            .prepareStatement("DELETE FROM managed_application_dependency WHERE application_id=?");
             var components = connection
                 .prepareStatement("DELETE FROM managed_application_component WHERE application_id=?")) {
            dependencies.setString(1, graph.applicationId());
            dependencies.executeUpdate();
            components.setString(1, graph.applicationId());
            components.executeUpdate();
        }
        insertComponents(connection, graph);
        insertDependencies(connection, graph);
        try (var statement = connection.prepareStatement(
            "UPDATE managed_application SET health_component_id=?,health_check_payload=?,graph_ready=TRUE,updated_at=? WHERE application_id=?")) {
            statement.setString(1, graph.healthComponentId());
            statement.setBytes(2, new DeploymentHealthCodec().write(graph.healthCheck()));
            statement.setLong(3, updatedAt.toEpochMilli());
            statement.setString(4, graph.applicationId());
            if (statement.executeUpdate() != 1)
                throw new SQLException("Application graph completion was not confirmed");
        } catch (IOException failure) {
            throw new SQLException("Application health cannot be persisted", failure);
        }
    }

    /** Locks existing unit facts and rejects retargeting or stale runtime definitions. / 锁定既有单元事实，拒绝改换目标或陈旧运行定义。
     * @param connection original business transaction / 原业务事务
     * @param component reviewed component / 已审阅组件
     * @throws SQLException if the unit is absent or differs / 单元不存在或不一致时 */
    private static void requireUnit(Connection connection, DeploymentApplicationGraph.Part component)
        throws SQLException {
        try (var statement = connection
            .prepareStatement("SELECT * FROM deployment_unit WHERE deployment_unit_id=? FOR UPDATE")) {
            statement.setString(1, component.unit().id());
            try (var row = statement.executeQuery()) {
                if (!row.next() || !unit(row).equals(component.unit()) || !new DeploymentUnitRuntimeCodec()
                    .read(DeploymentPayloadReader.read(row, "runtime_payload")).equals(component.runtime()))
                    throw new SQLException("Reviewed component differs from its persisted unit");
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new SQLException("Persisted deployment unit is invalid", failure);
        }
    }

    /** Clears the health foreign key before replacement, retaining the original server identity. / 替换前清除健康外键，保留原服务器身份。
     * @param connection original business transaction / 原业务事务
     * @param graph complete reviewed graph / 完整审阅图
     * @param updatedAt original observation time / 原观测时间
     * @throws SQLException if the existing application belongs elsewhere / 既有应用属于其他目标时 */
    private static void prepare(Connection connection, DeploymentApplicationGraph graph, Instant updatedAt)
        throws SQLException {
        String server = graph.components().getFirst().unit().server().id();
        boolean exists;
        try (var statement = connection.prepareStatement(
            "SELECT server_profile_id FROM managed_application WHERE application_id=? FOR UPDATE")) {
            statement.setString(1, graph.applicationId());
            try (var row = statement.executeQuery()) {
                exists = row.next();
                if (exists && !server.equals(row.getString(1)))
                    throw new SQLException("Application identity belongs to another server");
            }
        }
        if (exists) {
            try (var statement = connection.prepareStatement(
                "UPDATE managed_application SET graph_ready=FALSE,health_component_id=NULL,health_check_payload=NULL WHERE application_id=?")) {
                statement.setString(1, graph.applicationId());
                statement.executeUpdate();
            }
        } else {
            try (var statement = connection.prepareStatement(
                "INSERT INTO managed_application(application_id,server_profile_id,updated_at,graph_ready) VALUES(?,?,?,FALSE)")) {
                statement.setString(1, graph.applicationId());
                statement.setString(2, server);
                statement.setLong(3, updatedAt.toEpochMilli());
                statement.executeUpdate();
            }
        }
    }

    /** Inserts all reviewed component documents into the current draft. / 将全部审阅组件文档写入当前草稿。
     * @param connection original business transaction / 原业务事务
     * @param graph complete reviewed graph / 完整审阅图
     * @throws SQLException if documents or ownership constraints cannot persist / 文档或归属约束无法持久化时 */
    private static void insertComponents(Connection connection, DeploymentApplicationGraph graph) throws SQLException {
        try (var statement = connection.prepareStatement(
            "INSERT INTO managed_application_component(application_id,component_id,deployment_unit_id,reviewed_runtime_payload,reviewed_data_paths_payload,reviewed_resource_bindings_payload) VALUES(?,?,?,?,?,?)")) {
            for (var component : graph.components()) {
                statement.setString(1, graph.applicationId());
                statement.setString(2, component.componentId());
                statement.setString(3, component.unit().id());
                statement.setBytes(4, new DeploymentRuntimeCodec().write(component.reviewedRuntime()));
                statement.setBytes(5, new DeploymentStoragePathCodec().write(component.dataPaths()));
                statement.setBytes(6, new DeploymentResourceCodec().write(component.resources()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (IOException failure) {
            throw new SQLException("Reviewed component documents cannot be persisted", failure);
        }
    }

    /** Persists dependencies only after both endpoint components exist. / 仅在两端组件存在后持久化依赖。
     * @param connection original business transaction / 原业务事务
     * @param graph complete reviewed graph / 完整审阅图
     * @throws SQLException if dependencies cannot persist / 依赖无法持久化时 */
    private static void insertDependencies(Connection connection, DeploymentApplicationGraph graph)
        throws SQLException {
        try (var statement = connection.prepareStatement(
            "INSERT INTO managed_application_dependency(application_id,component_id,dependency_component_id) VALUES(?,?,?)")) {
            for (var component : graph.components())
                for (var dependency : component.dependencies()) {
                    statement.setString(1, graph.applicationId());
                    statement.setString(2, component.componentId());
                    statement.setString(3, dependency);
                    statement.addBatch();
                }
            statement.executeBatch();
        }
    }

    /** Reconstructs a complete graph from owned tables only. / 仅从所属表重建完整图。
     * @param connection original read transaction or stable validation scope / 原读取事务或稳定验证作用域
     * @param applicationId exact application identity / 精确应用身份
     * @return validated graph or absent/draft result / 已验证图或不存在及草稿结果
     * @throws SQLException if persisted facts are invalid / 持久事实无效时 */
    static Optional<DeploymentApplicationGraph> read(Connection connection, String applicationId) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT server_profile_id,health_component_id,health_check_payload FROM managed_application WHERE application_id=? AND graph_ready=TRUE")) {
            statement.setString(1, applicationId);
            try (var row = statement.executeQuery()) {
                if (!row.next())
                    return Optional.empty();
                String server = row.getString("server_profile_id");
                var components = components(connection, applicationId);
                if (components.stream().anyMatch(c -> !c.unit().server().id().equals(server)))
                    throw new SQLException("Application and component servers differ");
                var graph = new DeploymentApplicationGraph(applicationId, row.getString("health_component_id"),
                    new DeploymentHealthCodec().read(DeploymentPayloadReader.read(row, "health_check_payload")),
                    components);
                return Optional.of(graph);
            }
        } catch (IOException | IllegalArgumentException | NullPointerException failure) {
            throw new SQLException("Stored application graph is invalid", failure);
        }
    }

    /** Reads complete reviewed component facts, rejecting oversized graphs before accumulation. / 读取完整审阅组件事实，在累积前拒绝超限图。
     * @param connection original read connection / 原读取连接
     * @param applicationId exact application identity / 精确应用身份
     * @return original reviewed components / 原审阅组件
     * @throws SQLException if rows are unavailable / 记录不可用时
     * @throws IOException if component documents are malformed / 组件文档格式错误时 */
    private static List<DeploymentApplicationGraph.Part> components(Connection connection, String applicationId)
        throws SQLException, IOException {
        var dependencies = dependencies(connection, applicationId);
        var result = new ArrayList<DeploymentApplicationGraph.Part>();
        try (var statement = connection.prepareStatement(
            "SELECT c.component_id,c.reviewed_runtime_payload,c.reviewed_data_paths_payload,c.reviewed_resource_bindings_payload,u.* FROM managed_application_component c JOIN deployment_unit u ON u.deployment_unit_id=c.deployment_unit_id WHERE c.application_id=? ORDER BY c.component_id")) {
            statement.setString(1, applicationId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == 64)
                        throw new SQLException("Application graph exceeds its component bound");
                    var runtime = new DeploymentUnitRuntimeCodec()
                        .read(DeploymentPayloadReader.read(rows, "runtime_payload"));
                    result.add(new DeploymentApplicationGraph.Part(rows.getString("component_id"), unit(rows),
                        runtime, dependencies.getOrDefault(rows.getString("component_id"), List.of()),
                        new DeploymentRuntimeCodec().read(
                            DeploymentPayloadReader.read(rows, "reviewed_runtime_payload"),
                            runtime.healthCheck()),
                        new DeploymentStoragePathCodec()
                            .read(DeploymentPayloadReader.read(rows, "reviewed_data_paths_payload")),
                        new DeploymentResourceCodec()
                            .read(DeploymentPayloadReader.read(rows, "reviewed_resource_bindings_payload"))));
                }
            }
        }
        return result;
    }

    /** Reads the original bounded dependency relation. / 读取原有界依赖关系。
     * @param connection original read connection / 原读取连接
     * @param applicationId exact application identity / 精确应用身份
     * @return dependencies grouped by component / 按组件分组的依赖
     * @throws SQLException if rows are unavailable or exceed the graph bound / 记录不可用或超出图上限时 */
    private static Map<String, List<String>> dependencies(Connection connection, String applicationId)
        throws SQLException {
        var result = new HashMap<String, List<String>>();
        try (var statement = connection.prepareStatement(
            "SELECT component_id,dependency_component_id FROM managed_application_dependency WHERE application_id=? ORDER BY component_id,dependency_component_id")) {
            statement.setString(1, applicationId);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 64 * 63)
                        throw new SQLException("Application dependencies exceed their bound");
                    result.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(rows.getString(2));
                }
            }
        }
        return result;
    }

    /** Reads persisted unit identity without querying or inventing endpoint metadata. / 读取持久单元身份，不查询或虚构端点元数据。
     * @param row original unit row / 原单元行
     * @return exact owned unit identity / 精确所属单元身份
     * @throws SQLException if stored columns are invalid / 存储列无效时 */
    static DeploymentUnitIdentity unit(ResultSet row) throws SQLException {
        String id = row.getString("deployment_unit_id");
        var unit = new DeploymentUnitIdentity(id, new ServerId(row.getString("server_profile_id")),
            row.getString("systemd_unit_name"), row.getString("release_root_path"),
            row.getString("ownership_manifest_sha256"));
        if (!id.equals(unit.id()))
            throw new SQLException("Stored deployment identity is not canonical");
        return unit;
    }

    /** Validates each complete application without publishing retained drafts. / 校验每个完整应用，不发布保留草稿。
     * @param connection original SELECT-only validation connection / 原仅SELECT验证连接
     * @throws SQLException if a complete graph is inconsistent / 完整图不一致时 */
    static void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement
                 .executeQuery("SELECT application_id FROM managed_application WHERE graph_ready=TRUE")) {
            while (rows.next())
                read(connection, rows.getString(1))
                    .orElseThrow(() -> new SQLException("Complete application disappeared"));
        }
    }
}

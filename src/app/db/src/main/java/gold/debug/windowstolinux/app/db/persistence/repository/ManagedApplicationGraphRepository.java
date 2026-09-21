package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.db.persistence.serialization.ComponentPathPersistenceCodec;
import gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec;
import gold.debug.windowstolinux.app.db.persistence.serialization.ManagedResourcePersistenceCodec;
import gold.debug.windowstolinux.shared.config.persistence.serialization.HealthCheckCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stores durable whole-application topology atomically with successful component releases. / 将持久整应用拓扑与成功组件版本原子保存。
 */
public final class ManagedApplicationGraphRepository {
    /**
     * Bound deployment runtime persistence codec collaborator for RUNTIME CODEC.
     * <p>处理运行时编解码器的部署运行时持久化编解码器协作对象。
     */
    private static final DeploymentRuntimePersistenceCodec RUNTIME_CODEC = new DeploymentRuntimePersistenceCodec();
    /**
     * Bound component path persistence codec collaborator for DATA PATH CODEC.
     * <p>处理数据路径编解码器的组件路径持久化编解码器协作对象。
     */
    private static final ComponentPathPersistenceCodec DATA_PATH_CODEC = new ComponentPathPersistenceCodec();
    /**
     * Bound managed resource persistence codec collaborator for RESOURCE CODEC.
     * <p>处理资源编解码器的受管资源持久化编解码器协作对象。
     */
    private static final ManagedResourcePersistenceCodec RESOURCE_CODEC = new ManagedResourcePersistenceCodec();
    /**
     * Bound health check codec collaborator for HEALTH CODEC.
     * <p>处理健康编解码器的健康检查编解码器协作对象。
     */
    private static final HealthCheckCodec HEALTH_CODEC = new HealthCheckCodec();
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;

    /**
     * Creates the focused graph repository. / 创建聚焦的图仓库。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedApplicationGraphRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Atomically records component success together with its durable whole-application graph. / 原子记录组件成功状态及其持久整应用图。
     *
     * @param graph graph / 图
     * @param deployments deployments / 部署集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void recordSuccessfulApplication(ManagedApplicationGraph graph,
                                            List<SuccessfulManagedDeployment> deployments) throws SQLException {
        graph = Objects.requireNonNull(graph, "graph");
        List<SuccessfulManagedDeployment> records = List.copyOf(Objects.requireNonNull(deployments, "deployments"));
        Map<String, SuccessfulManagedDeployment> byApplication = new LinkedHashMap<>();
        records.forEach(record -> byApplication.put(record.application().id(), record));
        Set<String> graphApplications = graph.components().stream()
                .map(component -> component.application().id()).collect(java.util.stream.Collectors.toSet());
        if (byApplication.size() != records.size() || !byApplication.keySet().equals(graphApplications)
                || graph.components().stream().anyMatch(component -> !component.runtimeConfiguration().equals(
                        byApplication.get(component.application().id()).runtimeConfiguration()))
                || graph.components().stream().anyMatch(component -> component.reviewedRuntime().isEmpty()
                        || component.reviewedDataPaths().isEmpty()
                        || component.reviewedResourceBindings().isEmpty())
                || graph.applicationHealthCheck().isEmpty()) {
            throw new IllegalArgumentException("successful deployments must exactly match the managed application graph");
        }
        try (Connection connection = connections.open()) {
            ManagedApplicationGraph stableGraph = graph;
            RepositoryTransactionExecutor.execute(connection, () -> {
                ManagedApplicationRepository.recordSuccessfulDeployments(connection, records);
                replaceGraph(connection, stableGraph);
            });
        }
    }

    /**
     * Finds a durable whole-application graph without returning build or secret values. / 查找不返回构建值或秘密值的持久整应用图。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public Optional<ManagedApplicationGraph> find(String applicationId) throws SQLException {
        applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
        try (Connection connection = connections.open(); PreparedStatement graphStatement = connection.prepareStatement("""
                SELECT health_component_id, application_health_check
                FROM managed_application_graph WHERE application_id=?
                """)) {
            graphStatement.setString(1, applicationId);
            String healthComponentId;
            Optional<gold.debug.windowstolinux.shared.model.health.HealthCheck> applicationHealthCheck;
            try (ResultSet result = graphStatement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                healthComponentId = result.getString("health_component_id");
                byte[] storedHealthCheck = result.getBytes("application_health_check");
                try {
                    applicationHealthCheck = storedHealthCheck == null
                            ? Optional.empty() : Optional.of(HEALTH_CODEC.read(storedHealthCheck));
                } catch (java.io.IOException exception) {
                    throw new SQLException("stored application health definition is invalid", exception);
                }
            }
            Map<String, List<String>> dependencies = readDependencies(connection, applicationId);
            List<ManagedApplicationGraph.Component> components = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT gc.component_id,
                           a.id, a.systemd_unit, a.release_root, a.ownership_manifest_sha256,
                           s.id AS server_id, s.host, s.ssh_port, s.host_key_sha256,
                           r.health_kind, r.http_endpoint, r.http_expected_status, r.tcp_port,
                           r.health_timeout_seconds, r.tcp_stability_seconds, r.user_access_url, r.identity_policy, r.runtime_payload,
                           gc.reviewed_runtime, gc.reviewed_data_paths, gc.reviewed_resource_bindings
                    FROM managed_application_graph_component gc
                    JOIN managed_application a ON a.id=gc.managed_application_id
                    JOIN server s ON s.id=a.server_id
                    JOIN managed_application_runtime_configuration r ON r.application_id=a.id
                    WHERE gc.application_id=? ORDER BY gc.component_id
                    """)) {
                statement.setString(1, applicationId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String componentId = result.getString("component_id");
                        var runtimeConfiguration = ManagedApplicationRepository.readRuntime(result);
                        byte[] storedRuntime = result.getBytes("reviewed_runtime");
                        Optional<gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification>
                                reviewedRuntime = Optional.empty();
                        if (storedRuntime != null) {
                            try {
                                reviewedRuntime = Optional.of(RUNTIME_CODEC.read(
                                        storedRuntime, runtimeConfiguration.healthCheck()));
                            } catch (java.io.IOException exception) {
                                throw new SQLException("stored reviewed runtime definition is invalid", exception);
                            }
                        }
                        byte[] storedDataPaths = result.getBytes("reviewed_data_paths");
                        Optional<List<gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath>>
                                reviewedDataPaths = Optional.empty();
                        if (storedDataPaths != null) {
                            try {
                                reviewedDataPaths = Optional.of(DATA_PATH_CODEC.read(storedDataPaths));
                            } catch (java.io.IOException exception) {
                                throw new SQLException("stored reviewed data-path definition is invalid", exception);
                            }
                        }
                        byte[] storedResourceBindings = result.getBytes("reviewed_resource_bindings");
                        Optional<gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings>
                                reviewedResourceBindings = Optional.empty();
                        if (storedResourceBindings != null) {
                            try {
                                reviewedResourceBindings = Optional.of(RESOURCE_CODEC.read(storedResourceBindings));
                            } catch (java.io.IOException exception) {
                                throw new SQLException("stored reviewed resource-binding definition is invalid", exception);
                            }
                        }
                        components.add(new ManagedApplicationGraph.Component(componentId,
                                ManagedApplicationRepository.readApplication(result),
                                runtimeConfiguration, dependencies.getOrDefault(componentId, List.of()),
                                reviewedRuntime, reviewedDataPaths, reviewedResourceBindings));
                    }
                }
            }
            return Optional.of(new ManagedApplicationGraph(applicationId, healthComponentId,
                    applicationHealthCheck, components));
        }
    }

    /**
     * Replaces the application's component topology and reviewed resource bindings inside the caller's transaction.
     * <p>在调用方事务内替换应用组件拓扑及已审阅资源绑定。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param graph graph / 图
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static void replaceGraph(Connection connection, ManagedApplicationGraph graph) throws SQLException {
        try (PreparedStatement dependencies = connection.prepareStatement(
                "DELETE FROM managed_application_graph_dependency WHERE application_id=?");
             PreparedStatement components = connection.prepareStatement(
                     "DELETE FROM managed_application_graph_component WHERE application_id=?")) {
            dependencies.setString(1, graph.applicationId());
            dependencies.executeUpdate();
            components.setString(1, graph.applicationId());
            components.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_graph (
                    application_id, server_id, health_component_id, application_health_check, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET server_id=excluded.server_id,
                    health_component_id=excluded.health_component_id,
                    application_health_check=excluded.application_health_check, updated_at=excluded.updated_at
                """)) {
            statement.setString(1, graph.applicationId());
            statement.setString(2, graph.components().getFirst().application().server().id());
            statement.setString(3, graph.healthComponentId());
            try {
                statement.setBytes(4, HEALTH_CODEC.write(graph.applicationHealthCheck().orElseThrow()));
            } catch (java.io.IOException exception) {
                throw new SQLException("application health definition cannot be persisted", exception);
            }
            statement.setLong(5, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
        insertComponents(connection, graph);
        insertDependencies(connection, graph);
    }

    /**
     * Inserts reviewed components in the application graph.
     * <p>插入应用图中的已审阅组件。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param graph graph / 图
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static void insertComponents(Connection connection, ManagedApplicationGraph graph) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_graph_component (
                    application_id, component_id, managed_application_id, reviewed_runtime, reviewed_data_paths,
                    reviewed_resource_bindings
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (ManagedApplicationGraph.Component component : graph.components()) {
                statement.setString(1, graph.applicationId());
                statement.setString(2, component.componentId());
                statement.setString(3, component.application().id());
                try {
                    statement.setBytes(4, RUNTIME_CODEC.write(component.reviewedRuntime().orElseThrow()));
                    statement.setBytes(5, DATA_PATH_CODEC.write(component.reviewedDataPaths().orElseThrow()));
                    statement.setBytes(6, RESOURCE_CODEC.write(component.reviewedResourceBindings().orElseThrow()));
                } catch (java.io.IOException exception) {
                    throw new SQLException("reviewed runtime, data-path or resource-binding definition cannot be persisted", exception);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Inserts component identifiers that must precede this component.
     * <p>插入必须先于当前组件执行的组件标识。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param graph graph / 图
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static void insertDependencies(Connection connection, ManagedApplicationGraph graph) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_graph_dependency (
                    application_id, component_id, dependency_component_id
                ) VALUES (?, ?, ?)
                """)) {
            for (ManagedApplicationGraph.Component component : graph.components()) {
                for (String dependency : component.dependencies()) {
                    statement.setString(1, graph.applicationId());
                    statement.setString(2, component.componentId());
                    statement.setString(3, dependency);
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    /**
     * Reads component identifiers that must precede this component.
     * <p>读取必须先于当前组件执行的组件标识。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param applicationId managed application identifier / 受管应用标识
     * @return component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static Map<String, List<String>> readDependencies(Connection connection, String applicationId)
            throws SQLException {
        Map<String, List<String>> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT component_id, dependency_component_id
                FROM managed_application_graph_dependency
                WHERE application_id=? ORDER BY component_id, dependency_component_id
                """)) {
            statement.setString(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.computeIfAbsent(result.getString("component_id"), ignored -> new ArrayList<>())
                        .add(result.getString("dependency_component_id"));
            }
        }
        return values;
    }
}

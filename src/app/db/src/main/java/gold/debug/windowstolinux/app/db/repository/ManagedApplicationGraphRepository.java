package gold.debug.windowstolinux.app.db.repository;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;

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

/** Stores durable whole-application topology atomically with successful component releases. / 将持久整应用拓扑与成功组件版本原子保存。 */
public final class ManagedApplicationGraphRepository {
    private final DesktopConnectionFactory connections;

    /** Creates the focused graph repository. / 创建聚焦的图仓库。 */
    public ManagedApplicationGraphRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Atomically records component success together with its durable whole-application graph. / 原子记录组件成功状态及其持久整应用图。 */
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
                        byApplication.get(component.application().id()).runtimeConfiguration()))) {
            throw new IllegalArgumentException("successful deployments must exactly match the managed application graph");
        }
        try (Connection connection = connections.open()) {
            ManagedApplicationGraph stableGraph = graph;
            RepositoryTransactions.execute(connection, () -> {
                ManagedApplicationRepository.recordSuccessfulDeployments(connection, records);
                replaceGraph(connection, stableGraph);
            });
        }
    }

    /** Finds a durable whole-application graph without returning build or secret values. / 查找不返回构建值或秘密值的持久整应用图。 */
    public Optional<ManagedApplicationGraph> find(String applicationId) throws SQLException {
        applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
        try (Connection connection = connections.open(); PreparedStatement graphStatement = connection.prepareStatement("""
                SELECT health_component_id FROM managed_application_graph WHERE application_id=?
                """)) {
            graphStatement.setString(1, applicationId);
            String healthComponentId;
            try (ResultSet result = graphStatement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                healthComponentId = result.getString("health_component_id");
            }
            Map<String, List<String>> dependencies = readDependencies(connection, applicationId);
            List<ManagedApplicationGraph.Component> components = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT gc.component_id,
                           a.id, a.systemd_unit, a.release_root, a.ownership_manifest_sha256,
                           s.id AS server_id, s.host, s.ssh_port, s.host_key_sha256,
                           r.health_kind, r.http_endpoint, r.http_expected_status, r.tcp_port,
                           r.health_timeout_seconds, r.tcp_stability_seconds, r.user_access_url
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
                        components.add(new ManagedApplicationGraph.Component(componentId,
                                ManagedApplicationRepository.readApplication(result),
                                ManagedApplicationRepository.readRuntime(result),
                                dependencies.getOrDefault(componentId, List.of())));
                    }
                }
            }
            return Optional.of(new ManagedApplicationGraph(applicationId, healthComponentId, components));
        }
    }

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
                INSERT INTO managed_application_graph (application_id, server_id, health_component_id, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET server_id=excluded.server_id,
                    health_component_id=excluded.health_component_id, updated_at=excluded.updated_at
                """)) {
            statement.setString(1, graph.applicationId());
            statement.setString(2, graph.components().getFirst().application().server().id());
            statement.setString(3, graph.healthComponentId());
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
        insertComponents(connection, graph);
        insertDependencies(connection, graph);
    }

    private static void insertComponents(Connection connection, ManagedApplicationGraph graph) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_application_graph_component (
                    application_id, component_id, managed_application_id
                ) VALUES (?, ?, ?)
                """)) {
            for (ManagedApplicationGraph.Component component : graph.components()) {
                statement.setString(1, graph.applicationId());
                statement.setString(2, component.componentId());
                statement.setString(3, component.application().id());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

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

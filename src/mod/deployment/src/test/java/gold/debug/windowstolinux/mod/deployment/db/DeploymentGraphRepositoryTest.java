package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.*;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentGraphRepositoryTest extends DeploymentRuntimeFixture {
    static final Instant OBSERVED = Instant.parse("2026-09-27T00:00:00Z");

    DeploymentApplicationGraph.Component component(String id, String server, List<String> dependencies) {
        var health = new DeploymentHealthCheck.Tcp(8080, 20, 2);
        var definition = new DeploymentRuntimeDefinition.NodeService(22, health);
        var unit = DeploymentUnitIdentity.forManaged(id, new ServerRef(server), "a".repeat(64));
        var runtime = new DeploymentUnitRuntimeConfiguration(health, Optional.empty(), definition.identityPolicy(),
                definition.workload());
        var path = new DeploymentDataPath("uploads", DeploymentDataPath.AccessMode.READ_WRITE, "uploads-v1", false);
        var binding = new DeploymentFileBinding("uploads", path, DeploymentStorageLocation.defaults(),
                DeploymentStorageLocation.StorageResourceType.FILE);
        return new DeploymentApplicationGraph.Component(id, unit, runtime, dependencies, definition, List.of(path),
                new DeploymentResourceBindings(List.of(binding), Optional.empty()));
    }

    DeploymentApplicationGraph graph(String id) {
        var web = component("web", "server", List.of("worker"));
        var worker = component("worker", "server", List.of());
        return new DeploymentApplicationGraph(id, "web", new DeploymentHealthCheck.Process(20, 2),
                List.of(worker, web));
    }

    void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var observed = new AtomicReference<Throwable>();
        var handle = context.tasks()
                .submit(new TaskRequest("graph_acceptance", "Graph acceptance", List.of("server"), true), task -> {
                    try {
                        return work.run(task);
                    } catch (Exception | AssertionError failure) {
                        observed.set(failure);
                        throw failure;
                    }
                });
        var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        if (observed.get() != null)
            throw new AssertionError("Original fixture work failed", observed.get());
        assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
    }

    void server(PlatformRuntime runtime) throws Exception {
        runtime.servers().saveServerProfile(
                gold.debug.windowstolinux.app.service.contract.profile.ServerProfile
                        .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "tester", "fixture-password")),
                "fixture-secret".toCharArray());
    }

    OperationResult<Void> success() {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    void seed(PlatformRuntime runtime, TaskContext task, DeploymentApplicationGraph graph) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        for (var component : graph.components()) {
            var reference = context.servers().reserve(component.unit().server(), "unit:" + component.unit().id(), task);
            var unit = component.unit();
            try (var connection = context.db().openConnection(task);
                    var statement = connection.prepareStatement(
                            "INSERT INTO deployment_unit(deployment_unit_id,server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256,runtime_payload,current_release_sha256,current_release_published_at,has_display_override,display_name,application_category) VALUES(?,?,?,?,?,?,?,1,1,'Retained name','APP')")) {
                statement.setString(1, unit.id());
                statement.setString(2, unit.server().id());
                statement.setString(3, unit.systemdUnit());
                statement.setString(4, unit.releaseRoot());
                statement.setString(5, unit.ownershipManifestSha256());
                statement.setBytes(6, new DeploymentUnitRuntimeCodec().write(component.runtime()));
                statement.setString(7, "b".repeat(64));
                statement.executeUpdate();
            }
            context.servers().resolve(reference.id(), ReferenceResolution.REFERENCED, task);
        }
    }

    void sql(PlatformRuntime runtime, TaskContext task, String sql) throws Exception {
        try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    void valid(PlatformRuntime runtime) throws Exception {
        try (var scope = scope(runtime, true)) {
            var report = new DeploymentSchema().validate(scope);
            assertTrue(report.valid(), report.toString());
        }
    }

    @Test
    void completeGraphAndExactServerReferencesSurviveRestartWithoutPlatformTableAccess() throws Exception {
        enable();
        var graph = graph("application");
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                seed(runtime, task, graph);
                var database = runtime.modContexts().get(OWNER).context().db();
                var repository = new DeploymentGraphRepository(database);
                repository.save(task, graph, OBSERVED);
                assertEquals(graph, repository.find(task, "application").orElseThrow());
                try (var connection = database.openConnection(task); var statement = connection.createStatement()) {
                    assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM APP.server_profile"));
                }
                return success();
            });
            valid(runtime);
            assertEquals(2, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            assertThrows(SQLException.class, () -> runtime.persistence().servers().deleteServerProfile("server"));
        }
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var repository = new DeploymentGraphRepository(runtime.modContexts().get(OWNER).context().db());
                assertEquals(graph, repository.find(task, "application").orElseThrow());
                assertTrue(repository.find(task, "missing").isEmpty());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void replacementClearsHealthForeignKeyAndPreservesUnitReleasesAndPresentation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var original = graph("application");
                seed(runtime, task, original);
                var database = runtime.modContexts().get(OWNER).context().db();
                var repository = new DeploymentGraphRepository(database);
                repository.save(task, original, OBSERVED);
                var replacement = new DeploymentApplicationGraph("application", "worker", original.healthCheck(),
                        List.of(component("worker", "server", List.of())));
                repository.save(task, replacement, OBSERVED.plusSeconds(1));
                repository.save(task, replacement, OBSERVED.plusSeconds(1));
                assertEquals(replacement, repository.find(task, "application").orElseThrow());
                try (var connection = database.openConnection(task);
                        var statement = connection.createStatement();
                        var rows = statement
                                .executeQuery("SELECT COUNT(*) FROM deployment_unit WHERE current_release_sha256='"
                                        + "b".repeat(64) + "' AND display_name='Retained name'")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void ownershipConflictRollsBackReplacedTopologyAndNeverPublishesNewDraft() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var full = graph("application");
                seed(runtime, task, full);
                var repository = new DeploymentGraphRepository(runtime.modContexts().get(OWNER).context().db());
                var original = new DeploymentApplicationGraph("application", "worker", full.healthCheck(),
                        List.of(component("worker", "server", List.of())));
                var other = new DeploymentApplicationGraph("other", "web", full.healthCheck(),
                        List.of(component("web", "server", List.of())));
                repository.save(task, original, OBSERVED);
                repository.save(task, other, OBSERVED);
                assertThrows(SQLException.class, () -> repository.save(task, full, OBSERVED.plusSeconds(1)));
                assertEquals(original, repository.find(task, "application").orElseThrow());
                assertEquals(other, repository.find(task, "other").orElseThrow());
                var stolen = new DeploymentApplicationGraph("new-application", "web", full.healthCheck(),
                        full.components());
                assertThrows(SQLException.class, () -> repository.save(task, stolen, OBSERVED));
                assertTrue(repository.find(task, "new-application").isEmpty());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void absentUnitsStaleRuntimeAndChangedApplicationServerAreRejectedBeforePublication() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                var repository = new DeploymentGraphRepository(runtime.modContexts().get(OWNER).context().db());
                assertThrows(SQLException.class, () -> repository.save(task, graph, OBSERVED));
                seed(runtime, task, graph);
                sql(runtime, task, "UPDATE deployment_unit SET ownership_manifest_sha256='" + "c".repeat(64)
                        + "' WHERE deployment_unit_id='web'");
                assertThrows(SQLException.class, () -> repository.save(task, graph, OBSERVED));
                sql(runtime, task, "UPDATE deployment_unit SET ownership_manifest_sha256='" + "a".repeat(64)
                        + "' WHERE deployment_unit_id='web'");
                sql(runtime, task, "UPDATE deployment_unit SET runtime_payload=NULL WHERE deployment_unit_id='web'");
                assertThrows(SQLException.class, () -> repository.save(task, graph, OBSERVED));
                try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
                        var statement = connection.prepareStatement(
                                "UPDATE deployment_unit SET runtime_payload=? WHERE deployment_unit_id='web'")) {
                    statement.setBytes(1,
                            new DeploymentUnitRuntimeCodec().write(graph.components().getFirst().runtime()));
                    statement.executeUpdate();
                }
                sql(runtime, task,
                        "INSERT INTO managed_application(application_id,server_profile_id,updated_at) VALUES('application','different-server',1)");
                assertThrows(SQLException.class, () -> repository.save(task, graph, OBSERVED));
                assertTrue(repository.find(task, "application").isEmpty());
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cycle", "server", "missing-reviewed", "missing-health", "missing-paths",
            "resource-coverage", "unit-path"})
    void invalidCompleteGraphIsRejectedReadOnlyWhileDraftRemainsHidden(String corruption) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                seed(runtime, task, graph);
                var database = runtime.modContexts().get(OWNER).context().db();
                var repository = new DeploymentGraphRepository(database);
                repository.save(task, graph, OBSERVED);
                switch (corruption) {
                    case "cycle" -> sql(runtime, task,
                            "INSERT INTO managed_application_dependency VALUES('application','worker','web')");
                    case "server" ->
                        sql(runtime, task, "UPDATE managed_application SET server_profile_id='different-server'");
                    case "missing-reviewed" -> sql(runtime, task,
                            "UPDATE managed_application_component SET reviewed_runtime_payload=NULL WHERE component_id='web'");
                    case "missing-health" ->
                        sql(runtime, task, "UPDATE managed_application SET health_check_payload=NULL");
                    case "missing-paths" -> sql(runtime, task,
                            "UPDATE managed_application_component SET reviewed_data_paths_payload=NULL WHERE component_id='web'");
                    case "unit-path" -> sql(runtime, task,
                            "UPDATE deployment_unit SET release_root_path='/unowned' WHERE deployment_unit_id='web'");
                    case "resource-coverage" -> {
                        try (var connection = database.openConnection(task);
                                var statement = connection.prepareStatement(
                                        "UPDATE managed_application_component SET reviewed_data_paths_payload=? WHERE component_id='web'")) {
                            statement.setBytes(1,
                                    new DeploymentDataPathCodec().write(List.of(new DeploymentDataPath("uploads",
                                            DeploymentDataPath.AccessMode.READ_WRITE, "uploads", false))));
                            statement.executeUpdate();
                        }
                    }
                    default -> fail();
                }
                assertThrows(SQLException.class, () -> repository.find(task, "application"));
                try (var access = scope(runtime, true)) {
                    var report = new DeploymentSchema().validate(access);
                    assertFalse(report.valid());
                    assertEquals(List.of("Stored complete application graphs are invalid"), report.violations());
                    assertTrue(report.businessDigest().isEmpty());
                }
                sql(runtime, task, "UPDATE managed_application SET graph_ready=FALSE");
                assertTrue(repository.find(task, "application").isEmpty());
                return success();
            });
        }
    }

    ModDatabase commitFault(ModDatabase delegate) {
        return new ModDatabase() {
            public SchemaIdentity identity() throws ApiException {
                return delegate.identity();
            }

            public Connection openConnection(TaskContext task) throws ApiException, SQLException {
                var connection = delegate.openConnection(task);
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            try {
                                var result = method.invoke(connection, args);
                                if (method.getName().equals("commit"))
                                    throw new SQLException("Commit acknowledgement fixture");
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
            }
        };
    }

    @Test
    void lostCommitAcknowledgementPreservesOriginalCommittedGraphForReconciliation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var graph = graph("application");
                seed(runtime, task, graph);
                var database = runtime.modContexts().get(OWNER).context().db();
                var failure = assertThrows(ApiException.class,
                        () -> new DeploymentGraphRepository(commitFault(database)).save(task, graph, OBSERVED));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                assertEquals(graph, new DeploymentGraphRepository(database).find(task, "application").orElseThrow());
                return success();
            });
        }
    }

    @Test
    void foreignGenerationOwnerIsRejectedBeforeOpeningJdbc() throws Exception {
        var opened = new AtomicBoolean();
        var database = new ModDatabase() {
            public SchemaIdentity identity() {
                return new SchemaIdentity("example.foreign", "1", UUID.randomUUID());
            }

            public Connection openConnection(TaskContext task) {
                opened.set(true);
                throw new AssertionError("Foreign JDBC must not open");
            }
        };
        var repository = new DeploymentGraphRepository(database);
        assertThrows(IllegalArgumentException.class,
                () -> repository.save((TaskContext) null, graph("application"), OBSERVED));
        assertThrows(IllegalArgumentException.class, () -> repository.find(null, "application"));
        assertFalse(opened.get());
    }

    @Test
    void graphRejectsMissingForeignCyclicDuplicateOrOversizedComponents() {
        var full = graph("application");
        var health = full.healthCheck();
        var web = full.components().getFirst();
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentApplicationGraph("application", "web", health, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentApplicationGraph("application", "missing", health, full.components()));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentApplicationGraph("application", "web", health, List.of(web)));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentApplicationGraph("application", "web", health,
                List.of(web, component("worker", "server", List.of("web")))));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentApplicationGraph("application", "web", health,
                List.of(web, component("worker", "other", List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentApplicationGraph("application", "web", health, List.of(web, web)));
        var large = java.util.stream.IntStream.range(0, 65).mapToObj(i -> component("unit-" + i, "server", List.of()))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentApplicationGraph("application", "unit-0", health, large));
        assertEquals(64, new DeploymentApplicationGraph("application", "unit-0", health, large.subList(0, 64))
                .components().size());
        var copied = new ArrayList<>(full.components());
        var stable = new DeploymentApplicationGraph("application", "web", health, copied);
        copied.clear();
        assertEquals(full, stable);
    }

    @Test
    void persistedUnitKeepsExactPlatformReferenceWithoutFabricatingEndpointOrTrust() {
        var ref = new ServerRef("Exact-Platform-ID");
        var unit = DeploymentUnitIdentity.forManaged("unit", ref, "a".repeat(64));
        assertSame(ref, unit.server());
        assertEquals("Exact-Platform-ID", unit.server().id());
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentUnitIdentity.forManaged("unit", new ServerRef(" "), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentUnitIdentity.forManaged("unit", new ServerRef("s".repeat(513)), "a".repeat(64)));
    }

    @Test
    void readUsesOneSnapshotWhenReplacementCommitsBetweenHeaderAndComponentQueries() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var original = graph("application");
                seed(runtime, task, original);
                var replacement = new DeploymentApplicationGraph("application", "worker", original.healthCheck(),
                        List.of(component("worker", "server", List.of())));
                var database = runtime.modContexts().get(OWNER).context().db();
                var writer = new DeploymentGraphRepository(database);
                writer.save(task, original, OBSERVED);
                var replaced = new AtomicBoolean();
                var interleaved = new ModDatabase() {
                    public SchemaIdentity identity() throws ApiException {
                        return database.identity();
                    }

                    public Connection openConnection(TaskContext owner) throws ApiException, SQLException {
                        var connection = database.openConnection(owner);
                        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                    if (method.getName().equals("prepareStatement")
                                            && ((String) args[0])
                                                    .startsWith("SELECT component_id,dependency_component_id")
                                            && replaced.compareAndSet(false, true))
                                        writer.save(task, replacement, OBSERVED.plusSeconds(1));
                                    try {
                                        return method.invoke(connection, args);
                                    } catch (InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
                    }
                };
                assertEquals(original,
                        new DeploymentGraphRepository(interleaved).find(task, "application").orElseThrow());
                assertTrue(replaced.get());
                assertEquals(replacement, writer.find(task, "application").orElseThrow());
                return success();
            });
        }
    }
}

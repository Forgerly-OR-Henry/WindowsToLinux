package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentGraphStoreTest extends DeploymentGraphFixture {
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
                var repository = new DeploymentGraphStore(database);
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
                var repository = new DeploymentGraphStore(runtime.modContexts().get(OWNER).context().db());
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
                var repository = new DeploymentGraphStore(database);
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
                var repository = new DeploymentGraphStore(runtime.modContexts().get(OWNER).context().db());
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
                var repository = new DeploymentGraphStore(runtime.modContexts().get(OWNER).context().db());
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
                var repository = new DeploymentGraphStore(database);
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
                                new DeploymentStoragePathCodec().write(List.of(new DeploymentStoragePath("uploads",
                                    DeploymentStoragePath.AccessMode.READ_WRITE, "uploads", false))));
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
                    () -> new DeploymentGraphStore(commitFault(database)).save(task, graph, OBSERVED));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                assertEquals(graph, new DeploymentGraphStore(database).find(task, "application").orElseThrow());
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
        var repository = new DeploymentGraphStore(database);
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
        var ref = new ServerId("Exact-Platform-ID");
        var unit = DeploymentUnitIdentity.forManaged("unit", ref, "a".repeat(64));
        assertSame(ref, unit.server());
        assertEquals("Exact-Platform-ID", unit.server().id());
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentUnitIdentity.forManaged("unit", new ServerId(" "), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentUnitIdentity.forManaged("unit", new ServerId("s".repeat(513)), "a".repeat(64)));
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
                var writer = new DeploymentGraphStore(database);
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
                    new DeploymentGraphStore(interleaved).find(task, "application").orElseThrow());
                assertTrue(replaced.get());
                assertEquals(replacement, writer.find(task, "application").orElseThrow());
                return success();
            });
        }
    }
}

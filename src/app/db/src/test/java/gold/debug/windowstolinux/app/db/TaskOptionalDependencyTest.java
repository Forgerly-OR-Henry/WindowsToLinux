package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.db.ModActivationLedgerTest.sql;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskOptionalDependencyTest {
    @TempDir
    Path root;

    static final String OWNER = "example.consumer", PROVIDER = "example.provider", LEAF = "example.leaf";

    static TaskRecoveryGraph.Release release(String owner, String digest) {
        return new TaskRecoveryGraph.Release(owner, "1.0.0", digest.repeat(64), Optional.empty());
    }

    static TaskRecoveryGraph original() {
        return new TaskRecoveryGraph(OWNER, List.of(release(OWNER, "a")), List.of());
    }

    static TaskRecoveryGraph provider() {
        return new TaskRecoveryGraph(PROVIDER, List.of(release(PROVIDER, "b"), release(LEAF, "c")),
            List.of(new TaskRecoveryGraph.Edge(PROVIDER, LEAF, TaskRecoveryGraph.EdgeKind.REQUIRED)));
    }

    static TaskLedger.InitialClaims policy(TaskRecoveryGraph graph, List<TaskResource> claims) {
        return new TaskLedger.InitialClaims() {
            public List<TaskResource> resolve(Connection c) {
                return claims;
            }

            public Optional<TaskRecoveryGraph> recoveryGraph(Connection c) {
                return Optional.of(graph);
            }

            public Optional<TaskRecoveryGraph.EdgeKind> dependencyKind(Connection c) {
                return Optional.of(TaskRecoveryGraph.EdgeKind.OPTIONAL_USED);
            }
        };
    }

    static UUID start(TaskLedger ledger) throws Exception {
        var graph = original();
        UUID task = ledger.enqueue(UUID.randomUUID(), OWNER, "1.0.0", new TaskRequest("optional", "optional", List.of(), false),
            policy(graph, graph.claims()));
        assertTrue(ledger.tryStart(task));
        return task;
    }

    static void registry(PlatformDatabase db) throws Exception {
        for (var r : List.of(release(OWNER, "a"), release(PROVIDER, "b"), release(LEAF, "c"))) {
            sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", r.owner());
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,manifest_json,origin_kind,installed_at,state) VALUES(?,'1.0.0','fixture',?,'{}','CLASS',1,'READY')", r.owner(), r.digest());
        }
    }

    @Test
    void atomicOptionalClosureAndPinsSurviveReturnAndReopen() throws Exception {
        UUID task;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            registry(db);
            var ledger = new TaskLedger(db);
            task = start(ledger);
            var claims = List.of(new TaskResource("MOD", PROVIDER, PROVIDER, false));
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_optional CHECK(resource_id<>'example.leaf')");
            assertThrows(SQLException.class, () -> ledger.dependencies().admitDependency(task, OWNER, "1.0.0", PROVIDER, "1.0.0", Optional.empty(), false, policy(provider(), claims)));
            assertEquals(original(), ledger.queries().recoveryGraph(task).orElseThrow());
            assertFalse(ledger.queries().holds(task, claims.getFirst()));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_optional");
            var admitted = ledger.dependencies().admitDependency(task, OWNER, "1.0.0", PROVIDER, "1.0.0", Optional.empty(), false, policy(provider(), claims));
            assertEquals("service.v2", admitted.recovery().encode().protocol());
            assertEquals(OWNER, admitted.recovery().caller());
            assertEquals("b".repeat(64), admitted.recovery().digest());
            ledger.dependencies().releaseDependency(task, admitted.recovery(), admitted.leases());
            assertTrue(ledger.queries().holds(task, claims.getFirst()));
            assertTrue(ledger.queries().holds(task, new TaskResource("MOD", LEAF, LEAF, false)));
            assertTrue(ledger.queries().dependencies(task).isEmpty());
            assertEquals(3, new com.fasterxml.jackson.databind.ObjectMapper().readTree(TaskRecoveryGraphTest.metadata(db, task)).path("formatVersion").intValue());
            ledger.uncertain(task);
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var graph = ledger.queries().recoveryGraph(task).orElseThrow();
            assertTrue(graph.edges().contains(new TaskRecoveryGraph.Edge(OWNER, PROVIDER, TaskRecoveryGraph.EdgeKind.OPTIONAL_USED)));
            assertTrue(ledger.queries().holds(task, new TaskResource("MOD", PROVIDER, PROVIDER, false)));
            assertThrows(SQLException.class, () -> ledger.dependencies().admitDependency(task, OWNER, "1.0.0", PROVIDER, "2.0.0", Optional.empty(), true,
                policy(new TaskRecoveryGraph(PROVIDER, List.of(new TaskRecoveryGraph.Release(PROVIDER, "2.0.0", "d".repeat(64), Optional.empty())), List.of()), List.of(new TaskResource("MOD", PROVIDER, PROVIDER, false)))));
            assertEquals(graph, ledger.queries().recoveryGraph(task).orElseThrow());
            assertTrue(ledger.complete(task, TaskResult.FAILED, "{}", true));
        }
    }

    @Test
    void recoveryCannotIntroduceUnusedOptionalAndProviderCheckpointNamespaceDoesNotCross() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            registry(db);
            var ledger = new TaskLedger(db);
            var task = start(ledger);
            var claims = List.of(new TaskResource("MOD", PROVIDER, PROVIDER, false));
            assertThrows(SQLException.class, () -> ledger.dependencies().admitDependency(task, OWNER, "1.0.0", PROVIDER, "1.0.0", Optional.empty(), true, policy(provider(), claims)));
            var admitted = ledger.dependencies().admitDependency(task, OWNER, "1.0.0", PROVIDER, "1.0.0", Optional.empty(), false, policy(provider(), claims));
            var checkpoints = new TaskAuthorCheckpoint(db);
            var rootFact = checkpoints.begin(task, OWNER, "1.0.0", "same", "{\"root\":true}");
            var providerFact = checkpoints.begin(task, PROVIDER, "1.0.0", "same", "{\"provider\":true}");
            assertNotEquals(rootFact.lease(), providerFact.lease());
            assertEquals(providerFact, checkpoints.find(task, PROVIDER, "1.0.0", "same").orElseThrow());
            assertThrows(SQLException.class, () -> checkpoints.find(task, PROVIDER, "2.0.0", "same"));
            assertThrows(SQLException.class, () -> checkpoints.begin(task, "example.unrelated", "1.0.0", "same", "{}"));
            ledger.dependencies().releaseDependency(task, admitted.recovery(), admitted.leases());
            assertFalse(ledger.complete(task, TaskResult.SUCCEEDED, "{}", false));
        }
    }
}

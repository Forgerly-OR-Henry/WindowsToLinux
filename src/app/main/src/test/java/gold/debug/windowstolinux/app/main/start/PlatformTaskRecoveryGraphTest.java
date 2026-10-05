package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskRecoveryGraphTest {
    @TempDir
    Path root;

    static final String PROVIDER = "example.provider";

    static final String LEAF = "example.leaf";

    @Test
    void absentOptionalProviderAllowsRealLocalTaskCompletionWithRootOnlyGraph() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var db = runtime.persistence().database();
            String manifest = PlatformModIntentsTest.manifest(OWNER, VERSION, Map.of(PROVIDER, "[1.0.0,2.0.0)"), null, List.of())
                .replace("\"versionRange\":\"[1.0.0,2.0.0)\"", "\"versionRange\":\"[1.0.0,2.0.0)\",\"optional\":true");
            sql(db, "UPDATE mod_version SET manifest_json=? WHERE mod_id=? AND version=?", manifest, OWNER, VERSION);
            var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), context -> {
                var graph = new TaskLedger(db).queries().recoveryGraph(context.id()).orElseThrow();
                assertEquals(Set.of(OWNER), graph.releases().stream().map(TaskRecoveryGraph.Release::owner).collect(java.util.stream.Collectors.toSet()));
                assertTrue(graph.edges().isEmpty());
                return success("local-completed");
            });
            var result = task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals("local-completed", result.value().orElseThrow());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
        }
    }

    static SchemaIdentity prepare(PlatformRuntime runtime) throws Exception {
        initialize(runtime, false);
        var db = runtime.persistence().database();
        var schema = PlatformModServicesTest.provider(db);
        sql(db, "UPDATE mod_version SET manifest_json=? WHERE mod_id=? AND version=?",
            PlatformModIntentsTest.manifest(OWNER, VERSION, Map.of(PROVIDER, "[1.0.0,2.0.0)"), null, List.of()),
            OWNER, VERSION);
        sql(db, "UPDATE mod_version SET manifest_json=? WHERE mod_id=? AND version=?",
            PlatformModIntentsTest.manifest(PROVIDER, VERSION, Map.of(LEAF, "[3.0.0,4.0.0)"), "1", List.of()),
            PROVIDER, VERSION);
        sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", LEAF);
        sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,manifest_json,origin_kind,installed_at,state) VALUES(?,'3.0.0','fixture',?,?,'CLASS',1,'READY')",
            LEAF, "3".repeat(64), PlatformModIntentsTest.manifest(LEAF, "3.0.0", Map.of(), null, List.of()));
        sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version='3.0.0',selection_initialized=TRUE,actual_version='3.0.0',last_runtime_state='RUNNING',last_boot_id=? WHERE mod_id=?",
            db.bootId(), LEAF);
        return schema;
    }

    @Test
    void realAdmissionCapturesTransitiveCodeAndDataBeforeCallbacksAndNeverRewritesQueuedFacts() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = prepare(runtime);
            var db = runtime.persistence().database();
            var ledger = new TaskLedger(db);
            var release = new CountDownLatch(1);
            var observed = new AtomicReference<TaskRecoveryGraph>();
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var first = tasks.submit(request("server"), context -> {
                assertTrue(release.await(15, TimeUnit.SECONDS));
                return success(null);
            });
            TaskHandle<TaskRecoveryGraph> queued = null;
            try {
                state(first, TaskState.RUNNING);
                var graph = ledger.queries().recoveryGraph(first.id()).orElseThrow();
                assertEquals(OWNER, graph.owner());
                assertEquals(Set.of(OWNER, PROVIDER, LEAF), graph.releases().stream()
                    .map(TaskRecoveryGraph.Release::owner).collect(java.util.stream.Collectors.toSet()));
                assertEquals(Optional.of(schema), graph.release(PROVIDER).schema());
                assertEquals("3.0.0", graph.release(LEAF).version());
                assertEquals("3".repeat(64), graph.release(LEAF).digest());
                assertTrue(graph.release(OWNER).schema().isEmpty());
                queued = tasks.submit(request("server"), context -> {
                    var original = ledger.queries().recoveryGraph(context.id()).orElseThrow();
                    observed.set(original);
                    return success(original);
                });
                assertEquals(TaskState.QUEUED, queued.snapshot().state());
                assertNull(observed.get());
                assertEquals(Optional.of(graph), ledger.queries().recoveryGraph(queued.id()));
                // Deliberate storage drift proves retained observations are not rebuilt from mutable current choices. / 刻意存储漂移证明保留观察不从可变当前选择重建。
                sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,'9.0.0','fixture-other',?,'1',?,'CLASS',1,'READY')",
                    PROVIDER, "9".repeat(64),
                    PlatformModIntentsTest.manifest(PROVIDER, "9.0.0", Map.of(), "1", List.of()));
                sql(db, "UPDATE mod_registry SET selected_version='9.0.0' WHERE mod_id=?", PROVIDER);
                sql(db, "UPDATE mod_schema SET generation=? WHERE mod_id=?", UUID.randomUUID(), PROVIDER);
                sql(db, "UPDATE mod_version SET content_sha256=? WHERE mod_id=?", "f".repeat(64), LEAF);
                assertEquals(Optional.of(graph), ledger.queries().recoveryGraph(first.id()));
                assertEquals(Optional.of(graph), ledger.queries().recoveryGraph(queued.id()));
                assertThrows(ApiException.class, () -> tasks.submit(request(), context -> {
                    throw new AssertionError();
                }));
                assertEquals(2, count(db, "SELECT COUNT(*) FROM platform_task"));
                assertEquals(2,
                    count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE owner_id='example.provider'"));
            } finally {
                sql(db, "UPDATE mod_registry SET selected_version=? WHERE mod_id=?", VERSION, PROVIDER);
                sql(db, "UPDATE mod_schema SET generation=? WHERE mod_id=?", schema.generation(), PROVIDER);
                sql(db, "UPDATE mod_version SET content_sha256=? WHERE mod_id=?", "3".repeat(64), LEAF);
                release.countDown();
                assertEquals(TaskResult.SUCCEEDED,
                    first.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                if (queued != null) {
                    var result = queued.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
                    assertEquals(TaskResult.SUCCEEDED, result.outcome());
                    assertEquals(observed.get(), result.value().orElseThrow());
                }
            }
        }
    }

    @Test
    void unavailableProviderDataOrDigestRejectsAdmissionWithoutAnyBusinessCallback() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            prepare(runtime);
            var db = runtime.persistence().database();
            var called = new AtomicBoolean();
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            sql(db, "UPDATE mod_schema SET state='FAILED' WHERE mod_id=?", PROVIDER);
            assertEquals("SCHEMA_NOT_READY", assertThrows(ApiException.class, () -> tasks.submit(request(), context -> {
                called.set(true);
                return success(null);
            })).problem().eventCode());
            sql(db, "UPDATE mod_schema SET state='READY' WHERE mod_id=?", PROVIDER);
            sql(db, "UPDATE mod_version SET content_sha256='unverified' WHERE mod_id=?", LEAF);
            assertEquals("MOD_RUNNING_GRAPH_UNVERIFIED",
                assertThrows(ApiException.class, () -> tasks.submit(request(), context -> {
                    called.set(true);
                    return success(null);
                })).problem().eventCode());
            assertFalse(called.get());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource"));
        }
    }

    @Test
    void actualHostTaskKeepsItsProtocolWithoutInventingModRegistrationOrGraph() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            runtime.started();
            var task = runtime.tasks().bind("@platform", runtime.version(), false).submit(request(), context -> {
                assertTrue(new TaskLedger(runtime.persistence().database()).queries().recoveryGraph(context.id()).isEmpty());
                return success(null);
            });
            assertEquals(TaskResult.SUCCEEDED, task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
        }
    }
}

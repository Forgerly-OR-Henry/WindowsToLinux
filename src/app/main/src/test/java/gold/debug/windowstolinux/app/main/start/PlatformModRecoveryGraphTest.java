package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModFlowTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.mod.ModRecoveryLedger;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentInfo;
import gold.debug.windowstolinux.app.service.task.PlatformTaskManagement;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModRecoveryGraphTest {
    @TempDir
    Path root;

    PlatformModFlowTest fixture;

    final Map<UUID, TaskRecoveryGraph> originals = new LinkedHashMap<>();

    boolean disableAfterCapture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModFlowTest();
        fixture.root = root;
        fixture.workspace();
        module(P, Map.of());
        module(C, Map.of(P, VERSION));
        module(G, Map.of(P, VERSION));
        module(F, Map.of());
        fixture.enable(P, C, G, F);
    }

    void module(String owner, Map<String, String> dependencies) throws Exception {
        Path module = fixture.workspace.resolve("src/mod/" + owner);
        Path classes = Files.createDirectories(module.resolve("target/classes"));
        Path source = module.resolve("Mod.java");
        Files.writeString(source,
            """
                package %s;
                import java.nio.file.*;
                import java.util.*;
                import gold.debug.windowstolinux.appapi.mod.ModContext; import gold.debug.windowstolinux.appapi.mod.ModEntrypoint; import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode; import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
                import gold.debug.windowstolinux.appapi.task.result.ApiException; import gold.debug.windowstolinux.appapi.task.result.OperationResult; import gold.debug.windowstolinux.appapi.task.result.Problem; import gold.debug.windowstolinux.appapi.task.result.ProblemCode; import gold.debug.windowstolinux.appapi.task.model.TaskRequest; import gold.debug.windowstolinux.appapi.task.result.TaskResult;
                
                import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
                public final class Mod implements ModEntrypoint {
                    private static final Path ROOT=Path.of("%s");
                    private static void record(String action) throws Exception {
                        Files.writeString(ROOT.resolve("trace.txt"),"%s:"+action+"\\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    }
                    public Mod() throws Exception {record("construct");}
                    public void start(ModContext context) throws Exception {
                        record("start:"+context.launchMode());
                        context.tasks().registerRecovery("fixture",(snapshot,task)->{
                            record("recover:"+snapshot.id());
                            if(Files.exists(ROOT.resolve("%s.recover.unknown")))
                                return new OperationResult<Void>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"FIXTURE_UNKNOWN","Fixture unknown",Map.of(),Optional.empty())));
                            return new OperationResult<Void>(TaskResult.SUCCEEDED,Optional.empty(),Optional.empty());
                        });
                        context.ui().register(new PageSpec("main","main","Fixture",false),()->{throw new AssertionError("Recovery page mounted");});
                        if(context.launchMode()==ModLaunchMode.RECOVERY) {
                            try {context.tasks().submit(new TaskRequest("forbidden","Forbidden",List.of(),false),task->{throw new AssertionError();});throw new AssertionError("Recovery admitted new business");}
                            catch(ApiException expected){record("submit-denied");}
                        }
                        if(Files.exists(ROOT.resolve("%s.start.fail")))throw new IllegalStateException("Fixture start failure");
                    }
                    public void stop(StopContext context) throws Exception {
                        record("stop:"+context.reason());
                        if(Files.exists(ROOT.resolve("%s.stop.fail")))throw new IllegalStateException("Fixture stop failure");
                    }
                }
                """
                .formatted(owner, fixture.workspace.toString().replace("\\", "\\\\"), owner, owner, owner,
                    owner));
        String cp = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
            "-encoding", "UTF-8", "-classpath", cp, "-d", classes.toString(), source.toString()));
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("mod.json"),
            PlatformModIntentsTest.manifest(owner, VERSION, dependencies, null, List.of()));
        DevelopmentInfo.generate(fixture.workspace, module.resolve("mod.json"), classes, List.of(),
            List.of(fixture.workspace.resolve("pom.xml"), fixture.workspace.resolve("src/mod/pom.xml"),
                module.resolve("pom.xml")),
            List.of());
    }

    // Capture graphs through real admission, then reproduce their retained H2 state for a later engine boot.
    void retained(String... owners) throws Exception {
        UUID boot;
        try (var runtime = fixture.open(true)) {
            assertEquals(Set.of(P, C, G, F), fixture.start(runtime).running());
            boot = runtime.persistence().database().bootId();
            var ledger = new TaskLedger(runtime.persistence().database());
            for (String owner : owners) {
                var task = runtime.tasks().bind(owner, VERSION, false).submit(
                    new TaskRequest("fixture", "Original task", List.of(), false),
                    t -> PlatformTaskManagementWindowTest.unknown());
                state(task, TaskState.UNKNOWN);
                originals.put(task.id(), ledger.queries().recoveryGraph(task.id()).orElseThrow());
                if (disableAfterCapture && owner.equals(C)) {
                    var intents = runtime.modIntents();
                    intents.apply(intents.review(C, PlatformModIntentsTest.choice(false, VERSION), Optional.empty()),
                        false);
                }
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(task.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
        }
        try (var db = PlatformDatabase.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var ledger = new TaskLedger(db);
            for (var entry : originals.entrySet()) {
                seed(ledger, entry.getKey(), entry.getValue());
                for (var release : entry.getValue().releases())
                    sql(db, "UPDATE mod_registry SET last_boot_id=?,last_runtime_state='UNKNOWN',actual_version=? WHERE mod_id=?",
                        boot, release.version(), release.owner());
            }
        }
        Files.writeString(fixture.trace, "");
    }

    static void seed(TaskLedger ledger, UUID id, TaskRecoveryGraph graph) throws Exception {
        ledger.enqueue(id, graph.owner(), graph.release(graph.owner()).version(),
            new TaskRequest("fixture", "Original task", List.of(), false), new TaskLedger.InitialClaims() {
                public List<TaskResource> resolve(Connection c) {
                    return List.of(new TaskResource("MOD", graph.owner(), graph.owner(), false));
                }

                public Optional<TaskRecoveryGraph> recoveryGraph(Connection c) {
                    return Optional.of(graph);
                }
            });
        assertTrue(ledger.tryStart(id));
        assertFalse(ledger.complete(id, TaskResult.UNKNOWN, "fixture", false));
    }

    PlatformTaskManagement management(PlatformRuntime runtime) {
        return new PlatformTaskManagement(runtime.tasks(), runtime.security(), id -> {
            throw new AssertionError("Not a rescue task");
        }, runtime.modRecovery()::release);
    }

    TaskSnapshot reconcile(PlatformRuntime runtime, UUID task) throws Exception {
        return management(runtime).reconcile(task, false).toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    void cleanup(PlatformRuntime runtime) throws Exception {
        for (String owner : List.of(P, C, G, F)) {
            Files.deleteIfExists(fixture.workspace.resolve(owner + ".stop.fail"));
            Files.deleteIfExists(fixture.workspace.resolve(owner + ".recover.unknown"));
        }
        var ledger = new TaskLedger(runtime.persistence().database());
        for (var id : ledger.queries().unfinished()) {
            var snapshot = ledger.queries().snapshot(id);
            try {
                runtime.tasks().reconcile(id).toCompletableFuture().get(8, TimeUnit.SECONDS);
            } catch (ApiException absent) {
                try (var registration = runtime.tasks().bind(snapshot.ownerId(), snapshot.ownerVersion(), true)
                    .registerRecovery(snapshot.type(), (before, t) -> success(null))) {
                    runtime.tasks().reconcile(id).toCompletableFuture().get(8, TimeUnit.SECONDS);
                }
            }
        }
        runtime.modRecovery().release();
        runtime.close();
    }

    @Test
    void defaultClassStartupUsesOldGraphWhileIndependentModsRunAndExplicitRecoveryReleasesIt() throws Exception {
        retained(C);
        try (var db = PlatformDatabase.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            sql(db, "INSERT INTO mod_version SELECT mod_id,'2.0.0',install_path,content_sha256,archive_sha256,schema_version,manifest_json,origin_kind,installed_at,state FROM mod_version WHERE mod_id=?",
                C);
            sql(db, "UPDATE mod_registry SET enabled=FALSE,selected_version='2.0.0',revision=revision+1 WHERE mod_id=?",
                C);
        }
        var runtime = fixture.open(true);
        try {
            var pages = fixture.pages(false);
            var report = ApplicationLauncher
                .startMods(
                    runtime, new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS,
                        fixture.workspace, runtime.persistence().database().root()),
                    fixture.workspace.toString(), pages);
            assertEquals(Set.of(F), report.running()); // G requires P, which is isolated in recovery.
            assertEquals(Set.of(P, C, F), runtime.modContexts().keySet());
            assertEquals(List.of(F), pages.contributions().stream().map(value -> value.key().ownerId()).toList());
            assertTrue(fixture.actions().indexOf(P + ":start:RECOVERY") < fixture.actions()
                .indexOf(C + ":start:RECOVERY"));
            assertTrue(fixture.actions().contains(C + ":submit-denied"));
            assertFalse(fixture.actions().stream().anyMatch(value -> value.contains(":recover:")));
            assertEquals("UNKNOWN", PlatformModFlowTest.state(runtime, C));
            assertEquals("2.0.0", PlatformModIntentsTest.text(runtime,
                "SELECT selected_version FROM mod_registry WHERE mod_id='" + C + "'"));
            assertEquals(2, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_operation WHERE phase='RECOVERING'"));
            assertEquals(TaskState.ENDED, reconcile(runtime, originals.keySet().iterator().next()).state());
            assertEquals(Set.of(F), runtime.modContexts().keySet());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(List.of(C + ":stop:RECOVERY_FINISHED", P + ":stop:RECOVERY_FINISHED"),
                fixture.actions().stream().filter(value -> value.endsWith(":stop:RECOVERY_FINISHED")).toList());
            assertEquals("STOPPED", PlatformModFlowTest.state(runtime, C));
            assertFalse(fixture.actions().contains(C + ":start:NORMAL"));
        } finally {
            cleanup(runtime);
        }
    }

    @Test
    void sharedProviderStaysUntilEveryOriginalTaskInItsConnectedGroupEnds() throws Exception {
        retained(C, G);
        var runtime = fixture.open(true);
        try {
            assertEquals(Set.of(F), fixture.start(runtime).running());
            assertEquals(3, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            var ids = List.copyOf(originals.keySet());
            reconcile(runtime, ids.getFirst());
            assertEquals(Set.of(P, C, G, F), runtime.modContexts().keySet());
            assertFalse(fixture.actions().stream().anyMatch(value -> value.contains(":stop:")));
            reconcile(runtime, ids.getLast());
            assertEquals(Set.of(F), runtime.modContexts().keySet());
            assertEquals(1, fixture.actions().stream().filter(value -> value.equals(P + ":construct")).count());
            assertEquals(2, fixture.actions().stream().filter(value -> value.contains(":recover:")).count());
        } finally {
            cleanup(runtime);
        }
    }

    @Test
    void changedProgramBlocksWholeOriginalGroupWithoutConstructingConsumers() throws Exception {
        retained(C);
        Files.write(fixture.workspace.resolve("src/mod/" + P + "/target/classes/" + P.replace('.', '/') + "/Mod.class"),
            new byte[]{1}, StandardOpenOption.APPEND);
        var runtime = fixture.open(true);
        try {
            assertEquals(Set.of(F), fixture.start(runtime).running());
            assertEquals(Set.of(F), runtime.modContexts().keySet());
            assertFalse(fixture.actions().contains(C + ":construct"));
            assertFalse(fixture.actions().contains(P + ":construct"));
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("UNKNOWN", PlatformModFlowTest.state(runtime, C));
        } finally {
            cleanup(runtime);
        }
    }

    @Test
    void durableSessionGraphSurvivesAfterOriginalTaskWasArchivedBeforeApplicationExit() throws Exception {
        retained(C);
        try (var db = PlatformDatabase.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var graph = originals.values().iterator().next();
            new ModRecoveryLedger(db).retain(graph);
            new TaskLedger(db).completeAndArchive(originals.keySet().iterator().next(), TaskResult.SUCCEEDED,
                "Explicit fixture recovery", true, () -> {
                });
        }
        try (var runtime = fixture.open(true)) {
            assertEquals(Set.of(F), fixture.start(runtime).running());
            assertEquals(Set.of(F), runtime.modContexts().keySet());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertTrue(fixture.actions().contains(C + ":start:RECOVERY"));
            assertTrue(fixture.actions().contains(P + ":stop:RECOVERY_FINISHED"));
            assertFalse(fixture.actions().stream().anyMatch(value -> value.contains(":recover:")));
            assertFalse(fixture.actions().contains(C + ":start:NORMAL"));
        }
        Files.writeString(fixture.trace, "");
        try (var runtime = fixture.open(true)) {
            assertEquals(Set.of(P, C, G, F), fixture.start(runtime).running());
            assertTrue(fixture.actions().contains(C + ":start:NORMAL"));
        }
    }

    @Test
    void stopFailureRetainsOriginalProviderAndExitRetriesOnlyOriginalObjects() throws Exception {
        retained(C);
        var runtime = fixture.open(true);
        try {
            fixture.start(runtime);
            Files.writeString(fixture.workspace.resolve(C + ".stop.fail"), "fixture");
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> reconcile(runtime, originals.keySet().iterator().next()));
            assertEquals("MOD_RECOVERY_CLEANUP_PENDING", ((ApiException) failure.getCause()).problem().eventCode());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
            assertEquals(2, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_operation WHERE phase='RECOVERING'"));
            assertFalse(fixture.actions().contains(P + ":stop:RECOVERY_FINISHED"));
            Files.delete(fixture.workspace.resolve(C + ".stop.fail"));
            runtime.close();
            assertEquals(1, fixture.actions().stream().filter(value -> value.equals(C + ":construct")).count());
            assertEquals(1, fixture.actions().stream().filter(value -> value.startsWith(C + ":recover:")).count());
            assertTrue(fixture.actions().contains(P + ":stop:RECOVERY_FINISHED"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void archiveFailureRetainsVerifiedReceiptWithoutReplayingTaskOrModStop() throws Exception {
        retained(C);
        var runtime = fixture.open(true);
        try {
            fixture.start(runtime);
            sql(runtime.persistence().database(),
                "ALTER TABLE runtime_log ADD CONSTRAINT fixture_recovery_archive CHECK(event_code<>'MOD_RECOVERY_FINISHED')");
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> reconcile(runtime, originals.keySet().iterator().next()));
            assertEquals(Set.of(F), runtime.modContexts().keySet());
            assertEquals(2, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_operation WHERE phase='VERIFIED'"));
            assertEquals("UNKNOWN", PlatformModFlowTest.state(runtime, C));
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT fixture_recovery_archive");
            runtime.modRecovery().release();
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(1,
                fixture.actions().stream().filter(value -> value.equals(C + ":stop:RECOVERY_FINISHED")).count());
            assertEquals(1, fixture.actions().stream().filter(value -> value.startsWith(C + ":recover:")).count());
        } finally {
            cleanup(runtime);
        }
    }

    @Test
    void conflictingOriginalProviderVersionsBlockBothTaskGraphs() throws Exception {
        retained(C, G);
        try (var db = PlatformDatabase.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var id = List.copyOf(originals.keySet()).getLast();
            var graph = originals.get(id);
            var changed = new TaskRecoveryGraph(graph.owner(),
                graph.releases().stream()
                    .map(release -> release.owner().equals(P)
                        ? new TaskRecoveryGraph.Release(P, "2.0.0", release.digest(), release.schema())
                        : release)
                    .toList());
            String document;
            try (var c = db.openConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery("SELECT recovery_json FROM platform_task WHERE task_id='" + id + "'")) {
                assertTrue(r.next());
                document = r.getString(1);
            }
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(document);
            value.set("graph", json.valueToTree(changed.encode()));
            sql(db, "UPDATE platform_task SET recovery_json=? WHERE task_id=?", value.toString(), id);
        }
        var runtime = fixture.open(true);
        try {
            var report = fixture.start(runtime);
            assertEquals(Set.of(F), report.running());
            assertEquals(3, report.problems().stream()
                .filter(problem -> problem.eventCode().equals("MOD_RECOVERY_GRAPH_CONFLICT")).count());
            assertEquals(Set.of(F), runtime.modContexts().keySet());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        } finally {
            cleanup(runtime);
        }
    }

    @Test
    void unknownRecoveryResultKeepsOriginalSessionsUntilExplicitSuccessfulRetry() throws Exception {
        retained(C);
        var runtime = fixture.open(true);
        try {
            fixture.start(runtime);
            Files.writeString(fixture.workspace.resolve(C + ".recover.unknown"), "fixture");
            UUID id = originals.keySet().iterator().next();
            assertEquals(TaskState.UNKNOWN, reconcile(runtime, id).state());
            assertEquals(Set.of(P, C, F), runtime.modContexts().keySet());
            assertFalse(fixture.actions().stream().anyMatch(value -> value.contains(":stop:")));
            Files.delete(fixture.workspace.resolve(C + ".recover.unknown"));
            assertEquals(TaskState.ENDED, reconcile(runtime, id).state());
            assertEquals(Set.of(F), runtime.modContexts().keySet());
        } finally {
            cleanup(runtime);
        }
    }

    @Test
    void unappliedDisableIntentWaitsForOriginalRecoveryAndThenForAnotherRestart() throws Exception {
        disableAfterCapture = true;
        retained(C);
        var runtime = fixture.open(true);
        try {
            assertEquals(Set.of(F), fixture.start(runtime).running());
            assertTrue(fixture.actions().contains(C + ":start:RECOVERY"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM mod_operation WHERE operation_type='DISABLE' AND phase='WAIT_RESTART'"));
            reconcile(runtime, originals.keySet().iterator().next());
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("FALSE",
                PlatformModIntentsTest.text(runtime, "SELECT enabled FROM mod_registry WHERE mod_id='" + C + "'"));
        } finally {
            cleanup(runtime);
        }
        Files.writeString(fixture.trace, "");
        try (var next = fixture.open(true)) {
            assertEquals(Set.of(P, G, F), fixture.start(next).running());
            assertEquals(0, count(next.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertFalse(fixture.actions().contains(C + ":construct"));
        }
    }
}

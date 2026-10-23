package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationRunner;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentManagementActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitRuntimeConfig;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real CLASS/H2 tasks and production management channels against a controlled peer. / 真实CLASS/H2任务及生产管理频道连接受控对等端。 */
class DeploymentApplicationLifecycleTest extends DeploymentManagedApplicationFixture {
    enum ScenarioType {
        CURRENT_DRIFT, DISPATCH_DRIFT, DEGRADED_STOP, STOP_ACK_LOST, STOP_UNCONFIRMED, STOP_UNTOUCHED_DRIFT,
        RESTART_ACK_LOST, RESTART_PARTIAL_ACK_LOST, RESTART_VERIFIED_PARTIAL, FINAL_HEALTH_FAILURE, FINAL_CURRENT_DRIFT
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                return true;
            }

            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server,
                                                           TaskSnapshot task, CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task,
                                                       CancelToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }
        };
    }

    @ParameterizedTest
    @EnumSource(value = ScenarioType.class, names = {"CURRENT_DRIFT", "RESTART_VERIFIED_PARTIAL", "RESTART_PARTIAL_ACK_LOST"}, mode = EnumSource.Mode.EXCLUDE)
    void originalCurrentAndAcknowledgementsGateEveryLifecycle(ScenarioType scenario) throws Exception {
        if (scenario == ScenarioType.DISPATCH_DRIFT || scenario == ScenarioType.RESTART_ACK_LOST
            || scenario == ScenarioType.FINAL_CURRENT_DRIFT || scenario == ScenarioType.STOP_UNCONFIRMED
            || scenario == ScenarioType.STOP_UNTOUCHED_DRIFT)
            runIsolated(scenario.name());
        else
            runScenario(scenario, false);
    }

    @org.junit.jupiter.api.Test
    void partialRestartRetainsMissingStartAcknowledgementWhileSettlingCurrentFailure() throws Exception {
        runScenario(ScenarioType.RESTART_PARTIAL_ACK_LOST, false);
    }

    @org.junit.jupiter.api.Test
    void verifiedPartialRestartSettlesFailedWithoutReplayingAnyEffect() throws Exception {
        runScenario(ScenarioType.RESTART_VERIFIED_PARTIAL, false);
    }

    @org.junit.jupiter.api.Test
    void originalCurrentDriftPrecedesEveryLifecycleEffect() throws Exception {
        runScenario(ScenarioType.CURRENT_DRIFT, false);
    }

    @org.junit.jupiter.api.Test
    void agentManagementUsesActualTaskAppAndFreshGrantForEveryRemoteAction() throws Exception {
        isolatedManagedRecovery(root, "APP_REVIEW");
    }

    private void managedAppReview() throws Exception {
        enable();
        var runtime = open();
        start(runtime, true); server(runtime);
        run(runtime, task -> { publishManaged(runtime, task, reviewedGraph(), 1); return success(); });
        var harness = new ManagedHarness(runtime.modContexts().get(OWNER).context());
        var app = new DeploymentApplicationId("application", new ServerId("server"));
        for (var action : List.of(DeploymentLifecycleAction.RESTART, DeploymentLifecycleAction.STOP,
            DeploymentLifecycleAction.START, DeploymentLifecycleAction.DISABLE_AUTOSTART)) {
            var snapshot = harness.finished(harness.service.inspect(app));
            harness.finished(harness.service.lifecycle(snapshot, action));
        }
        assertEquals(harness.reviews.get(), harness.hostGrants.get());
        assertEquals(harness.hostGrants.get(), harness.peer.sends.get());
        assertTrue(harness.reviews.get() > harness.peer.effects.get());
        assertEquals(0, harness.openWorkflows.get()); assertEquals(0, harness.peer.sessions.get());
        int sends = harness.peer.sends.get();
        int grantsBeforeDenied = harness.hostGrants.get();
        harness.decision = "DENY";
        var denied = harness.service.inspect(app);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (denied.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline) Thread.sleep(10);
        var refusal = assertInstanceOf(ApiException.class, harness.failure.get());
        assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", refusal.problem().eventCode());
        assertEquals(TaskState.UNKNOWN, denied.snapshot().state());
        assertEquals(sends, harness.peer.sends.get(), "DENY cannot send the command");
        assertEquals(grantsBeforeDenied, harness.hostGrants.get(), "DENY cannot obtain a grant");
        assertThrows(ApiException.class, () -> harness.service.scan(new ServerId("server")));
        assertEquals(0, harness.openWorkflows.get()); assertEquals(0, harness.peer.sessions.get());
        assertThrows(ApiException.class, runtime::close, "Retained UNKNOWN is not pretended settled");
        System.out.println("MANAGEMENT_V2_READ_ONLY_VERIFIED APP_REVIEW");
    }

    @org.junit.jupiter.api.Test
    void singleComponentStopPreservesOtherRawStateAndStartRestoresHealth() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true); server(runtime);
            run(runtime, task -> { publishManaged(runtime, task, reviewedGraph(), 1); return success(); });
            var harness = new ManagedHarness(runtime.modContexts().get(OWNER).context());
            var app = new DeploymentApplicationId("application", new ServerId("server"));
            var stop = harness.service.lifecycleComponent(harness.finished(harness.service.inspect(app)), "worker", DeploymentLifecycleAction.STOP);
            var stopped = harness.finished(stop);
            assertEquals(1, harness.peer.effects.get()); assertTrue(harness.peer.running.get("web"));
            assertEquals(gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState.DEGRADED, stopped.after());
            var snapshot = harness.finished(harness.service.inspect(app));
            var start = harness.service.lifecycleComponent(snapshot, "worker", DeploymentLifecycleAction.START);
            assertEquals(gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState.RUNNING, harness.finished(start).after());
            assertNotEquals(stop.id(), start.id()); assertEquals(2, harness.peer.effects.get());
            assertTrue(harness.peer.enabled.values().stream().allMatch(Boolean::booleanValue));
            int effects = harness.peer.effects.get();
            assertEquals(TaskResult.FAILED, harness.service.lifecycleComponent(harness.finished(harness.service.inspect(app)),
                "missing", DeploymentLifecycleAction.STOP).completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertThrows(IllegalArgumentException.class, () -> harness.service.lifecycleComponent(snapshot, "worker", DeploymentLifecycleAction.RESTART));
            assertEquals(effects, harness.peer.effects.get()); harness.assertNoFailure();
        }
    }

    @org.junit.jupiter.api.Test
    void componentIntentAndVersionTwoFactsFailClosed() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true); server(runtime);
            run(runtime, task -> { publishManaged(runtime, task, reviewedGraph(), 1); return success(); });
            var harness = new ManagedHarness(runtime.modContexts().get(OWNER).context());
            var app = new DeploymentApplicationId("application", new ServerId("server"));
            var snapshot = harness.finished(harness.service.inspect(app));
            harness.after = (task, result) -> {
                var log = new DeploymentManagementActionLog(harness.context);
                var original = log.find(task).orElseThrow();
                assertEquals(2, original.facts().path("formatVersion").intValue());
                var changes = new ArrayList<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>();
                changes.add(facts -> facts.put("formatVersion", 3));
                changes.add(facts -> facts.put("formatVersion", "2"));
                changes.add(facts -> facts.put("unrecognized", true));
                changes.add(facts -> facts.remove("target"));
                changes.add(facts -> facts.put("selectedComponentId", "web"));
                changes.add(facts -> facts.withObject("review").put("rememberAuthorization", true));
                changes.add(facts -> facts.withObject("review").put("configurationRevision", "f".repeat(64)));
                changes.add(facts -> facts.withObject("components").remove("web"));
                for (String key : List.of("unitId", "unitOwnershipSha256", "systemdUnit", "releaseRoot", "releaseSha256", "beforeRaw", "beforeAutostart"))
                    changes.add(facts -> facts.withObject("components").withObject("worker").put(key,
                        key.endsWith("Sha256") ? "f".repeat(64) : key.equals("beforeRaw") ? "STOPPED"
                            : key.equals("beforeAutostart") ? "DISABLED" : "changed"));
                changes.add(facts -> facts.withObject("components").withObject("web").put("attempted", true).put("verb", "stop"));
                changes.add(facts -> facts.withObject("components").withObject("worker").put("attempted", false).put("verified", false).putNull("verb"));
                for (var change : changes) {
                    var facts = DeploymentManagementActionLog.copy(original); change.accept(facts);
                    assertThrows(Exception.class, () -> log.advance(original, "FINISHED", facts, task));
                    assertEquals(original.facts(), log.find(task).orElseThrow().facts());
                }
                var concurrentFacts = DeploymentManagementActionLog.copy(original).put("reconciledReadOnly", true);
                var injected = new AtomicBoolean();
                var racedDatabase = (gold.debug.windowstolinux.appapi.db.ModDatabase) Proxy.newProxyInstance(
                    gold.debug.windowstolinux.appapi.db.ModDatabase.class.getClassLoader(),
                    new Class<?>[]{gold.debug.windowstolinux.appapi.db.ModDatabase.class}, (proxy, method, args) -> {
                        Object value;
                        try { value = method.invoke(harness.context.db(), args); } catch (InvocationTargetException error) { throw error.getCause(); }
                        if (!(value instanceof java.sql.Connection connection)) return value;
                        return Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                            (connectionProxy, operation, values) -> {
                                Object prepared;
                                try { prepared = operation.invoke(connection, values); } catch (InvocationTargetException error) { throw error.getCause(); }
                                if (!operation.getName().equals("prepareStatement") || !(prepared instanceof java.sql.PreparedStatement statement)
                                    || !((String) values[0]).startsWith("UPDATE deployment_management_operation SET phase=?")) return prepared;
                                assertTrue(((String) values[0]).contains("AND component_state=?"), "Version-two updates require an atomic original-byte CAS");
                                return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                                    (statementProxy, dispatch, inputs) -> {
                                        if (dispatch.getName().equals("executeUpdate") && injected.compareAndSet(false, true)) {
                                            // All pre-reads have passed; another writer changes facts in the same phase immediately before this UPDATE.
                                            try (var concurrent = harness.context.db().openConnection(task);
                                                 var update = concurrent.prepareStatement("UPDATE deployment_management_operation SET component_state=? WHERE operation_id=? AND phase=?")) {
                                                update.setBytes(1, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(concurrentFacts));
                                                update.setObject(2, original.id()); update.setString(3, original.phase()); assertEquals(1, update.executeUpdate());
                                            }
                                        }
                                        try { return dispatch.invoke(statement, inputs); } catch (InvocationTargetException error) { throw error.getCause(); }
                                    });
                            });
                    });
                var racedContext = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("db")) return racedDatabase;
                        try { return method.invoke(harness.context, args); } catch (InvocationTargetException error) { throw error.getCause(); }
                    });
                var stale = new DeploymentManagementActionLog(racedContext);
                assertEquals(ProblemCode.INCOMPATIBLE, assertThrows(ApiException.class,
                    () -> stale.advance(original, original.phase(), original.facts(), task)).problem().code());
                assertTrue(injected.get(), "The counterexample must reach the post-read UPDATE window");
                assertEquals(concurrentFacts, log.find(task).orElseThrow().facts(), "A same-phase stale writer cannot erase the concurrent facts");
                return result;
            };
            harness.finished(harness.service.lifecycleComponent(snapshot, "worker", DeploymentLifecycleAction.STOP));
            assertEquals(1, harness.peer.effects.get()); harness.assertNoFailure();
        }
    }

    static void isolatedManagedRecovery(java.nio.file.Path root, String scenario) throws Exception {
        var executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(executable.toString(), "-cp", System.getProperty("java.class.path"),
            DeploymentApplicationLifecycleTest.class.getName(), "--managed-v2", root.toString(), scenario).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output); assertTrue(output.contains("MANAGEMENT_V2_READ_ONLY_VERIFIED"), output);
        } finally {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CONFIRM", "DECLINE", "DENY", "IDENTITY", "RECEIPT_CHANGED", "LEGACY"})
    void originalRestartCleanupKeepsHistoryAndRequiresNewReview(String scenario) throws Exception {
        isolatedManagedRecovery(root, "CLEANUP_" + scenario);
    }

    @org.junit.jupiter.api.Test
    void originalCleanupLostStopAckUsesReadOnlyProof() throws Exception {
        isolatedManagedRecovery(root, "CLEANUP_ACK_LOST");
    }

    @org.junit.jupiter.api.Test
    void originalCleanupProviderFailureRetainsTypedUnknown() throws Exception {
        isolatedManagedRecovery(root, "CLEANUP_RUNTIME");
    }

    private void managedCleanup(String scenario) throws Exception {
        enable(); var runtime = open(); start(runtime, true); server(runtime);
        run(runtime, task -> { publishManaged(runtime, task, reviewedGraph(), 1); return success(); });
        var harness = new ManagedHarness(runtime.modContexts().get(OWNER).context());
        harness.waitSeconds = 50;
        var app = new DeploymentApplicationId("application", new ServerId("server"));
        var snapshot = harness.finished(harness.service.inspect(app));
        harness.peer.lossVerb = "start";
        harness.after = (task, result) -> {
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            var log = new DeploymentManagementActionLog(harness.context);
            var original = log.find(task).orElseThrow();
            if (scenario.equals("LEGACY")) {
                // A retained v1 row is input to the new cleanup; no live record is rewritten by production code.
                var legacy = DeploymentManagementActionLog.facts().put("before", original.facts().path("before").asText());
                legacy.set("target", original.facts().path("target"));
                legacy.set("components", original.facts().path("components"));
                try (var connection = harness.context.db().openConnection(task);
                     var update = connection.prepareStatement("UPDATE deployment_management_operation SET component_state=? WHERE platform_task_id=?")) {
                    update.setBytes(1, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(legacy));
                    update.setObject(2, task.id()); assertEquals(1, update.executeUpdate());
                }
                original = log.find(task).orElseThrow();
            }
            int effects = harness.peer.effects.get();
            harness.cleanupConfirmed = !scenario.equals("DECLINE");
            harness.failWorkflow = scenario.equals("RUNTIME");
            if (scenario.equals("DENY")) harness.decision = "DENY";
            if (scenario.equals("IDENTITY")) harness.peer.current.put("web", "f".repeat(64));
            if (scenario.equals("ACK_LOST")) { harness.peer.lossVerb = "stop"; harness.peer.lossUnit = "worker"; }
            if (scenario.equals("RECEIPT_CHANGED")) {
                var facts = DeploymentManagementActionLog.copy(original);
                facts.putObject("lifecycleCleanup").put("formatVersion", 1).put("confirmedTaskId", UUID.randomUUID().toString());
                log.advance(original, original.phase(), facts, task);
            }
            OperationResult<?> recovered;
            if (scenario.equals("LEGACY") || scenario.equals("RUNTIME")) {
                var method = DeploymentApplicationsService.class.getDeclaredMethod("cleanupOriginalLifecycle", DeploymentManagementActionLog.Entry.class, TaskContext.class);
                method.setAccessible(true);
                recovered = (OperationResult<?>) method.invoke(new DeploymentApplicationsService(harness.context), original, task);
            } else recovered = new DeploymentApplicationsService(harness.context).reconcile(harness.actual.tasks().inspect(task), task);
            boolean positive = scenario.equals("CONFIRM") || scenario.equals("ACK_LOST") || scenario.equals("LEGACY");
            if (scenario.equals("ACK_LOST")) {
                assertEquals(TaskResult.UNKNOWN, recovered.outcome());
                assertEquals(effects + 2, harness.peer.effects.get());
                recovered = new DeploymentApplicationsService(harness.context).reconcile(harness.actual.tasks().inspect(task), task);
                assertEquals(effects + 4, harness.peer.effects.get(), "Lost STOP ACK is observed, only the new disable phase sends effects");
            }
            assertEquals(positive ? TaskResult.FAILED : TaskResult.UNKNOWN, recovered.outcome(), recovered::toString);
            if (scenario.equals("RUNTIME")) assertEquals("DEPLOYMENT_ORIGINAL_CLEANUP_RUNTIME_FAILURE", recovered.problem().orElseThrow().eventCode());
            if (positive) {
                assertEquals("DEPLOYMENT_ORIGINAL_UNCONFIRMED_CLEANUP_VERIFIED", recovered.problem().orElseThrow().eventCode());
                assertTrue(harness.peer.running.values().stream().noneMatch(Boolean::booleanValue));
                assertTrue(harness.peer.enabled.values().stream().noneMatch(Boolean::booleanValue));
                var retained = log.find(task).orElseThrow();
                assertEquals(original.phase(), retained.phase());
                var preserved = DeploymentManagementActionLog.copy(retained); preserved.remove("lifecycleCleanup");
                assertEquals(original.facts(), preserved, "Original unknown attempts and missing ACKs are unchanged");
                for (String action : List.of("STOP", "DISABLE_AUTOSTART"))
                    assertEquals("FINISHED", retained.facts().path("lifecycleCleanup").path("operations").path(action).path("phase").asText());
            } else assertEquals(effects, harness.peer.effects.get(), "Decline, review denial, identity drift or receipt mismatch cannot send cleanup");
            assertEquals(0, harness.peer.sessions.get()); assertEquals(0, harness.openWorkflows.get());
            return recovered;
        };
        var outcome = harness.result(harness.service.lifecycle(snapshot, DeploymentLifecycleAction.RESTART));
        harness.assertNoFailure();
        if (outcome.outcome() == TaskResult.UNKNOWN) assertThrows(ApiException.class, runtime::close);
        else runtime.close();
        System.out.println("MANAGEMENT_V2_READ_ONLY_VERIFIED CLEANUP_" + scenario);
    }

    /** Actual ACTION Task/journal with controlled host/AI/peer; this is not the Mod registry integration test. */
    private void managedRecovery(String scenario) throws Exception {
        enable(); var runtime = open(); start(runtime, true); server(runtime);
        run(runtime, task -> { publishManaged(runtime, task, reviewedGraph(), 1); return success(); });
        var harness = new ManagedHarness(runtime.modContexts().get(OWNER).context());
        var app = new DeploymentApplicationId("application", new ServerId("server"));
        var snapshot = harness.finished(harness.service.inspect(app));
        boolean start = scenario.equals("START_ACK") || scenario.equals("HEALTH");
        if (start) { harness.finished(harness.service.lifecycleComponent(snapshot, "worker", DeploymentLifecycleAction.STOP)); snapshot = harness.finished(harness.service.inspect(app)); }
        boolean disable = scenario.startsWith("DISABLE_");
        harness.peer.lossVerb = start ? "start" : disable ? "disable" : "stop";
        harness.peer.lossUnit = disable && !scenario.equals("DISABLE_PARTIAL_ACK") ? "web" : "worker";
        harness.after = (task, result) -> {
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals(DeploymentApplicationsService.ACTION_TASK, harness.actual.tasks().inspect(task).type());
            var log = new DeploymentManagementActionLog(harness.context);
            var original = log.find(task).orElseThrow();
            assertEquals("AUTOMATIC", original.facts().path("review").path("approval").textValue());
            assertFalse(original.facts().path("review").path("rememberAuthorization").booleanValue());
            int effects = harness.peer.effects.get();
            if (scenario.equals("OTHER_RAW")) harness.peer.running.put("web", false);
            if (scenario.equals("DISABLE_RAW_DRIFT")) harness.peer.running.put("web", false);
            if (scenario.equals("OTHER_AUTOSTART")) harness.peer.enabled.put("web", false);
            if (scenario.equals("IDENTITY")) harness.peer.current.put("web", "f".repeat(64));
            if (scenario.equals("MODEL")) harness.modelRevision = "f".repeat(64);
            if (scenario.equals("TARGET")) harness.peer.fingerprint = "SHA256:" + "B".repeat(43);
            if (scenario.equals("HEALTH")) harness.peer.forceUnhealthy = true;
            var recovered = new DeploymentApplicationsService(harness.context).reconcile(harness.actual.tasks().inspect(task), task);
            assertEquals(effects, harness.peer.effects.get(), "Original reconciliation never resends STOP/START/DISABLE");
            boolean positive = Set.of("STOP_ACK", "START_ACK", "DISABLE_ACK").contains(scenario);
            assertEquals(positive ? TaskResult.SUCCEEDED : TaskResult.UNKNOWN, recovered.outcome());
            var retained = log.find(task).orElseThrow();
            assertEquals(original.id(), retained.id()); assertEquals(original.taskId(), retained.taskId());
            var lost = retained.facts().path("components").path(harness.peer.lossUnit);
            assertFalse(lost.path("verified").booleanValue(), "A new read-only proof cannot rewrite the missing original ACK");
            if (positive) assertTrue(retained.facts().path("reconciledReadOnly").booleanValue());
            return recovered;
        };
        var handle = disable ? harness.service.lifecycle(snapshot, DeploymentLifecycleAction.DISABLE_AUTOSTART)
            : harness.service.lifecycleComponent(snapshot, "worker", start ? DeploymentLifecycleAction.START : DeploymentLifecycleAction.STOP);
        var outcome = harness.result(handle);
        harness.assertNoFailure();
        assertEquals(0, harness.peer.sessions.get()); assertEquals(0, harness.openWorkflows.get());
        if (outcome.outcome() == TaskResult.UNKNOWN) { assertEquals(TaskState.UNKNOWN, handle.snapshot().state()); assertThrows(ApiException.class, runtime::close); }
        else { assertEquals(TaskState.ENDED, handle.snapshot().state()); runtime.close(); }
        System.out.println("MANAGEMENT_V2_READ_ONLY_VERIFIED " + scenario);
    }

    @FunctionalInterface
    private interface ManagedAfter {
        OperationResult<?> run(TaskContext task, OperationResult<?> result) throws Exception;
    }

    private final class ManagedHarness {
        final ModContext actual;
        final ModContext context;
        final Peer peer = new Peer(ScenarioType.FINAL_HEALTH_FAILURE);
        final DeploymentApplicationsService service;
        final AtomicInteger reviews = new AtomicInteger(), hostGrants = new AtomicInteger(), openWorkflows = new AtomicInteger();
        final Set<String> reviewed = new HashSet<>();
        final Set<UUID> issued = new HashSet<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Map<UUID, OperationResult<?>> workResults = new java.util.concurrent.ConcurrentHashMap<>();
        String modelRevision = "b".repeat(64), decision = "APPROVE";
        boolean cleanupConfirmed;
        boolean failWorkflow;
        int waitSeconds = 20;
        ManagedAfter after;

        ManagedHarness(ModContext actual) {
            this.actual = actual; peer.managedMode = true; peer.healthy = true;
            var base = bind(actual, peer);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                var task = Arrays.stream(args).filter(TaskContext.class::isInstance).map(TaskContext.class::cast).findFirst().orElseThrow();
                assertTrue(Set.of(DeploymentApplicationsService.ACTION_TASK, DeploymentApplicationsService.QUERY_TASK).contains(actual.tasks().inspect(task).type()));
                if (method.getName().equals("configurationRevision")) return modelRevision;
                var request = (AiRequest) args[0]; assertEquals(AiPurpose.APPROVAL, request.purpose());
                var facts = json.readTree(request.messages().getLast().text());
                var identity = facts.path("currentCommandEvidence").path("managementIdentity");
                assertEquals(task.id().toString(), identity.path("taskId").asText());
                assertEquals("AUTOMATIC", identity.path("review").path("approval").asText());
                assertFalse(identity.path("review").path("rememberAuthorization").booleanValue());
                if (decision.equals("APPROVE")) assertTrue(reviewed.add(task.id() + ":" + facts.path("exactAction").asText()));
                reviews.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(new AiToolCall("review-" + reviews.get(), "submit_result", json.writeValueAsString(Map.of(
                        "decision", decision, "requiresUserConfirmation", false, "reason", "Controlled independent management review")))), Optional.empty())), Optional.empty());
            });
            var host = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(), new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
                var task = (TaskContext) args[args.length - 1]; actual.tasks().inspect(task);
                if (method.getName().equals("requestText")) {
                    assertEquals("仅停止原应用并结清", args[0]); assertEquals(false, args[2]);
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(cleanupConfirmed ? "STOP " + task.id() : ""), Optional.empty());
                }
                if (method.getName().equals("openReviewedWorkflow")) {
                    if (failWorkflow) throw new IllegalStateException("Controlled cleanup provider failure");
                    assertEquals(false, args[2]);
                    var lease = task.retain("controlled_management_workflow", UUID.randomUUID().toString(), "Controlled task-bound workflow");
                    openWorkflows.incrementAndGet();
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> { lease.close(); openWorkflows.decrementAndGet(); }), Optional.empty());
                }
                assertEquals("approveReviewedAction", method.getName(), "No bare host approval fallback");
                var intent = (ActionIntent) args[0];
                assertTrue(reviewed.remove(task.id() + ":" + intent.exactAction()));
                var request = (CommandRequest) ((Optional<?>) args[1]).orElseThrow();
                var grant = new ApprovalGrant(UUID.randomUUID()); assertTrue(issued.add(grant.id()));
                peer.grants.put(grant.id(), request); hostGrants.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            var tasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(), new Class<?>[]{TaskService.class}, (proxy, method, args) -> {
                if (method.getName().equals("submit")) {
                    var request = (TaskRequest) args[0];
                    assertTrue(Set.of(DeploymentApplicationsService.ACTION_TASK, DeploymentApplicationsService.QUERY_TASK).contains(request.type()));
                    var work = (TaskWork<?>) args[1];
                    args[1] = (TaskWork<Object>) task -> {
                        try {
                            var result = work.run(task);
                            var next = request.type().equals(DeploymentApplicationsService.ACTION_TASK) && after != null ? after.run(task, result) : result;
                            workResults.put(task.id(), next);
                            return new OperationResult<>(next.outcome(), next.value().map(value -> (Object) value), next.problem());
                        } catch (Exception | AssertionError error) { failure.set(error); throw error; }
                    };
                }
                try { return method.invoke(actual.tasks(), args); } catch (InvocationTargetException error) { throw error.getCause(); }
            });
            context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "ai" -> ai; case "interaction" -> host; case "tasks" -> tasks;
                default -> { try { yield method.invoke(base, args); } catch (InvocationTargetException error) { throw error.getCause(); } }
            });
            service = new DeploymentApplicationsService(context, DeploymentApplicationsService.AgentManagementPolicy.INDEPENDENT_APP);
        }

        @SuppressWarnings("unchecked")
        <T> OperationResult<T> result(TaskHandle<T> handle) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
            while (System.nanoTime() < deadline) {
                assertNoFailure();
                var snapshot = handle.snapshot();
                if (snapshot.state() == TaskState.ENDED)
                    return handle.completion().toCompletableFuture().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (snapshot.state() == TaskState.UNKNOWN) {
                    var observed = workResults.get(handle.id());
                    assertNotNull(observed, () -> "Original task became UNKNOWN without a captured result: " + snapshot);
                    assertEquals(TaskResult.UNKNOWN, observed.outcome(), () -> "Host retained UNKNOWN after original work result: " + observed + "; " + snapshot);
                    return (OperationResult<T>) observed;
                }
                Thread.sleep(10);
            }
            assertNoFailure();
            throw new AssertionError("Original management task did not settle within 20 seconds: " + handle.snapshot());
        }

        <T> T finished(TaskHandle<T> handle) throws Exception {
            var result = result(handle); assertNoFailure();
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result::toString); assertEquals(TaskState.ENDED, handle.snapshot().state());
            return result.value().orElseThrow();
        }

        void assertNoFailure() { if (failure.get() != null) throw new AssertionError("Controlled real management task failed", failure.get()); }
    }

    /** Runs actual management evidence without inventing an unknown final acknowledgement. / 执行实际管理依据，不捏造未知最终确认。
     * @param scenario exact peer behavior / 精确对等端行为
     * @param interruptedHost whether the child preserves a pending host / 子进程是否保留待核对宿主
     * @throws Exception if actual original evidence differs / 实际原依据不符时 */
    private void runScenario(ScenarioType scenario, boolean interruptedHost) throws Exception {
        enable();
        var runtime = open();
        Throwable originalFailure = null;
        try {
            start(runtime, true);
            server(runtime);
            var graph = reviewedGraph();
            run(runtime, task -> {
                publishManaged(runtime, task, graph, 1);
                return success();
            });
            var actual = runtime.modContexts().get(OWNER).context();
            var peer = new Peer(scenario);
            ModContext context = bind(actual, peer);
            var failures = new AtomicReference<Throwable>();
            var handle = actual.tasks().submit(
                new TaskRequest("graph_acceptance", "Lifecycle contract", List.of("server"), true), task -> {
                    try {
                        var app = new DeploymentApplicationId("application", new ServerId("server"));
                        var snapshot = new DeploymentApplicationReader(context.db()).find(app, task).snapshot();
                        var manager = new DeploymentApplicationRunner(context);
                        var action = scenario == ScenarioType.DEGRADED_STOP
                            || scenario == ScenarioType.CURRENT_DRIFT || scenario == ScenarioType.DISPATCH_DRIFT
                            || scenario == ScenarioType.FINAL_CURRENT_DRIFT || scenario == ScenarioType.STOP_ACK_LOST
                            || scenario == ScenarioType.STOP_UNCONFIRMED || scenario == ScenarioType.STOP_UNTOUCHED_DRIFT
                            ? DeploymentLifecycleAction.STOP
                            : DeploymentLifecycleAction.RESTART;
                        var first = manager.lifecycle(app, snapshot.revisionSha256(), action, task);
                        if (scenario == ScenarioType.CURRENT_DRIFT) {
                            assertEquals(TaskResult.FAILED, first.outcome());
                            assertEquals(0, peer.effects.get());
                            assertTrue(new DeploymentManagementActionLog(context).find(task).isEmpty());
                        } else if (scenario == ScenarioType.DEGRADED_STOP) {
                            assertEquals(TaskResult.SUCCEEDED, first.outcome());
                            assertEquals(2, peer.effects.get());
                        } else {
                            assertEquals(TaskResult.UNKNOWN, first.outcome());
                            assertEquals("UNKNOWN",
                                new DeploymentManagementActionLog(context).find(task).orElseThrow().phase());
                        }
                        if (scenario == ScenarioType.CURRENT_DRIFT)
                            return new OperationResult<Void>(first.outcome(), Optional.empty(), first.problem());
                        assertCompleteObservation(context, task);
                        int before = peer.effects.get();
                        peer.healthy = true;
                        var repeated = manager.lifecycle(app, snapshot.revisionSha256(), action, task);
                        assertEquals(before, peer.effects.get(),
                            "Repeated original-task query cannot resend lifecycle");
                        TaskResult expected = scenario == ScenarioType.DISPATCH_DRIFT
                            || scenario == ScenarioType.RESTART_ACK_LOST
                            || scenario == ScenarioType.FINAL_CURRENT_DRIFT || scenario == ScenarioType.STOP_UNCONFIRMED
                            || scenario == ScenarioType.STOP_UNTOUCHED_DRIFT
                            ? TaskResult.UNKNOWN
                            : scenario == ScenarioType.STOP_ACK_LOST || scenario == ScenarioType.RESTART_VERIFIED_PARTIAL || scenario == ScenarioType.RESTART_PARTIAL_ACK_LOST ? TaskResult.FAILED : TaskResult.SUCCEEDED;
                        assertEquals(expected, repeated.outcome());
                        if (scenario == ScenarioType.RESTART_VERIFIED_PARTIAL || scenario == ScenarioType.RESTART_PARTIAL_ACK_LOST) {
                            assertEquals("DEPLOYMENT_LIFECYCLE_RESTART_PARTIAL_VERIFIED", repeated.problem().orElseThrow().eventCode());
                            assertEquals(3, peer.effects.get());
                            var original = new DeploymentManagementActionLog(context).find(task).orElseThrow();
                            assertEquals("FINISHED", original.phase());
                            assertTrue(original.facts().path("partialRestartVerified").asBoolean());
                            assertTrue(original.facts().path("reconciledReadOnly").asBoolean());
                            assertFalse(original.facts().path("acknowledgementsReconstructed").asBoolean(true));
                            if (scenario == ScenarioType.RESTART_PARTIAL_ACK_LOST)
                                assertTrue(java.util.stream.StreamSupport.stream(original.facts().path("components").spliterator(), false)
                                    .anyMatch(part -> part.path("verb").asText().equals("start") && !part.path("verified").asBoolean(true)));
                        }
                        if (scenario == ScenarioType.STOP_ACK_LOST) {
                            assertEquals("DEPLOYMENT_LIFECYCLE_STOP_PARTIAL_VERIFIED", repeated.problem().orElseThrow().eventCode());
                            assertEquals(1, peer.effects.get());
                            var original = new DeploymentManagementActionLog(context).find(task).orElseThrow();
                            assertEquals("FINISHED", original.phase());
                            assertTrue(original.facts().path("partialStopVerified").asBoolean());
                            assertTrue(original.facts().path("reconciledReadOnly").asBoolean());
                        }
                        if (scenario == ScenarioType.DISPATCH_DRIFT)
                            assertEquals(0, peer.effects.get(),
                                "Lock-time same-owner CURRENT drift prevents the effect");
                        if (scenario == ScenarioType.FINAL_CURRENT_DRIFT)
                            assertEquals(2, peer.effects.get(),
                                "Both stops completed before the final CURRENT drift");
                        return new OperationResult<Void>(repeated.outcome(), Optional.empty(), repeated.problem());
                    } catch (Exception | AssertionError failure) {
                        failures.set(failure);
                        throw failure;
                    }
                });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
                && System.nanoTime() < deadline)
                Thread.sleep(20);
            if (failures.get() != null)
                throw new AssertionError("Original management fixture failed", failures.get());
            assertTrue(handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN,
                handle.snapshot().toString());
            assertEquals(0, peer.sessions.get(), "All provider sessions close before the task reports its state");
            assertEquals(
                scenario == ScenarioType.DISPATCH_DRIFT || scenario == ScenarioType.RESTART_ACK_LOST
                    || scenario == ScenarioType.FINAL_CURRENT_DRIFT || scenario == ScenarioType.STOP_UNCONFIRMED
                    || scenario == ScenarioType.STOP_UNTOUCHED_DRIFT ? TaskState.UNKNOWN : TaskState.ENDED,
                handle.snapshot().state());
            if (interruptedHost) {
                var refusal = assertThrows(ApiException.class, runtime::close);
                assertEquals(ProblemCode.BUSY, refusal.problem().code());
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(),
                    "Shutdown refusal cannot conclude the original task");
                assertEquals(0, peer.sessions.get());
            }
        } catch (Exception | AssertionError failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            if (!interruptedHost) {
                if (originalFailure == null)
                    runtime.close();
                else
                    try {
                        runtime.close();
                    } catch (Exception | AssertionError cleanup) {
                        originalFailure.addSuppressed(cleanup);
                    }
            }
        }
    }

    /** Enters only the test-owned unknown child and exits without claiming host cleanup. / 仅进入测试所属未知子进程并不冒称宿主清理地退出。
     * @param args fixed marker, original directory and scenario / 固定标志、原目录及场景 */
    public static void main(String[] args) {
        if (args.length == 3 && args[0].equals("--managed-v2")) {
            try {
                var probe = new DeploymentApplicationLifecycleTest(); probe.root = java.nio.file.Path.of(args[1]);
                if (args[2].startsWith("CLEANUP_")) probe.managedCleanup(args[2].substring(8));
                else if (args[2].equals("APP_REVIEW")) probe.managedAppReview();
                else probe.managedRecovery(args[2]);
                System.exit(0);
            } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
            return;
        }
        if (args.length != 3 || !args[0].equals("--unknown"))
            throw new IllegalArgumentException("Original management child only");
        try {
            var scenario = ScenarioType.valueOf(args[2]);
            if (scenario != ScenarioType.DISPATCH_DRIFT && scenario != ScenarioType.RESTART_ACK_LOST
                && scenario != ScenarioType.FINAL_CURRENT_DRIFT && scenario != ScenarioType.STOP_UNCONFIRMED
                && scenario != ScenarioType.STOP_UNTOUCHED_DRIFT)
                throw new IllegalArgumentException("Unknown management scenario only");
            var probe = new DeploymentApplicationLifecycleTest();
            probe.root = java.nio.file.Path.of(args[1]);
            probe.runScenario(scenario, true);
            System.out.println("MANAGED_APPLICATION_UNKNOWN_VERIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Verifies the real owned H2 observation tuple without manufacturing enablement evidence. / 核对真实所属H2观测元组，不捏造启用依据。
     * @param context original deployment context / 原部署上下文
     * @param task authenticated original task / 已认证原任务
     * @throws Exception if persisted complete evidence differs / 持久完整依据不符时 */
    private void assertCompleteObservation(ModContext context, TaskContext task) throws Exception {
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT runtime_state,autostart_state,is_ownership_verified,observation_evidence,observed_at FROM deployment_unit ORDER BY deployment_unit_id");
             var rows = query.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                count++;
                assertNotNull(rows.getString(1));
                assertEquals(rows.getString(1).equals("UNKNOWN") ? "UNKNOWN" : "ENABLED", rows.getString(2));
                assertNotNull(rows.getObject(3));
                assertEquals("deployment-helper-observation-v1", rows.getString(4));
                assertNotNull(rows.getObject(5));
            }
            assertEquals(2, count);
        }
    }

    private DeploymentApplicationGraph reviewedGraph() {
        var original = graph("application");
        var parts = original.components().stream().map(value -> {
            var old = value.runtime().workload();
            var workload = new DeploymentWorkload(old.mode(), true, old.command(), old.workingDirectory(), old.endpoints(),
                old.verification(), old.expectedOutput(), old.client(), old.inputs(), old.companions(), old.buildDirectory(), old.workers());
            var definition = value.reviewedRuntime().withWorkload(workload);
            var runtime = new DeploymentUnitRuntimeConfig(value.runtime().healthCheck(), value.runtime().userAccessUrl(),
                value.runtime().identityPolicy(), workload);
            return new DeploymentApplicationGraph.Part(value.componentId(), value.unit(), runtime, value.dependencies(),
                definition, value.dataPaths(), value.resources());
        }).toList();
        return new DeploymentApplicationGraph(original.applicationId(), original.healthComponentId(), original.healthCheck(), parts);
    }

    private ModContext bind(ModContext actual, Peer peer) {
        LinuxService linux = new LinuxService() {
            public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                return peer.session(task);
            }

            public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                var current = actual.servers().get(expected.server(), task);
                assertEquals(task.id(), expected.taskId());
                assertEquals(current.host(), expected.host());
                assertEquals(current.port(), expected.port());
                assertEquals(current.username(), expected.username());
                expected.fingerprint().ifPresent(value -> assertEquals("SHA256:" + "A".repeat(43), value));
                return peer.session(task);
            }

            public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                return List.of();
            }
        };
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("linux"))
                    return linux;
                try {
                    return method.invoke(actual, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private static final class Peer {
        final ScenarioType scenario;

        final AtomicInteger effects = new AtomicInteger();

        final AtomicInteger sessions = new AtomicInteger();

        final Map<String, String> current = new HashMap<>();

        final Map<String, Boolean> running = new HashMap<>();

        volatile boolean healthy;

        boolean managedMode, forceUnhealthy;
        String lossVerb;
        String lossUnit = "worker";
        String fingerprint = "SHA256:" + "A".repeat(43);
        final AtomicInteger sends = new AtomicInteger();
        final Map<UUID, CommandRequest> grants = new HashMap<>();
        final Map<String, Boolean> enabled = new HashMap<>();

        Peer(ScenarioType scenario) {
            this.scenario = scenario;
            for (String unit : List.of("web", "worker")) {
                current.put(unit, "a".repeat(64));
                running.put(unit, true);
                enabled.put(unit, true);
            }
            if (scenario == ScenarioType.CURRENT_DRIFT)
                current.put("worker", "f".repeat(64));
        }

        RemoteSession session(TaskContext task) throws ApiException {
            var lease = task.retain("controlled_ssh", "management-peer-" + UUID.randomUUID(),
                "Controlled management session");
            sessions.incrementAndGet();
            return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("facts"))
                        return new RemoteFacts("linux", "x86_64", fingerprint);
                    if (method.getName().equals("close")) {
                        lease.close();
                        sessions.decrementAndGet();
                        return null;
                    }
                    if (method.getName().equals("execute")) {
                        assertNotNull(args[1]);
                        if (managedMode) {
                            assertSame(args[0], grants.remove(((ApprovalGrant) args[1]).id()), "One fresh reviewed grant per exact send");
                            sends.incrementAndGet();
                        }
                        return command((CommandRequest) args[0]);
                    }
                    throw new AssertionError(method.getName());
                });
        }

        private OperationResult<CommandResult> command(CommandRequest request) {
            if (request.script().contains("managed-helper' probe"))
                return reply(0, "HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            var args = new ArrayList<String>();
            var matcher = Pattern.compile("'([^']*)'").matcher(DeploymentManagedHelperBundle.verifiedBody(request.script()));
            while (matcher.find())
                args.add(matcher.group(1));
            String verb = args.get(1), unit = args.get(2);
            if (verb.equals("inspect-runtime")) {
                assertEquals(4, args.size());
                assertEquals("a".repeat(64), args.get(3));
                assertTrue(current.containsKey(unit));
                return reply(0, "KIND=deployment\nMODE=DAEMON\n");
            }
            if (verb.equals("managed-current-status")) {
                if (scenario == ScenarioType.RESTART_VERIFIED_PARTIAL && effects.get() == 3 && !healthy)
                    return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                        ProblemCode.IO_FAILURE, "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT", "Controlled read opening failure", Map.of(), Optional.empty())));
                if (scenario == ScenarioType.FINAL_CURRENT_DRIFT && effects.get() == 2)
                    current.put("worker", "f".repeat(64));
                return reply(0, "MANAGED_CURRENT=1\nKIND=deployment\nRELEASE_SHA256=" + current.get(unit) + "\n");
            }
            if (verb.equals("application-health"))
                return reply(managedMode ? (forceUnhealthy || !running.get("worker") ? 1 : 0) : scenario == ScenarioType.DEGRADED_STOP
                        || scenario == ScenarioType.FINAL_HEALTH_FAILURE && effects.get() >= 4 && !healthy ? 1 : 0,
                    "HEALTHY=1\n");
            if (verb.equals("observe-deployment"))
                return observe(unit);
            if (verb.equals("lifecycle-deployment")) {
                assertEquals(6, args.size());
                assertEquals("a".repeat(64), args.get(4));
                if (managedMode) {
                    if (!current.get(unit).equals(args.get(5))) return reply(1, "");
                    effects.incrementAndGet(); String action = args.get(3);
                    if (action.equals("enable") || action.equals("disable")) enabled.put(unit, action.equals("enable"));
                    else running.put(unit, action.equals("start"));
                    if (action.equals(lossVerb) && unit.equals(lossUnit)) {
                        lossVerb = null;
                        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                            "CONTROLLED_MANAGEMENT_ACK_LOST", "Original acknowledgement unavailable", Map.of(), Optional.empty())));
                    }
                    return reply(0, "LIFECYCLE=" + action + "\n");
                }
                if (scenario == ScenarioType.DISPATCH_DRIFT)
                    current.put(unit, "f".repeat(64));
                if (!current.get(unit).equals(args.get(5)))
                    return reply(1, "");
                effects.incrementAndGet();
                String action = args.get(3);
                running.put(unit, !action.equals("stop"));
                if ((scenario == ScenarioType.STOP_ACK_LOST || scenario == ScenarioType.STOP_UNCONFIRMED
                    || scenario == ScenarioType.STOP_UNTOUCHED_DRIFT) && effects.get() == 1) {
                    if (scenario == ScenarioType.STOP_UNCONFIRMED) running.put(unit, true);
                    if (scenario == ScenarioType.STOP_UNTOUCHED_DRIFT) running.replaceAll((key, value) -> false);
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_STOP_ACK_LOST",
                            "Stop acknowledgement unavailable", Map.of(), Optional.empty())));
                }
                if (scenario == ScenarioType.RESTART_PARTIAL_ACK_LOST && action.equals("start"))
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                        "CONTROLLED_START_ACK_LOST", "Start acknowledgement unavailable", Map.of(), Optional.empty())));
                if (scenario == ScenarioType.RESTART_ACK_LOST && action.equals("start")) {
                    running.replaceAll((key, value) -> true);
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_START_ACK_LOST",
                            "Start acknowledgement unavailable", Map.of(), Optional.empty())));
                }
                return reply(0, "LIFECYCLE=" + action + "\n");
            }
            throw new AssertionError(request.script());
        }

        private OperationResult<CommandResult> observe(String unit) {
            boolean active = running.get(unit);
            return reply(0, "OWNER=1\nRUNNING=" + (active ? "1" : "0") + "\nENABLED=" + (enabled.get(unit) ? "enabled" : "disabled") + "\nQUERY_OK=1\nActiveState="
                + (active ? "active" : "inactive") + "\nSubState=" + (active ? "running" : "dead")
                + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=" + (active ? "123" : "0") + "\n");
        }

        private OperationResult<CommandResult> reply(int exit, String text) {
            return new OperationResult<>(TaskResult.SUCCEEDED,
                Optional.of(new CommandResult(Optional.of(exit), text, "", false)), Optional.empty());
        }
    }
}

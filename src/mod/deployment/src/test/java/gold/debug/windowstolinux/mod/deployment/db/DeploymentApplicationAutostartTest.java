package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationAutostartState;
import gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentCollectionRequest;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentHandoffState;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationRunner;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentManagementActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.collect.DeploymentCollectionFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentHandoffLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Automatic startup is an actual component state change under the original journal, preserving runtime. / 自启在原日志下改变实际组件状态并保持运行态。 */
class DeploymentApplicationAutostartTest extends DeploymentAutostartFixture {
    @ParameterizedTest
    @ValueSource(strings = {"STOP_ACK", "START_ACK", "DISABLE_ACK", "DISABLE_PARTIAL_ACK", "DISABLE_RAW_DRIFT", "OTHER_RAW", "OTHER_AUTOSTART", "IDENTITY", "MODEL", "TARGET", "HEALTH"})
    void originalComponentUnknownReconcilesReadOnlyWithoutResend(String scenario) throws Exception {
        DeploymentApplicationLifecycleTest.isolatedManagedRecovery(root, scenario);
    }

    @ParameterizedTest
    @CsvSource({"NATIVE,false", "NATIVE,true", "DOCKER,false", "DOCKER,true", "PODMAN,false", "PODMAN,true"})
    void actualAutostartChangesEveryDaemonAndPreservesRunningOrStoppedState(Backend backend, boolean active) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            var graph = lifecycleGraph(backend, true);
            seedUsage(runtime, graph, 1);
            var controlled = new ManagedPeer(context(runtime), graph, active, active);
            var bound = bind(context(runtime), controlled);
            var handle = rootTask(runtime, task -> {
                var manager = new DeploymentApplicationRunner(bound);
                var app = new DeploymentApplicationId("demo", graph.components().getFirst().unit().server());
                var observed = manager.inspect(app, task).value().orElseThrow();
                var before = active ? ApplicationRunState.RUNNING : ApplicationRunState.STOPPED;
                assertEquals(before, observed.state());
                assertEquals(active ? ApplicationAutostartState.ENABLED : ApplicationAutostartState.DISABLED, observed.autostart());
                var action = active ? DeploymentLifecycleAction.DISABLE_AUTOSTART : DeploymentLifecycleAction.ENABLE_AUTOSTART;
                var result = manager.lifecycle(app, observed.revisionSha256(), action, task);
                assertEquals(TaskResult.SUCCEEDED, result.outcome());
                assertEquals(before, result.value().orElseThrow().after());
                assertEquals(2, controlled.effects.get());
                assertTrue(controlled.running.values().stream().allMatch(value -> value == active));
                var saved = new DeploymentApplicationReader(bound.db()).find(app, task);
                for (var part : graph.components()) {
                    var fact = saved.observations().get(part.unit().id());
                    if (part.componentId().equals("job")) {
                        assertEquals(ApplicationRunState.INSTALLED, fact.state());
                        assertEquals(ApplicationAutostartState.NOT_APPLICABLE, fact.autostart());
                    } else {
                        assertEquals(before, fact.state());
                        assertEquals(active ? ApplicationAutostartState.DISABLED : ApplicationAutostartState.ENABLED, fact.autostart());
                    }
                }
                var original = new DeploymentManagementActionLog(bound).find(task).orElseThrow();
                assertEquals(controlled.operation.get(), original.id());
                assertEquals("FINISHED", original.phase());
                assertFalse(original.facts().path("components").path("job").path("attempted").asBoolean());
                assertEquals(TaskResult.SUCCEEDED, manager.lifecycle(app, observed.revisionSha256(), action, task).outcome());
                assertEquals(2, controlled.effects.get(), "Same original task repeats only a read-only target check");
                return success();
            });
            assertEquals(TaskResult.SUCCEEDED, await(handle).outcome());
            assertEquals(0, controlled.sessions.get());
            assertNoActiveResources(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void installedJobsAndUnreviewedGraphsRejectForcedServiceLifecycleBeforeConnectOrJournal(boolean reviewedJob) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            var graph = usageGraph("demo", reviewedJob, reviewedJob);
            seedUsage(runtime, graph, 1);
            var controlled = new ManagedPeer(context(runtime), graph, false, false);
            var service = new DeploymentApplicationsService(bind(context(runtime), controlled));
            var selected = await(service.inventory()).value().orElseThrow().getFirst();
            assertTrue(selected.usage().orElseThrow().actions().isEmpty());
            for (var action : DeploymentLifecycleAction.values()) {
                var result = await(service.lifecycle(selected.managed().orElseThrow(), action));
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals(reviewedJob ? "DEPLOYMENT_APPLICATION_LIFECYCLE_NOT_APPLICABLE" : "DEPLOYMENT_APPLICATION_DECLARATION_UNREVIEWED", result.problem().orElseThrow().eventCode());
            }
            assertEquals(0, controlled.connects.get(), "Forced daemon actions must refuse before connecting");
            var checked = rootTask(runtime, task -> {
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_management_operation"));
                if (reviewedJob) {
                    var observed = new DeploymentApplicationRunner(bind(context(runtime), controlled)).inspect(selected.managed().orElseThrow().application(), task);
                    assertEquals(TaskResult.SUCCEEDED, observed.outcome());
                    assertEquals(ApplicationRunState.INSTALLED, observed.value().orElseThrow().state());
                    assertEquals(ApplicationAutostartState.NOT_APPLICABLE, observed.value().orElseThrow().autostart());
                }
                return success();
            });
            assertEquals(TaskResult.SUCCEEDED, await(checked).outcome());
            assertEquals(0, controlled.effects.get());
            assertEquals(0, controlled.sessions.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void lostLastAutostartAcknowledgementReconcilesExactOriginalTargetsWithoutResend() throws Exception {
        acknowledgedTarget(2, false);
    }

    @Test
    void unreviewedRunningCollectionRejectsQuiescenceBeforeAnyMaintenanceOrLifecycleEffect() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            var graph = usageGraph("demo", false, false);
            seedUsage(runtime, graph, 1);
            var controlled = new ManagedPeer(context(runtime), graph, true, true);
            var bound = bind(context(runtime), controlled);
            var handle = rootTask(runtime, task -> {
                var app = new DeploymentApplicationId("demo", graph.components().getFirst().unit().server());
                var snapshot = new DeploymentApplicationReader(bound.db()).find(app, task).snapshot();
                var collection = new DeploymentCollectionFlow(bound);
                var reviewed = collection.review(new DeploymentCollectionRequest(app, snapshot.revisionSha256(),
                    List.of("service:uploads")), task).value().orElseThrow();
                var result = collection.collect(reviewed.id(), task);
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals("DEPLOYMENT_COLLECTION_LIFECYCLE_DECLARATION_UNREVIEWED", result.problem().orElseThrow().eventCode());
                var retained = new DeploymentHandoffLedger(bound).find(reviewed.id(), task).orElseThrow();
                assertEquals(DeploymentHandoffState.REVIEWED, retained.publicValue().state());
                assertFalse(retained.recovery().path("changed").asBoolean());
                assertEquals(0, controlled.maintenance.get());
                assertEquals(0, controlled.effects.get());
                return success();
            });
            assertEquals(TaskResult.SUCCEEDED, await(handle).outcome());
            assertEquals(0, controlled.sessions.get());
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void partialOriginalAutostartRemainsUnknownAndNeverSendsUnattemptedComponents() throws Exception {
        isolatedOriginal("PARTIAL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENDPOINT", "FINGERPRINT", "MISSING_FACTS"})
    void changedOrMissingOriginalTargetRejectsBeforeAuthenticationAndNeverResends(String drift) throws Exception {
        isolatedOriginal(drift);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CURRENT_RELEASE", "DECLARATION"})
    void changedCurrentPublicationPreservesOriginalHostUnknownBeforeAuthenticationAndNeverResends(String drift) throws Exception {
        isolatedOriginal(drift);
    }

    private void isolatedOriginal(String scenario) throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(executable.toString(), "-cp", System.getProperty("java.class.path"), getClass().getName(),
            "--unknown", root.toString(), scenario).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(50, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("AUTOSTART_ORIGINAL_UNKNOWN_VERIFIED"), output);
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private void originalIdentityDrift(String drift) throws Exception {
        enable();
        var runtime = open();
        start(runtime, true);
        usageServer(runtime);
        var graph = lifecycleGraph(Backend.NATIVE, false);
        seedUsage(runtime, graph, 1);
        var controlled = new ManagedPeer(context(runtime), graph, false, false);
        controlled.loseAck = 2;
        var bound = bind(context(runtime), controlled);
        var failure = new AtomicReference<Throwable>();
        var handle = rootTask(runtime, task -> {
            try {
                var app = new DeploymentApplicationId("demo", graph.components().getFirst().unit().server());
                var snapshot = new DeploymentApplicationReader(bound.db()).find(app, task).snapshot();
                var manager = new DeploymentApplicationRunner(bound);
                assertEquals(TaskResult.UNKNOWN, manager.lifecycle(app, snapshot.revisionSha256(), DeploymentLifecycleAction.ENABLE_AUTOSTART, task).outcome());
                var journal = new DeploymentManagementActionLog(bound);
                var original = journal.find(task).orElseThrow();
                var target = DeploymentManagementActionLog.target(original, task);
                assertEquals(task.id(), target.taskId());
                assertEquals(Optional.of("SHA256:" + "A".repeat(43)), target.fingerprint());
                if (drift.equals("FINGERPRINT")) controlled.fingerprint = "SHA256:" + "B".repeat(43);
                else if (drift.equals("ENDPOINT")) {
                    assertThrows(java.sql.SQLException.class, () -> runtime.servers().saveServerProfile(
                        gold.debug.windowstolinux.app.service.server.ServerProfile.fromStored(new gold.debug.windowstolinux.app.db.server.StoredServerProfile(
                            "server", "2001:db8::2", 22, "tester", "fixture-secret")), "fixture-secret".toCharArray()));
                    // Simulate out-of-band metadata drift after proving ordinary rebinding remains protected. / 先证明普通换绑受保护，再模拟带外元数据变化。
                    try (var connection = runtime.persistence().database().openConnection();
                         var change = connection.prepareStatement("UPDATE server_profile SET host=? WHERE server_profile_id=?")) {
                        change.setString(1, "2001:db8::2");
                        change.setString(2, "server");
                        assertEquals(1, change.executeUpdate());
                    }
                } else {
                    var facts = DeploymentManagementActionLog.copy(original);
                    facts.remove("target");
                    journal.advance(original, "UNKNOWN", facts, task);
                }
                int connections = controlled.connects.get(), effects = controlled.effects.get();
                var repeated = manager.lifecycle(app, snapshot.revisionSha256(), DeploymentLifecycleAction.ENABLE_AUTOSTART, task);
                assertEquals(TaskResult.UNKNOWN, repeated.outcome());
                assertEquals(connections, controlled.connects.get(), "Changed or missing original target cannot authenticate another session");
                assertEquals(effects, controlled.effects.get());
                assertEquals(original.id(), journal.find(task).orElseThrow().id());
                assertEquals(task.id(), journal.find(task).orElseThrow().taskId());
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), repeated.problem());
            } catch (Exception | AssertionError error) {
                failure.set(error);
                throw error;
            }
        });
        awaitState(handle);
        if (failure.get() != null) throw new AssertionError("Original target fixture failed", failure.get());
        assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
        assertEquals(2, controlled.effects.get());
        assertEquals(0, controlled.sessions.get());
        assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, runtime::close).problem().code());
    }

    private void originalPublicationDrift(String drift) throws Exception {
        enable();
        var runtime = open();
        start(runtime, true);
        usageServer(runtime);
        var graph = lifecycleGraph(Backend.NATIVE, false);
        seedUsage(runtime, graph, 1);
        var controlled = new ManagedPeer(context(runtime), graph, false, false);
        controlled.loseAck = 2;
        var bound = bind(context(runtime), controlled);
        var service = new DeploymentApplicationsService(bound);
        var original = new AtomicReference<DeploymentManagementActionLog.Entry>();
        var failure = new AtomicReference<Throwable>();
        try (var recovery = bound.tasks().registerRecovery("applications-test", (previous, task) -> {
            try {
                var result = service.reconcile(previous, task);
                assertEquals(TaskResult.UNKNOWN, result.outcome());
                assertEquals(drift.equals("DECLARATION") ? "DEPLOYMENT_APPLICATION_DECLARATION_UNREVIEWED"
                    : "DEPLOYMENT_APPLICATION_REVISION_CHANGED", result.problem().orElseThrow().eventCode());
                var retained = new DeploymentManagementActionLog(bound).find(task).orElseThrow();
                assertEquals(original.get().id(), retained.id());
                assertEquals(previous.id(), retained.taskId());
                assertEquals(original.get().facts(), retained.facts());
                assertEquals("UNKNOWN", retained.phase());
                return result;
            } catch (Exception | AssertionError error) {
                failure.set(error);
                throw error;
            }
        })) {
            var handle = rootTask(runtime, task -> {
                try {
                    var app = new DeploymentApplicationId("demo", graph.components().getFirst().unit().server());
                    var snapshot = new DeploymentApplicationReader(bound.db()).find(app, task).snapshot();
                    var result = new DeploymentApplicationRunner(bound).lifecycle(app, snapshot.revisionSha256(),
                        DeploymentLifecycleAction.ENABLE_AUTOSTART, task);
                    assertEquals(TaskResult.UNKNOWN, result.outcome());
                    original.set(new DeploymentManagementActionLog(bound).find(task).orElseThrow());
                    // Inject current publication drift inside the original admitted task before it yields UNKNOWN. / 在原准入任务返回UNKNOWN前注入当前发布变化。
                    publishUsage(runtime, drift.equals("DECLARATION") ? withoutReview(graph) : graph, 2, true, task);
                    return new OperationResult<Void>(result.outcome(), Optional.empty(), result.problem());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                }
            });
            awaitState(handle);
            if (failure.get() != null) throw new AssertionError("Original publication fixture failed", failure.get());
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertEquals(handle.id(), original.get().taskId());
            assertEquals("UNKNOWN", original.get().phase());
            int connections = controlled.connects.get(), effects = controlled.effects.get();
            var retained = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(15, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError("Actual original recovery fixture failed", failure.get());
            assertEquals(handle.id(), retained.id());
            assertEquals(TaskState.UNKNOWN, retained.state());
            assertEquals(connections, controlled.connects.get(), "Current publication drift cannot authenticate another session");
            assertEquals(effects, controlled.effects.get());
            assertEquals(2, effects);
            assertEquals(0, controlled.sessions.get());
            assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, runtime::close).problem().code());
        }
    }

    private void acknowledgedTarget(int lostOrdinal, boolean preserveUnknownHost) throws Exception {
        enable();
        var runtime = open();
        try {
            start(runtime, true);
            usageServer(runtime);
            var graph = lifecycleGraph(Backend.NATIVE, false);
            seedUsage(runtime, graph, 1);
            var controlled = new ManagedPeer(context(runtime), graph, false, false);
            controlled.loseAck = lostOrdinal;
            var bound = bind(context(runtime), controlled);
            var failure = new AtomicReference<Throwable>();
            var handle = rootTask(runtime, task -> {
                try {
                    var app = new DeploymentApplicationId("demo", graph.components().getFirst().unit().server());
                    var initial = new DeploymentApplicationReader(bound.db()).find(app, task).snapshot();
                    var manager = new DeploymentApplicationRunner(bound);
                    assertEquals(TaskResult.UNKNOWN, manager.lifecycle(app, initial.revisionSha256(), DeploymentLifecycleAction.ENABLE_AUTOSTART, task).outcome());
                    var original = new DeploymentManagementActionLog(bound).find(task).orElseThrow();
                    assertEquals("UNKNOWN", original.phase());
                    int effects = controlled.effects.get();
                    var repeated = manager.lifecycle(app, initial.revisionSha256(), DeploymentLifecycleAction.ENABLE_AUTOSTART, task);
                    assertEquals(lostOrdinal == 2 ? TaskResult.SUCCEEDED : TaskResult.UNKNOWN, repeated.outcome());
                    assertEquals(effects, controlled.effects.get());
                    if (lostOrdinal == 2) {
                        assertTrue(repeated.value().orElseThrow().evidence().contains("target state verified"));
                        var verified = new DeploymentManagementActionLog(bound).find(task).orElseThrow();
                        assertEquals(original.id(), verified.id());
                        assertTrue(verified.facts().path("reconciledReadOnly").asBoolean());
                    }
                    return new OperationResult<Void>(repeated.outcome(), Optional.empty(), repeated.problem());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                }
            });
            awaitState(handle);
            if (failure.get() != null) throw new AssertionError("Actual autostart contract failed", failure.get());
            assertEquals(0, controlled.sessions.get());
            assertEquals(lostOrdinal, controlled.effects.get());
            if (preserveUnknownHost) {
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, runtime::close).problem().code());
            } else {
                assertEquals(TaskResult.SUCCEEDED, await(handle).outcome());
                assertNoActiveResources(runtime);
            }
        } finally {
            if (!preserveUnknownHost) runtime.close();
        }
    }

    public static void main(String[] args) {
        if (args.length != 3 || !args[0].equals("--unknown") || !Set.of("PARTIAL", "ENDPOINT", "FINGERPRINT", "MISSING_FACTS", "CURRENT_RELEASE", "DECLARATION").contains(args[2]))
            throw new IllegalArgumentException("Original unknown child only");
        try {
            var probe = new DeploymentApplicationAutostartTest();
            probe.root = Path.of(args[1]);
            if (args[2].equals("PARTIAL")) probe.acknowledgedTarget(1, true);
            else if (Set.of("CURRENT_RELEASE", "DECLARATION").contains(args[2]))
                probe.originalPublicationDrift(args[2]);
            else probe.originalIdentityDrift(args[2]);
            System.out.println("AUTOSTART_ORIGINAL_UNKNOWN_VERIFIED");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}

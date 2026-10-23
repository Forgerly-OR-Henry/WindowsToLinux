package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Event;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentMultiPreparationCompensation;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentPrepareNotSentException;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Preparation pairing and persisted public proof cannot be replaced by candidate absence or an installed helper. */
class DeploymentAgentPreparationRecoveryTest extends DeploymentRuntimeFixture {
    private static final String SOURCE = "b".repeat(64);

    private static final String ARCHIVE = "c".repeat(64);

    private static final String REVISION = "a".repeat(64);

    private static final DeploymentAgentActionLog.Identity IDENTITY = new DeploymentAgentActionLog.Identity("server", "d".repeat(64), REVISION, "FULL_CONTROL", true);

    @Test
    void multiPreparationProgressUsesHostRecoveryCheckpointAndReleasesOnlyAfterProof() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            assertTrue(actual.tasks().getClass().getName().endsWith("PlatformModContext$RecoveryTasks"));
            var layout = DeploymentRemoteLayout.original();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var legacy = actual.tasks().submit(new TaskRequest("checkpoint-legacy-conflict", "Reject a retained author checkpoint", List.of(), false), original -> {
                try (var checkpoint = actual.tasks().beginCheckpoint(original, DeploymentAgentMultiPreparationCompensation.KEY, "{}")) {
                    var captured = new DeploymentAgentMultiPreparationCompensation.Captured(original.id(),
                        new DeploymentAgentActionLog.Snapshot(IDENTITY, "UNKNOWN", 4), layout, List.of(), List.of());
                    var failure = assertThrows(IllegalStateException.class,
                        () -> DeploymentAgentMultiPreparationCompensation.Progress.open(actual, original, captured));
                    assertEquals("Original author checkpoint cannot become recovery progress", failure.getMessage());
                    assertFalse(checkpoint.snapshot().released());
                }
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED, legacy.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            var context = new java.util.concurrent.atomic.AtomicReference<TaskContext>();
            var originalLease = new java.util.concurrent.atomic.AtomicReference<ResourceLease>();
            var originalCalls = new java.util.concurrent.atomic.AtomicInteger();
            var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var journal = new DeploymentAgentActionLog(actual.db());
            var handle = actual.tasks().submit(new TaskRequest("checkpoint-host-integration", "Retain a synthetic original candidate set", List.of(), false), original -> {
                originalCalls.incrementAndGet();
                context.set(original);
                originalLease.set(original.retain("file", "synthetic-original", "Controlled original resource"));
                DeploymentConfigCapture.remoteLayout(actual.tasks(), original, layout);
                journal.create(original, IDENTITY, Optional.of("f".repeat(64)));
                var events = DeploymentAgentPreparationCompensationTest.multipleEvents(original.id(), IDENTITY, layout, "e".repeat(64), "f".repeat(64));
                for (var event : events.subList(2, events.size()))
                    journal.event(original, event.type(), event.action(), event.detail());
                journal.state(original, "UNKNOWN");
                throw new java.io.IOException("Controlled original business failure");
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (handle.snapshot().state() != gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN, handle.snapshot().state());
            try (var registered = actual.tasks().registerRecovery("checkpoint-host-integration", (previous, original) -> {
                try {
                    assertSame(context.get(), original);
                    assertTrue(runtime.tasks().reconciling(original));
                    var captured = DeploymentAgentMultiPreparationCompensation.capture(original.id(), journal.find(original).orElseThrow(), layout, journal.events(original)).orElseThrow();
                    assertTrue(actual.tasks().findRecoveryCheckpoint(original, DeploymentAgentMultiPreparationCompensation.KEY).isEmpty());
                    assertThrows(IllegalStateException.class,
                        () -> DeploymentAgentMultiPreparationCompensation.Progress.open(actual, original, captured, true));
                    assertTrue(actual.tasks().findRecoveryCheckpoint(original, DeploymentAgentMultiPreparationCompensation.KEY).isEmpty(), "A missing proof checkpoint must not be fabricated");
                    var progress = DeploymentAgentMultiPreparationCompensation.Progress.open(actual, original, captured);
                    var checkpoint = actual.tasks().findRecoveryCheckpoint(original, DeploymentAgentMultiPreparationCompensation.KEY).orElseThrow();
                    assertThrows(IllegalArgumentException.class, progress::proof);
                    assertFalse(checkpoint.snapshot().released());
                    for (int index = 0; index < captured.members().size(); index++) {
                        var member = captured.members().get(index);
                        // Public, synthetic receipts exercise the client consumer, not a Linux or SSH execution.
                        var absent = new CommandResult(Optional.of(0), DeploymentAgentPartialPreparationProbe.absentOutput(captured.view(member)), "", false);
                        var before = member.pending() ? absent : new CommandResult(Optional.of(0), "CANDIDATE_STATE=absent\nBUILD_ACTIVE=no\n", "", false);
                        assertFalse(progress.before(member, before));
                        progress.after(member, absent);
                        assertFalse(checkpoint.snapshot().released());
                        if (index + 1 < captured.members().size())
                            assertThrows(IllegalArgumentException.class, progress::proof);
                    }
                    String proof = progress.proof();
                    assertEquals(captured.members().size(), json.readTree(proof).path("members").size());
                    assertTrue(journal.events(original).stream().noneMatch(e -> e.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT)));
                    assertFalse(checkpoint.snapshot().released(), "Complete absence alone is not a durable Mod proof");
                    journal.preparationsCompensated(original, captured, progress, proof);
                    var retained = journal.events(original);
                    var event = retained.stream().filter(e -> e.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT)).findFirst().orElseThrow();
                    assertTrue(DeploymentAgentMultiPreparationCompensation.matches(event, retained));
                    assertFalse(checkpoint.snapshot().released(), "Committing the proof does not implicitly release the host occupancy");
                    progress.release(event, retained);
                    assertTrue(checkpoint.snapshot().released());
                    var retainedProof = DeploymentAgentMultiPreparationCompensation.retained(original.id(), captured.saved(), layout, retained).orElseThrow();
                    var reopened = DeploymentAgentMultiPreparationCompensation.Progress.open(actual, original, retainedProof, true);
                    reopened.release(event, retained);
                    assertEquals(retained, journal.events(original), "Reopening a committed proof must not append or repeat cleanup");
                    assertFalse(actual.tasks().resourcesReleased(original));
                    originalLease.get().close();
                    assertTrue(actual.tasks().resourcesReleased(original));
                    journal.event(original, "AI_RECOVERY_SETTLED", original.id().toString(), "FAILED:COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED");
                    journal.state(original, "FAILED");
                    assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(original)));
                    return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                        ProblemCode.IO_FAILURE, "FIXTURE_ORIGINAL_FAILED", "Original failure retained after synthetic absence", Map.of(), Optional.empty())));
                } catch (Exception | AssertionError failure) {
                    callbackFailure.set(failure);
                    throw failure;
                }
            })) {
                var ended = reconcileReady(runtime, handle);
                if (callbackFailure.get() != null)
                    throw new AssertionError("Actual host/Mod recovery checkpoint failed", callbackFailure.get());
                assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED, ended.state());
                assertEquals(TaskResult.FAILED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            }
            assertEquals(1, originalCalls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingPreparationOrProofWithoutReceiptBlocksAllRemoteRecovery(boolean proofOnly) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(), new Class<?>[]{LinuxService.class},
                (proxy, method, args) -> {
                    throw new AssertionError("Pending preparation must not query, probe or clean any remote object");
                });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("linux")) return linux;
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var checked = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var finished = new java.util.concurrent.CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-preparation-gate-local", "Read exact unresolved preparation boundary", List.of(), false), task -> {
                try {
                    var journal = new DeploymentAgentActionLog(actual.db());
                    journal.create(task, IDENTITY);
                    var layout = DeploymentRemoteLayout.original();
                    DeploymentConfigCapture.remoteLayout(actual.tasks(), task, layout);
                    DeploymentAgentLayouts.record(journal, task, new ServerId(IDENTITY.server()), layout);
                    journal.event(task, "SOURCE", ARCHIVE, SOURCE);
                    journal.event(task, "PLAN_APPLICATION", "", "demo");
                    journal.event(task, "PREPARE_EXECUTING", "original-prepare", "api");
                    if (proofOnly)
                        journal.event(task, "PREPARE_DISPATCH_NOT_SENT", "original-prepare", proof(task.id(), "api", SOURCE));
                    journal.state(task, "UNKNOWN");
                    var before = journal.events(task);
                    var result = new DeploymentAgentRecovery(context, journal).reconcile(task, journal.find(task).orElseThrow());
                    assertEquals(TaskResult.UNKNOWN, result.outcome());
                    assertEquals("DEPLOYMENT_AGENT_ORIGINAL_PREPARATION_UNCONFIRMED", result.problem().orElseThrow().eventCode());
                    assertEquals(before, journal.events(task), "Read-only rejection cannot backfill an old missing receipt");
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failure) {
                    checked.set(failure);
                    throw failure;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(15, TimeUnit.SECONDS);
            if (checked.get() != null) throw new AssertionError("Preparation gate fixture failed", checked.get());
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
        }
    }

    @Test
    void originalWorkspaceExclusionRequiresCompleteIdentityAndKeepsAnyActuallyPreparedComponent() {
        UUID task = UUID.randomUUID();
        var events = new ArrayList<Event>(List.of(new Event(1, "SOURCE", ARCHIVE, SOURCE), new Event(2, "PLAN_APPLICATION", "", "demo"),
            new Event(3, "PREPARE_EXECUTING", "prepare", "api"), new Event(4, "PREPARE_DISPATCH_NOT_SENT", "prepare", proof(task, "api", SOURCE))));
        var workspace = new DeploymentRemoteWorkspace("demo-api", ARCHIVE);
        assertEquals(List.of(workspace), DeploymentAgentRecovery.originalWorkspaces(task, IDENTITY, events).orElseThrow());
        events.add(new Event(5, "PREPARE_NOT_SENT", "prepare", DeploymentAgentPrepareNotSentException.DETAIL));
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        assertEquals(List.of(), DeploymentAgentRecovery.originalWorkspaces(task, IDENTITY, events).orElseThrow(), "Original public proof needs no installed helper");
        for (String wrongProof : List.of(proof(UUID.randomUUID(), "api", SOURCE), proof(task, "other", SOURCE), proof(task, "api", "f".repeat(64)),
            proof(task, "api", SOURCE).replace("requestSha256=" + "e".repeat(64), "requestSha256=invalid"))) {
            var changed = new ArrayList<>(events);
            changed.set(3, new Event(4, "PREPARE_DISPATCH_NOT_SENT", "prepare", wrongProof));
            assertTrue(DeploymentAgentRecovery.originalWorkspaces(task, IDENTITY, changed).isEmpty());
        }
        var missingProof = new ArrayList<>(events);
        missingProof.remove(3);
        assertTrue(DeploymentAgentRecovery.originalWorkspaces(task, IDENTITY, missingProof).isEmpty());
        var wrongAction = new ArrayList<>(events);
        wrongAction.set(4, new Event(5, "PREPARE_NOT_SENT", "other", DeploymentAgentPrepareNotSentException.DETAIL));
        assertTrue(DeploymentAgentRecovery.originalWorkspaces(task, IDENTITY, wrongAction).isEmpty());
        events.add(new Event(6, "PREPARE_EXECUTING", "second", "api"));
        events.add(new Event(7, "PREPARE_RESULT", "second", SOURCE));
        assertEquals(List.of(workspace), DeploymentAgentRecovery.originalWorkspaces(task, IDENTITY, events).orElseThrow());
        events.add(new Event(8, "CANDIDATE", "api", workspace.candidateId()));
        assertEquals(List.of(workspace), DeploymentAgentRecovery.originalWorkspaces(task, IDENTITY, events).orElseThrow());
    }

    @Test
    void verifiedMultiPreparationProofExcludesExactlyItsWholeCandidateSet() throws Exception {
        try (var f = new DeploymentAgentPreparationCompensationTest.Frame(DeploymentRemoteLayout.original(), true)) {
            var prefix = f.journal.events(f.task);
            assertEquals(TaskResult.FAILED, f.recover().outcome());
            var saved = f.journal.find(f.task).orElseThrow();
            var events = f.journal.events(f.task);
            var proof = events.stream().filter(e -> e.type().equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentMultiPreparationCompensation.EVENT)).findFirst().orElseThrow();
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            assertTrue(DeploymentAgentRecovery.originalWorkspaces(f.id, saved, events, f.layout).orElseThrow().isEmpty());
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var rejected = new ArrayList<List<Event>>();
            for (String wrong : List.of("MISSING_BACKEND", "FOREIGN_CANDIDATE", "FOREIGN_TASK", "QUOTA", "EXTRA", "TRAILING", "DUPLICATE_KEY")) {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(proof.detail());
                switch (wrong) {
                    case "MISSING_BACKEND" ->
                        ((com.fasterxml.jackson.databind.node.ArrayNode) node.path("members")).remove(0);
                    case "FOREIGN_CANDIDATE" ->
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("members").get(0)).put("candidate", "foreign");
                    case "FOREIGN_TASK" -> node.put("task", UUID.randomUUID().toString());
                    case "QUOTA" ->
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("members").get(0)).put("quotaSha256", "0".repeat(64));
                    case "EXTRA" -> node.put("authority", "APPROVE");
                    default -> {
                    }
                }
                String detail = wrong.equals("TRAILING") ? proof.detail() + " {}" : wrong.equals("DUPLICATE_KEY")
                    ? proof.detail().replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1") : node.toString();
                var changed = new ArrayList<>(events);
                changed.replaceAll(e -> e.equals(proof) ? new Event(e.id(), e.type(), e.action(), detail) : e);
                rejected.add(changed);
            }
            var missingPrefix = new ArrayList<>(events);
            missingPrefix.remove(prefix.getFirst());
            rejected.add(missingPrefix);
            var late = new ArrayList<>(events);
            late.add(new Event(events.getLast().id() + 1, "PLAN", "", "f".repeat(64)));
            rejected.add(late);
            var duplicate = new ArrayList<>(events);
            duplicate.add(new Event(events.getLast().id() + 1, proof.type(), proof.action(), proof.detail()));
            rejected.add(duplicate);
            for (var changed : rejected) {
                assertFalse(DeploymentAgentActionLog.effectsSettled(changed));
                assertTrue(DeploymentAgentRecovery.originalWorkspaces(f.id, saved, changed, f.layout).isEmpty());
            }
            assertEquals(events, f.journal.events(f.task));
            assertEquals(2, f.deletes);
        }
    }

    private static String proof(UUID task, String component, String source) {
        try {
            String server = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("server".getBytes(StandardCharsets.UTF_8)));
            return "agent-install-review-stop-v1 task=" + task + " component=" + component + " sourceSha256=" + source + " serverSha256=" + server
                + " revision=" + REVISION + " installerSha256=" + "d".repeat(64) + " requestSha256=" + "e".repeat(64);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}

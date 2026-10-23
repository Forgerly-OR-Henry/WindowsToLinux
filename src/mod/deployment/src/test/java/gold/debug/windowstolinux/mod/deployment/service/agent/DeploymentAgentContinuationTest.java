package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;

/** Real JDBC and authentic task admission; constructed failure facts test persistence, not publication. */
class DeploymentAgentContinuationTest extends DeploymentAgentArchiveFixture {
    @Override
    protected gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts interactionPrompts() {
        var original = super.interactionPrompts();
        return (gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts) java.lang.reflect.Proxy.newProxyInstance(
            original.getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts.class},
            (proxy, method, args) -> {
                if (method.getName().equals("approve")) {
                    assertEquals("deployment.workflow.authority", ((gold.debug.windowstolinux.appapi.task.action.ActionIntent) args[0]).type());
                    return true; // Fresh local inspection handoff only; no command or provider is sent.
                }
                try {
                    return method.invoke(original, args);
                } catch (java.lang.reflect.InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
    }

    static final DeploymentAgentInputs.Request REQUEST = new DeploymentAgentInputs.Request(Map.of("goal", "Same deployment"),
        "MANUAL_REVIEW", true, 1024, 10, 1024);

    static final DeploymentAgentGoalBudget.Snapshot ALLOWANCE = new DeploymentAgentGoalBudget.Snapshot(20, 3, 1, 100, 8, 5,
        17, 7201, new DeploymentBuildBudget(60).snapshot());

    static OperationResult<Void> failed() {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "CONTROLLED_NATIVE_FAILURE", "Constructed persistence evidence", Map.of(), Optional.empty())));
    }

    DeploymentAgentContinuation.Intent failure(PlatformRuntime runtime, TaskContext task, boolean cleanup) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var log = new DeploymentAgentActionLog(context.db());
        log.create(task, agentIdentity());
        var execution = new DeploymentExecutionIdentity(UUID.randomUUID(), task.id(), Optional.empty(), "application",
            new gold.debug.windowstolinux.appapi.server.model.ServerId("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64));
        var store = new DeploymentExecutionStore(context.db());
        store.begin(task, execution, "deployment-agent", List.of(intent()), Optional.empty(), OBSERVED);
        var reference = context.servers().reserve(execution.server(), intent().businessKey(), task);
        store.recordReference(task, execution.executionId(), intent(), reference.id(), OBSERVED);
        store.startEffects(task, execution.executionId(), OBSERVED.plusSeconds(1));
        store.recordUncommittedOutcome(task, execution.executionId(), TaskResult.FAILED, failed().problem().orElseThrow(), OBSERVED.plusSeconds(2));
        context.servers().resolve(reference.id(), gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult.NOT_REFERENCED, task);
        if (cleanup) store.finishCleanup(task, execution.executionId(), OBSERVED.plusSeconds(3));
        String accepted = "{\"formatVersion\":1,\"execution\":\"" + execution.executionId() + "\",\"executionIdentity\":\"" + DeploymentAgentInputs.hash(execution.toString()) + "\"}";
        log.event(task, "AI_PUBLICATION_FAILED_ACCEPTED", "application", accepted);
        if (cleanup) log.event(task, "AI_PUBLICATION_FAILED_CLEANED", "application", "ABSENT");
        log.state(task, "FAILED");
        var layout = DeploymentRemoteLayout.original();
        return new DeploymentAgentContinuation.Intent(task.id(), task.id(), UUID.randomUUID(), UUID.randomUUID(), 2,
            "application", "server", "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), layout.canonical(), layout.digest(),
            REQUEST, new DeploymentAgentContinuation.Source(UUID.randomUUID(), UUID.randomUUID(), "e".repeat(64), "f".repeat(64), 1, 1),
            Map.of(), accepted, "FAILED; rollback=RESTORED", ALLOWANCE);
    }

    TaskHandle<Void> submit(PlatformRuntime runtime, TaskWork<Void> work, AtomicReference<Throwable> error) throws Exception {
        return submit(runtime, work, error, List.of("server"));
    }

    TaskHandle<Void> submit(PlatformRuntime runtime, TaskWork<Void> work, AtomicReference<Throwable> error, List<String> servers) throws Exception {
        return runtime.modContexts().get(OWNER).context().tasks().submit(new TaskRequest("deployment-agent", "Authentic persistence fixture", servers, true), task -> {
            try {
                return work.run(task);
            } catch (Exception | AssertionError failure) {
                error.set(failure);
                throw failure;
            }
        });
    }

    static void await(TaskHandle<Void> handle, AtomicReference<Throwable> error, TaskResult expected) throws Exception {
        OperationResult<Void> result;
        try {
            result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Unfinished fixture: " + handle.snapshot(), error.get() == null ? timeout : error.get());
        }
        if (error.get() != null) throw new AssertionError("Authentic fixture callback failed", error.get());
        assertEquals(expected, result.outcome(), result.toString());
    }

    @Test
    void realFailedArchiveAllowsExactlyOneConcurrentClaimAndKeepsFrozenChargesAfterReopen() throws Exception {
        enable();
        var intent = new AtomicReference<DeploymentAgentContinuation.Intent>();
        UUID winner;
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var error = new AtomicReference<Throwable>();
            var original = submit(runtime, task -> {
                var log = new DeploymentAgentActionLog(runtime.modContexts().get(OWNER).context().db());
                intent.set(failure(runtime, task, true));
                assertEquals(log.reserveSuccessor(task, intent.get()), log.reserveSuccessor(task, intent.get()));
                var changed = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(intent.get());
                ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("operation", UUID.randomUUID().toString());
                var conflict = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(changed, DeploymentAgentContinuation.Intent.class);
                assertThrows(Exception.class, () -> log.reserveSuccessor(task, conflict));
                assertEquals("READY", log.readySuccessor(task, ALLOWANCE).phase());
                return failed();
            }, error);
            await(original, error, TaskResult.FAILED);
            var start = new CountDownLatch(2);
            var claims = new AtomicInteger();
            var refusals = new AtomicInteger();
            var selected = new AtomicReference<UUID>();
            var children = new ArrayList<TaskHandle<Void>>();
            for (int index = 0; index < 2; index++)
                children.add(submit(runtime, task -> {
                    start.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var context = runtime.modContexts().get(OWNER).context();
                    var log = new DeploymentAgentActionLog(context.db());
                    try {
                        var claim = log.claimSuccessor(task, intent.get(), context.tasks());
                        assertTrue(claim.first());
                        claims.incrementAndGet();
                        selected.set(task.id());
                        assertFalse(log.claimSuccessor(task, intent.get(), context.tasks()).first());
                        assertEquals(ALLOWANCE, claim.ticket().settledAllowance());
                    } catch (java.sql.SQLException refused) {
                        refusals.incrementAndGet();
                    }
                    return success();
                }, error, List.of())); // Admission-only probes use DB/task capabilities; SDK server work itself is serial.
            for (var child : children) await(child, error, TaskResult.SUCCEEDED);
            assertEquals(1, claims.get());
            assertEquals(1, refusals.get());
            winner = selected.get();
            assertEquals(TaskResult.FAILED, original.snapshot().outcome().orElseThrow());
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var log = new DeploymentAgentActionLog(context.db());
                assertEquals(winner, log.successor(task, original.id()).orElseThrow().child());
                assertEquals(1, log.events(task, original.id()).stream().filter(event -> event.type().equals("CONTINUATION_CLAIMED")).count());
                assertThrows(Exception.class, () -> log.reserveSuccessor(task, intent.get()));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM managed_application"));
                return success();
            });
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var log = new DeploymentAgentActionLog(runtime.modContexts().get(OWNER).context().db());
                var saved = log.successor(task, intent.get().predecessor()).orElseThrow();
                assertEquals("CLAIMED", saved.phase());
                assertEquals(winner, saved.child());
                assertEquals(ALLOWANCE, saved.settledAllowance());
                assertThrows(Exception.class, () -> log.claimSuccessor(task, intent.get(), runtime.modContexts().get(OWNER).context().tasks()));
                return success();
            });
        }
    }

    @Test
    void incompleteCleanupPendingEffectsAndBudgetRefundNeverBecomeReady() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var error = new AtomicReference<Throwable>();
            await(submit(runtime, task -> {
                var log = new DeploymentAgentActionLog(runtime.modContexts().get(OWNER).context().db());
                var intent = failure(runtime, task, false);
                log.reserveSuccessor(task, intent);
                assertTrue(assertThrows(Exception.class, () -> log.readySuccessor(task, ALLOWANCE)).getMessage().contains("cleanup remains unconfirmed"));
                var store = new DeploymentExecutionStore(runtime.modContexts().get(OWNER).context().db());
                store.finishCleanup(task, store.findForTask(task).orElseThrow().identity().executionId(), OBSERVED.plusSeconds(3));
                log.event(task, "AI_PUBLICATION_FAILED_CLEANED", "application", "ABSENT");
                log.event(task, "COMMAND_EXECUTING", "later-command", "dispatched");
                assertTrue(assertThrows(Exception.class, () -> log.readySuccessor(task, ALLOWANCE)).getMessage().contains("cleanup remains unconfirmed"));
                log.event(task, "COMMAND_RESULT", "later-command", "known");
                var refund = new DeploymentAgentGoalBudget.Snapshot(20, 2, 1, 100, 8, 5, 17, 7201, ALLOWANCE.build());
                assertThrows(Exception.class, () -> log.readySuccessor(task, refund));
                assertEquals("RESERVED", log.successor(task, task.id()).orElseThrow().phase());
                log.blockSuccessor(task, task.id());
                assertThrows(Exception.class, () -> log.readySuccessor(task, ALLOWANCE));
                return failed();
            }, error), error, TaskResult.FAILED);
        }
    }

    @Test
    void readyCrashGapCannotClaimBeforeOriginalArchiveAndClaimBindingCannotBeForged() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var intent = new AtomicReference<DeploymentAgentContinuation.Intent>();
            var ready = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var original = submit(runtime, task -> {
                var log = new DeploymentAgentActionLog(runtime.modContexts().get(OWNER).context().db());
                intent.set(failure(runtime, task, true));
                log.reserveSuccessor(task, intent.get());
                log.readySuccessor(task, ALLOWANCE);
                ready.countDown();
                assertTrue(finish.await(15, TimeUnit.SECONDS));
                return failed();
            }, error);
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            await(submit(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                assertThrows(Exception.class, () -> new DeploymentAgentActionLog(context.db()).claimSuccessor(task, intent.get(), context.tasks()));
                return success();
            }, error, List.of()), error, TaskResult.SUCCEEDED);
            finish.countDown();
            await(original, error, TaskResult.FAILED);
            await(submit(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var log = new DeploymentAgentActionLog(context.db());
                assertTrue(log.claimSuccessor(task, intent.get(), context.tasks()).first());
                log.create(task, agentIdentity(), Optional.of(intent.get().physicalTarget()), Optional.of(intent.get()));
                log.event(task, "SOURCE", "f".repeat(64), intent.get().source().source());
                assertEquals(5, log.find(task).orElseThrow().executionSemantics());
                try (var connection = context.db().openConnection(task); var remove = connection.prepareStatement("DELETE FROM preference WHERE preference_key=?")) {
                    remove.setString(1, DeploymentAgentContinuation.TICKET_PREFIX + original.id());
                    assertEquals(1, remove.executeUpdate());
                }
                assertThrows(Exception.class, () -> log.find(task));
                return success();
            }, error), error, TaskResult.SUCCEEDED);
        }
    }

    @Test
    void actualHistoryReaderPassesCompleteFiveSnapshotToNonceDecoderAndRefusesMissingBindingsBeforeConnection() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            try (var operation = runtime.security().enterOperation(); var credentials = runtime.security().openCredentials()) {
                credentials.save("ai/history-metadata", "fixture-only".toCharArray());
            }
            runtime.persistence().aiProfiles().saveVerified(new gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile("history-metadata",
                "https://example.invalid/v1/chat/completions", "fixture", "ai/history-metadata"), "Local metadata only", java.time.Instant.now());
            for (var purpose : List.of(gold.debug.windowstolinux.app.ai.model.AiPurposeType.DEPLOYMENT, gold.debug.windowstolinux.app.ai.model.AiPurposeType.APPROVAL))
                runtime.persistence().aiProfiles().purposes().save(purpose, List.of(new gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment("history-metadata")));
            for (int semantics : List.of(4, 5))
                run(runtime, task -> {
                    var actual = runtime.modContexts().get(OWNER).context();
                    var connects = new AtomicInteger();
                    var linux = java.lang.reflect.Proxy.newProxyInstance(actual.linux().getClass().getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class.getMethod("linux").getReturnType()}, (proxy, method, args) -> {
                            if (method.getName().equals("connect")) {
                                connects.incrementAndGet();
                                throw new ApiException(new Problem(ProblemCode.IO_FAILURE,
                                    "CONTROLLED_CONNECTOR_BOUNDARY", "Inspection metadata admitted; transport intentionally unavailable", Map.of(), Optional.empty()));
                            }
                            throw new AssertionError("No Linux action expected: " + method.getName());
                        });
                    var context = (gold.debug.windowstolinux.appapi.mod.ModContext) java.lang.reflect.Proxy.newProxyInstance(actual.getClass().getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class}, (proxy, method, args) -> {
                            if (method.getName().equals("linux")) return linux;
                            try {
                                return method.invoke(actual, args);
                            } catch (java.lang.reflect.InvocationTargetException failed) {
                                throw failed.getCause();
                            }
                        });
                    var reader = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader(context);
                    String revision = actual.ai().configurationRevision(gold.debug.windowstolinux.appapi.ai.model.AiPurpose.LANGUAGE, task);
                    var identity = new DeploymentAgentActionLog.Identity("server", DeploymentAgentWorkspaceBindingTest.IDENTITY.targetDigest(), revision, "MANUAL_REVIEW", true);
                    {
                        var events = DeploymentAgentWorkspaceBindingTest.prefix(semantics);
                        if (semantics == 5) {
                            var old = events.getFirst();
                            var binding = DeploymentAgentWorkspaceBindingTest.BINDING;
                            var current = new DeploymentAgentWorkspaceBinding.Binding(binding.root(), binding.predecessor(), binding.ticket(), binding.operation(),
                                binding.application(), binding.source(), binding.layout(), binding.target(), revision, binding.ticketSha256());
                            events.set(0, new DeploymentAgentActionLog.Event(old.id(), old.type(), old.action(), current.detail(DeploymentAgentWorkspaceBindingTest.TASK, "server")));
                        }
                        for (int index = 0; index < events.size(); index++)
                            if (events.get(index).type().equals("TARGET_PROFILE")) {
                                var old = events.get(index);
                                events.set(index, new DeploymentAgentActionLog.Event(old.id(), old.type(), old.action(),
                                    DeploymentAgentInputs.targetProfile(actual.servers().get(new gold.debug.windowstolinux.appapi.server.model.ServerId("server"), task))));
                            }
                        var saved = new DeploymentAgentActionLog.Snapshot(identity, "UNKNOWN", semantics);
                        var workspaces = DeploymentAgentWorkspaceBindingTest.recovery(saved, events).orElseThrow();
                        assertEquals(semantics == 5 ? Optional.of(DeploymentAgentWorkspaceBindingTest.BINDING.operation()) : Optional.empty(), workspaces.getFirst().operation());
                        var detail = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader.Detail(
                            new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader.Entry(DeploymentAgentWorkspaceBindingTest.TASK,
                                new gold.debug.windowstolinux.appapi.server.model.ServerId("server"), "UNKNOWN", OBSERVED), saved, events);
                        assertEquals("CONTROLLED_CONNECTOR_BOUNDARY", assertThrows(ApiException.class, () -> reader.inspect(task, detail, false)).problem().eventCode());
                        assertEquals(1, connects.get());
                        if (semantics == 5) {
                            var missing = new ArrayList<>(events);
                            missing.removeFirst();
                            var invalid = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader.Detail(detail.entry(), saved, missing);
                            assertThrows(Exception.class, () -> reader.inspect(task, invalid, false));
                            assertEquals(1, connects.get());
                            var mismatched = new ArrayList<>(events);
                            var binding = mismatched.getFirst();
                            mismatched.set(0, new DeploymentAgentActionLog.Event(binding.id(), binding.type(), binding.action(), binding.detail().replace(DeploymentAgentWorkspaceBindingTest.BINDING.source(), "0".repeat(64))));
                            assertThrows(Exception.class, () -> reader.inspect(task, new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentHistoryReader.Detail(detail.entry(), saved, mismatched), false));
                            assertEquals(1, connects.get());
                        }
                    }
                    return success();
                });
        }
    }
}

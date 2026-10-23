package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retains exact command and patch completion proof across real CLASS/H2 reopen. / 跨真实CLASS/H2重开保留精确命令与补丁完成证明。 */
class DeploymentAgentEffectEvidenceTest extends DeploymentRuntimeFixture {
    @org.junit.jupiter.api.Test
    void originalDeliveryRecoveryProofSettlesOnlyItsExactAttempt() {
        var events = new ArrayList<DeploymentAgentActionLog.Event>();
        String action = "a".repeat(64);
        events.add(new DeploymentAgentActionLog.Event(1, "DELIVERY_EXECUTING", action, "2"));
        var prefix = gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.object().putArray("events");
        events.forEach(e -> prefix.addObject().put("id", e.id()).put("type", e.type()).put("action", e.action()).put("detail", e.detail()));
        String hash = gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(prefix.toString());
        var proof = new DeploymentAgentActionLog.OriginalDeliveryProof(1, action, 2, UUID.randomUUID(), UUID.randomUUID(), "server", "application",
            action, action, action, action, action, action, action, UUID.randomUUID().toString(), action, action, hash);
        var settled = new DeploymentAgentActionLog.Event(2, DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED, action, proof.detail());
        assertFalse(DeploymentAgentActionLog.effectsSettled(events));
        for (String bad : List.of(proof.detail().replace("\"eventId\":1", "\"eventId\":9"),
            proof.detail().replace("\"planRevision\":2", "\"planRevision\":3"),
            proof.detail().replace("\"formatVersion\":1", "\"formatVersion\":2"),
            proof.detail().replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            proof.detail().replace(hash, "b".repeat(64)), proof.detail() + "{}")) {
            var invalid = new ArrayList<>(events);
            invalid.add(new DeploymentAgentActionLog.Event(2, settled.type(), action, bad));
            assertFalse(DeploymentAgentActionLog.effectsSettled(invalid), bad);
        }
        events.add(settled);
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        var duplicate = new ArrayList<>(events);
        duplicate.add(new DeploymentAgentActionLog.Event(3, settled.type(), action, proof.detail()));
        assertFalse(DeploymentAgentActionLog.effectsSettled(duplicate));
        events.add(new DeploymentAgentActionLog.Event(3, "DELIVERY_EXECUTING", action, "2"));
        assertFalse(DeploymentAgentActionLog.effectsSettled(events), "A proof cannot consume a later same-action attempt");
        var other = new ArrayList<>(List.of(new DeploymentAgentActionLog.Event(1, "COMMAND_EXECUTING", action, "2"), settled));
        assertFalse(DeploymentAgentActionLog.effectsSettled(other));
    }

    @org.junit.jupiter.api.Test
    void installReviewReceiptSettlesOnlyExactPreparationAttemptAndNeverOtherKinds() {
        var events = new ArrayList<DeploymentAgentActionLog.Event>();
        events.add(new DeploymentAgentActionLog.Event(1, "PREPARE_EXECUTING", "original", "api"));
        events.add(new DeploymentAgentActionLog.Event(2, "COMMAND_NOT_SENT", "original", "INSTALL_REVIEW_STOPPED_NOT_SENT"));
        events.add(new DeploymentAgentActionLog.Event(3, "PREPARE_NOT_SENT", "other", "INSTALL_REVIEW_STOPPED_NOT_SENT"));
        events.add(new DeploymentAgentActionLog.Event(4, "PREPARE_NOT_SENT", "original", "UNCONFIRMED"));
        events.add(new DeploymentAgentActionLog.Event(5, "PREPARE_DISPATCH_NOT_SENT", "original", "proof without paired receipt"));
        assertFalse(DeploymentAgentActionLog.effectsSettled(events));
        events.add(new DeploymentAgentActionLog.Event(6, "PREPARE_NOT_SENT", "original", "INSTALL_REVIEW_STOPPED_NOT_SENT"));
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        events.add(new DeploymentAgentActionLog.Event(7, "PREPARE_EXECUTING", "original", "new attempt"));
        assertFalse(DeploymentAgentActionLog.effectsSettled(events), "An older receipt cannot settle a later attempt or historic pending preparation");
        for (String kind : List.of("COMMAND", "CAPABILITY", "PATCH", "SEAL", "DELIVERY"))
            assertFalse(DeploymentAgentActionLog.effectsSettled(List.of(
                new DeploymentAgentActionLog.Event(1, kind + "_EXECUTING", "original", "attempt"),
                new DeploymentAgentActionLog.Event(2, kind + "_NOT_SENT", "original", "INSTALL_REVIEW_STOPPED_NOT_SENT"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMAND", "CAPABILITY"})
    void stoppedReviewReceiptSettlesOnlyItsExactNewDispatcherAttempt(String kind) {
        String other = kind.equals("COMMAND") ? "CAPABILITY" : "COMMAND";
        var events = new ArrayList<DeploymentAgentActionLog.Event>();
        events.add(new DeploymentAgentActionLog.Event(1, kind + "_EXECUTING", "original", "attempt"));
        events.add(new DeploymentAgentActionLog.Event(2, other + "_NOT_SENT", "original", "REVIEW_STOPPED_NOT_SENT"));
        events.add(new DeploymentAgentActionLog.Event(3, kind + "_NOT_SENT", "other", "REVIEW_STOPPED_NOT_SENT"));
        events.add(new DeploymentAgentActionLog.Event(4, kind + "_NOT_SENT", "original", "UNCONFIRMED"));
        events.add(new DeploymentAgentActionLog.Event(5, kind + "_DISPATCH_NOT_SENT", "original", "public digest proof"));
        assertFalse(DeploymentAgentActionLog.effectsSettled(events));
        events.add(new DeploymentAgentActionLog.Event(6, kind + "_NOT_SENT", "original", "REVIEW_STOPPED_NOT_SENT"));
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        events.add(new DeploymentAgentActionLog.Event(7, kind + "_EXECUTING", "original", "new attempt"));
        assertFalse(DeploymentAgentActionLog.effectsSettled(events), "An older receipt cannot settle a new attempt or backfill an old UNKNOWN");
        events.add(new DeploymentAgentActionLog.Event(8, kind + "_NOT_SENT", "original", "REVIEW_STOPPED_NOT_SENT"));
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        for (String unrelated : List.of("PREPARE", "PATCH", "SEAL", "DELIVERY"))
            assertFalse(DeploymentAgentActionLog.effectsSettled(List.of(
                new DeploymentAgentActionLog.Event(1, unrelated + "_EXECUTING", "original", "attempt"),
                new DeploymentAgentActionLog.Event(2, unrelated + "_NOT_SENT", "original", "REVIEW_STOPPED_NOT_SENT"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARE", "COMMAND", "PATCH"})
    void lowerRiskUnsentReceiptRequiresOriginalKindActionAndReasonAfterReopen(String kind) throws Exception {
        enable();
        UUID original;
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentActionLog(context.db());
            var work = context.tasks().submit(new TaskRequest("agent-unsent-proof", "Persist exact known-unsent evidence", List.of(), false), task -> {
                journal.create(task, new DeploymentAgentActionLog.Identity("evidence-only", "a".repeat(64), "b".repeat(64), "AUTOMATIC", true));
                String other = kind.equals("COMMAND") ? "PATCH" : "COMMAND";
                journal.event(task, kind + "_EXECUTING", "original-binding", "attempt-boundary");
                journal.event(task, other + "_NOT_SENT", "original-binding", "ALTERNATIVE_REQUIRED");
                journal.event(task, kind + "_NOT_SENT", "different-binding", "ALTERNATIVE_REQUIRED");
                journal.event(task, kind + "_NOT_SENT", "original-binding", "UNCONFIRMED");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_NOT_SENT", "original-binding", "ALTERNATIVE_REQUIRED");
                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_EXECUTING", "original-binding", "new-attempt-boundary");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)), "A previous unsent receipt does not settle a later attempt");
                journal.event(task, kind + "_NOT_SENT", "original-binding", "ALTERNATIVE_REQUIRED");
                journal.state(task, "SUCCEEDED");
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            original = work.id();
            assertEquals(TaskResult.SUCCEEDED, work.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var query = new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)).events(original);
            var value = query.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow();
            var events = value.events().stream().filter(event -> !event.type().equals("REMEMBER_AUTHORIZATION")).toList();
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 4)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events.subList(0, 5)));
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 6)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            assertEquals(original, value.entry().id());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMAND", "PATCH"})
    void unrelatedReceiptsCannotReleaseOriginalEffectAfterReopen(String kind) throws Exception {
        enable();
        UUID original;
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var journal = new DeploymentAgentActionLog(context.db());
            var work = context.tasks().submit(new TaskRequest(DeploymentAgentWorkflow.TASK_TYPE,
                "Persist exact effect kinds", List.of(), true), task -> {
                journal.create(task, new DeploymentAgentActionLog.Identity("evidence-only", "a".repeat(64),
                    "b".repeat(64), "MANUAL_REVIEW", true));
                String other = kind.equals("COMMAND") ? "PATCH" : "COMMAND";
                journal.event(task, kind + "_EXECUTING", "shared-binding", "first");
                journal.event(task, other + "_RESULT", "shared-binding", "unrelated-kind");
                journal.event(task, "QUERY_RESULT", "shared-binding", "readonly");
                journal.event(task, kind + "_RESULT", "different-binding", "unrelated-action");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_RESULT", "shared-binding", "known-completion");
                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_EXECUTING", "shared-binding", "second");
                journal.event(task, other + "_RESULT", "shared-binding", "still-unrelated");
                assertFalse(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                journal.event(task, kind + "_RESULT", "shared-binding", "second-known-completion");
                journal.state(task, "SUCCEEDED");
                return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            original = work.id();
            assertEquals(TaskResult.SUCCEEDED, work.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
        }
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var query = new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)).events(original);
            var events = query.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).value().orElseThrow().events().stream()
                .filter(event -> !event.type().equals("REMEMBER_AUTHORIZATION")).toList();
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 4)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events.subList(0, 5)));
            assertFalse(DeploymentAgentActionLog.effectsSettled(events.subList(0, 7)));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            assertEquals(original, query.completion().toCompletableFuture().get().value().orElseThrow().entry().id());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}

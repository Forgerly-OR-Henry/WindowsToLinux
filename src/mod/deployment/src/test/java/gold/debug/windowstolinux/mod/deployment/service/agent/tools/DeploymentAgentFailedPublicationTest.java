package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Online typed failure settlement requires its unchanged real journal and formal failed result. */
class DeploymentAgentFailedPublicationTest {
    @Test
    void existingNativeDatabaseRecoveryRetainsDispatchedReceiptWhenActualStatusQueryIsUnknown() throws Exception {
        try (var frame = new DeploymentAgentFailurePublicationTest.Frame()) {
            String address = "1".repeat(32);
            String key = "deployment-database-plan." + "2".repeat(32);
            var json = DeploymentAgentFailurePublicationTest.JSON;
            var plan = json.createObjectNode().put("formatVersion", 1).put("taskId", frame.taskId.toString())
                .put("serverId", frame.server.id()).put("manifestSha256", "e".repeat(64));
            plan.putArray("keys").add(address);
            frame.retainIndexed(key, plan.toString());
            var child = json.createObjectNode().put("formatVersion", 1).put("taskId", frame.taskId.toString())
                .put("serverId", frame.server.id()).put("operationId", UUID.randomUUID().toString()).put("action", "initialize")
                .put("targetDigest", "3".repeat(64)).put("requestDigest", "4".repeat(64)).put("target", "controlled-database")
                .put("phase", "DISPATCHED").put("effectBoundary", "MAY_HAVE_RUN").put("effectsObserved", false);
            child.putArray("effectTargets");
            frame.retainIndexed("deployment-native-database." + address, child.toString());
            frame.journal.event(frame.task, "AGENT_DATABASE_INDEX", key, "e".repeat(64) + ":" + "6".repeat(64));
            frame.nativeDatabaseRecovery = true;
            var channel = new gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseChannel(
                frame.session, frame.context.interaction(), frame.server, frame.task);
            assertFalse(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentResourceRecovery.settle(
                frame.context, frame.task, frame.server, frame.journal.events(frame.task), channel));
            assertEquals(1, frame.nativeDatabaseStatusQueries);
            assertEquals(0, frame.indexedCloses);
            assertEquals(child.toString(), frame.extraCheckpoints.get("deployment-native-database." + address).snapshot().payload());
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
        }
    }

    @Test
    void existingSecretRecoveryRetainsOriginalReferenceWhenItsActualQueryIsUnknown() throws Exception {
        try (var frame = new DeploymentAgentFailurePublicationTest.Frame()) {
            String key = "deployment-secret-revision." + "1".repeat(32);
            var child = DeploymentAgentFailurePublicationTest.JSON.createObjectNode().put("formatVersion", 1).put("taskId", frame.taskId.toString())
                .put("serverId", frame.server.id()).put("secretId", "controlled-secret").put("revision", 1).put("purpose", "deployment-runtime")
                .put("createdAt", 0L).put("phase", "RESERVE_STARTED").put("credentialId", "controlled-opaque-id").putNull("referenceId").putNull("failure");
            frame.retainIndexed(key, child.toString());
            frame.journal.event(frame.task, "AGENT_SECRET_INDEX", key, "6".repeat(64) + ":deployment-runtime");
            assertFalse(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentResourceRecovery.settle(
                frame.context, frame.task, frame.server, frame.journal.events(frame.task), null));
            assertEquals(1, frame.secretReferenceQueries);
            assertEquals(0, frame.indexedCloses);
            assertEquals(child.toString(), frame.extraCheckpoints.get(key).snapshot().payload());
            assertEquals(0, frame.executed);
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
        }
    }

    @Test
    void acceptanceCannotSettleBeforeOriginalFailedDeliveryResult() throws Exception {
        try (var frame = accepted()) {
            assertThrows(IllegalStateException.class, () -> settle(frame, frame.candidates));
            failedOutcome(frame, frame.accepted.get().failure());
            assertThrows(IllegalStateException.class, () -> settle(frame, frame.candidates));
            frame.journal.event(frame.task, "DELIVERY_RESULT", "9".repeat(64), "FAILED");
            assertDoesNotThrow(() -> settle(frame, frame.candidates));
            assertEquals(0, frame.resourceReleases);
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-candidate", "original-event", "unknown-effect", "wrong-delivery", "duplicate-delivery", "different-failure", "later-cli"})
    void changedEvidenceCannotTurnAcceptanceIntoCleanupAuthority(String change) throws Exception {
        try (var frame = accepted()) {
            var candidates = frame.candidates;
            var failure = frame.accepted.get().failure();
            if (change.equals("different-failure"))
                failure = new Problem(failure.code(), "DIFFERENT_ORIGINAL_FAILURE", failure.message(), failure.context(), failure.diagnosticId());
            failedOutcome(frame, failure);
            frame.journal.event(frame.task, "DELIVERY_RESULT", "9".repeat(64), change.equals("wrong-delivery") ? "SUCCEEDED" : "FAILED");
            if (change.equals("duplicate-delivery"))
                frame.journal.event(frame.task, "DELIVERY_RESULT", "9".repeat(64), "FAILED");
            if (change.equals("original-event"))
                frame.sql("UPDATE deployment_agent_event SET event_detail='UNKNOWN' WHERE event_type='GRAPH_ROLLBACK_RESULT'");
            if (change.equals("unknown-effect")) frame.journal.event(frame.task, "CAPABILITY_EXECUTING", "other", "1");
            if (change.equals("later-cli")) frame.command("ENDED");
            if (change.equals("foreign-candidate")) {
                var original = candidates.get("component");
                candidates = Map.of("component", new DeploymentGraphPublisher.Candidate("controlled-fedcba9876543210", original.artifactSha256(),
                    original.sourceRevision(), original.toolchainBindingSha256(), original.hasTools(), original.credentials()));
            }
            var changed = candidates;
            assertThrows(IllegalStateException.class, () -> settle(frame, changed));
            assertEquals(0, frame.resourceReleases);
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(1, frame.count(DeploymentAgentFailedPublication.EVENT));
        }
    }

    private static DeploymentAgentFailurePublicationTest.Frame accepted() throws Exception {
        var frame = new DeploymentAgentFailurePublicationTest.Frame();
        try {
            frame.script = (request, turn) -> DeploymentAgentFailurePublicationTest.call(request, turn,
                turn == 0 ? "publish" : "finish_failure", turn == 0 ? Map.of() : Map.of("reason", "Accept observed native failed attempt"));
            assertEquals(TaskResult.FAILED, frame.run(10).outcome());
            assertNotNull(frame.accepted.get());
            return frame;
        } catch (Throwable failure) {
            frame.close();
            throw failure;
        }
    }

    private static void failedOutcome(DeploymentAgentFailurePublicationTest.Frame frame, Problem failure) throws Exception {
        frame.executions.recordUncommittedOutcome(frame.task, frame.execution.executionId(), TaskResult.FAILED, failure, Instant.EPOCH);
    }

    private static void settle(DeploymentAgentFailurePublicationTest.Frame frame,
                               Map<String, DeploymentGraphPublisher.Candidate> candidates) throws Exception {
        frame.accepted.get().requireSettlement(frame.context, frame.session, frame.server, frame.task,
            DeploymentAgentFailurePublicationTest.REVISION, frame.proposal, candidates, frame.journal);
    }
}

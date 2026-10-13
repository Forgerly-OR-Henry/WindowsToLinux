package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentRecovery;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentWorkspaceBindingTest {
    static final UUID TASK = UUID.randomUUID();

    static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();

    static final DeploymentAgentActionLog.Identity IDENTITY = new DeploymentAgentActionLog.Identity("server", "a".repeat(64), "b".repeat(64), "MANUAL_REVIEW", true);

    static final DeploymentAgentWorkspaceBinding.Binding BINDING = new DeploymentAgentWorkspaceBinding.Binding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), "application", "c".repeat(64), LAYOUT.digest(), IDENTITY.targetDigest(), IDENTITY.configurationDigest(), "d".repeat(64));

    static ArrayList<DeploymentAgentActionLog.Event> prefix(int semantics) throws Exception {
        var result = new ArrayList<DeploymentAgentActionLog.Event>();
        if (semantics == 5) event(result, "SUCCESSOR_BINDING", TASK.toString(), BINDING.detail(TASK, "server"));
        event(result, "REMEMBER_AUTHORIZATION", "rememberAuthorization.v1", "true");
        event(result, "REMOTE_TARGET", "", "e".repeat(64));
        var node = new ObjectMapper().createObjectNode().put("formatVersion", 1).put("task", TASK.toString()).put("server", "server");
        node.set("layout", new ObjectMapper().readTree(LAYOUT.canonical()));
        node.put("layoutSha256", LAYOUT.digest());
        event(result, "REMOTE_LAYOUT", TASK.toString(), node.toString());
        event(result, "SOURCE", "f".repeat(64), BINDING.source());
        event(result, "TARGET_PROFILE", "", "e".repeat(64));
        event(result, "PLAN", "", "e".repeat(64));
        event(result, "PLAN_APPLICATION", "", "application");
        event(result, "PREPARE_EXECUTING", DeploymentAgentPreparationAbsence.hash(TASK + ":web:" + BINDING.source()), "web");
        event(result, "STATE", "", "UNKNOWN");
        return result;
    }

    static DeploymentAgentActionLog.Event event(List<DeploymentAgentActionLog.Event> all, String type, String action, String detail) {
        var value = new DeploymentAgentActionLog.Event(all.size() + 1, type, action, detail);
        all.add(value);
        return value;
    }

    @SuppressWarnings("unchecked")
    static Optional<List<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace>> recovery(
        DeploymentAgentActionLog.Snapshot saved, List<DeploymentAgentActionLog.Event> events) throws Exception {
        var method = DeploymentAgentRecovery.class.getDeclaredMethod("originalWorkspaces", UUID.class, DeploymentAgentActionLog.Snapshot.class, List.class, DeploymentRemoteLayout.class);
        method.setAccessible(true);
        return (Optional<List<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace>>) method.invoke(null, TASK, saved, events, LAYOUT);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5})
    void actualAbsenceAndCompensationProofsRoundTripThroughRecoveryWithTheirOwnNonce(int semantics) throws Exception {
        var saved = new DeploymentAgentActionLog.Snapshot(IDENTITY, "UNKNOWN", semantics);
        var events = prefix(semantics);
        var captured = DeploymentAgentPreparationAbsence.capture(TASK, saved, LAYOUT, events).orElseThrow();
        var expected = DeploymentAgentWorkspaceBinding.workspace(TASK, saved, events, captured.workspace().applicationId(), "f".repeat(64), LAYOUT);
        assertEquals(expected, captured.workspace());
        if (semantics == 5) assertEquals(Optional.of(BINDING.operation()), captured.workspace().operation());
        else assertTrue(captured.workspace().operation().isEmpty());
        var absent = event(events, DeploymentAgentPreparationAbsence.EVENT, captured.preparation().action(),
            DeploymentAgentPreparationAbsence.proof(captured, DeploymentAgentPreparationProbe.requestDigest(captured),
                DeploymentAgentPreparationAbsence.hash(DeploymentAgentPreparationProbe.absentOutput(captured))));
        assertTrue(DeploymentAgentPreparationAbsence.matches(absent, events));
        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
        assertTrue(recovery(saved, events).orElseThrow().isEmpty());
        var original = prefix(semantics);
        original.removeLast();
        event(original, "HELPER_READINESS_OBSERVED", "e".repeat(64), "READY:CURRENT_TASK_READY");
        event(original, "STATE", "", "UNKNOWN");
        var partial = DeploymentAgentPreparationCompensation.capture(TASK, saved, LAYOUT, original).orElseThrow();
        var compensated = event(original, DeploymentAgentPreparationCompensation.EVENT, partial.preparation().action(),
            DeploymentAgentPreparationCompensation.proof(partial, false, DeploymentAgentPreparationCompensation.Cleanup.NOT_REQUIRED, ""));
        assertTrue(DeploymentAgentPreparationCompensation.matches(compensated, original));
        assertTrue(DeploymentAgentActionLog.effectsSettled(original));
        assertTrue(recovery(saved, original).orElseThrow().isEmpty());
        if (semantics == 4) {
            assertTrue(absent.detail().startsWith("{\"formatVersion\":1,"));
            assertFalse(absent.detail().contains("executionSemantics"));
        } else {
            assertTrue(absent.detail().startsWith("{\"formatVersion\":2,"));
            assertTrue(absent.detail().contains("\"executionSemantics\":5"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-binding", "foreign-task", "wrong-operation", "source", "missing-semantics", "downgrade"})
    void claimedNonceAndItsProofNeverFallbackToOriginalSemantics(String change) throws Exception {
        var saved = new DeploymentAgentActionLog.Snapshot(IDENTITY, "UNKNOWN", 5);
        var events = prefix(5);
        var captured = DeploymentAgentPreparationAbsence.capture(TASK, saved, LAYOUT, events).orElseThrow();
        String proof = DeploymentAgentPreparationAbsence.proof(captured, DeploymentAgentPreparationProbe.requestDigest(captured),
            DeploymentAgentPreparationAbsence.hash(DeploymentAgentPreparationProbe.absentOutput(captured)));
        var json = new ObjectMapper();
        if (change.equals("missing-semantics")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(proof);
            node.remove("executionSemantics");
            proof = node.toString();
        } else if (change.equals("downgrade")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(proof);
            node.put("formatVersion", 1);
            node.remove("executionSemantics");
            proof = node.toString();
        } else {
            var binding = events.getFirst();
            if (change.equals("missing-binding")) events.removeFirst();
            else {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(binding.detail());
                node.put(change.equals("foreign-task") ? "task" : change.equals("source") ? "source" : "operation",
                    change.equals("source") ? "0".repeat(64) : UUID.randomUUID().toString());
                events.set(0, new DeploymentAgentActionLog.Event(binding.id(), binding.type(), binding.action(), node.toString()));
            }
        }
        var absent = new DeploymentAgentActionLog.Event(100, DeploymentAgentPreparationAbsence.EVENT, captured.preparation().action(), proof);
        events.add(absent);
        assertFalse(DeploymentAgentPreparationAbsence.matches(absent, events));
        assertTrue(recovery(saved, events).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-semantics", "foreign-binding", "downgrade"})
    void actualCompensationProofRejectsMissingSemanticsForeignBindingAndDowngrade(String change) throws Exception {
        var events = prefix(5);
        events.removeLast();
        event(events, "HELPER_READINESS_OBSERVED", "e".repeat(64), "READY:CURRENT_TASK_READY");
        event(events, "STATE", "", "UNKNOWN");
        var captured = DeploymentAgentPreparationCompensation.capture(TASK, new DeploymentAgentActionLog.Snapshot(IDENTITY, "UNKNOWN", 5), LAYOUT, events).orElseThrow();
        var json = new ObjectMapper();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(DeploymentAgentPreparationCompensation.proof(captured,
            false, DeploymentAgentPreparationCompensation.Cleanup.NOT_REQUIRED, ""));
        if (change.equals("missing-semantics")) node.remove("executionSemantics");
        else if (change.equals("downgrade")) {
            node.put("formatVersion", 1);
            node.remove("executionSemantics");
        } else {
            var original = events.getFirst();
            var binding = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.detail());
            binding.put("operation", UUID.randomUUID().toString());
            events.set(0, new DeploymentAgentActionLog.Event(original.id(), original.type(), original.action(), binding.toString()));
        }
        var proof = event(events, DeploymentAgentPreparationCompensation.EVENT, captured.preparation().action(), node.toString());
        assertFalse(DeploymentAgentPreparationCompensation.matches(proof, events));
        assertFalse(DeploymentAgentActionLog.effectsSettled(events));
        assertTrue(recovery(new DeploymentAgentActionLog.Snapshot(IDENTITY, "UNKNOWN", 5), events).isEmpty());
    }
}

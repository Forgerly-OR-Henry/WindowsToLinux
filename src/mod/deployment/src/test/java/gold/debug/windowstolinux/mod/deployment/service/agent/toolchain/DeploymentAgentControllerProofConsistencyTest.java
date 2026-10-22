package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentControllerProofConsistencyTest {
    @ParameterizedTest
    @ValueSource(strings = {"receipt", "exit", "truncated", "stderr"})
    void completeActualTransportMustBeDurableBeforeConfirmingTheSameReceipt(String change) throws Exception {
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory();
        var state = memory.state(DeploymentAgentToolchainTrackedTest.SERVER, DeploymentAgentToolchainTrackedTest.LAYOUT);
        var recipe = DeploymentAgentToolchainTrackedTest.recipe("BIND", memory.id.toString());
        var action = state.begin("BIND", recipe.operationId(), "original exact", null, recipe, null);
        state.dispatched(action);
        var receipt = DeploymentAgentToolchainTrackedTest.observation(recipe, "bind-tracked", "SUCCEEDED", true);
        var actual = DeploymentAgentToolchainTrackedTest.result(receipt);
        var persistedReceipt = receipt.deepCopy();
        if (change.equals("receipt")) persistedReceipt.put("executionStatus", "FAILED");
        var persisted = new OperationResult<CommandResult>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(
            Optional.of(change.equals("exit") ? 7 : 0), DeploymentAgentToolchainTrackedTest.result(persistedReceipt).value().orElseThrow().stdout(),
            change.equals("stderr") ? "unconfirmed stderr" : "", change.equals("truncated"))), Optional.empty());
        state.transport(action, persisted);
        assertThrows(ApiException.class, () -> state.finish(action, actual, true, receipt));
        assertFalse(action.checkpoint().snapshot().released());
        assertEquals("INTENT", DeploymentAgentToolchainTracked.JSON.readTree(action.checkpoint().snapshot().payload()).path("state").asText());
        assertTrue(memory.events.stream().noneMatch(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT")));
    }

    @Test
    void retainedReceiptMustStillMatchOriginalStdoutWhenCheckpointAndResultAgree() throws Exception {
        var fixture = original(false);
        assertDoesNotThrow(() -> snapshot(fixture.memory));
        var checkpoint = (DeploymentAgentToolchainProgramStateTest.Checkpoint) fixture.action.checkpoint();
        var payload = (ObjectNode) DeploymentAgentToolchainTracked.JSON.readTree(checkpoint.snapshot().payload());
        var retained = DeploymentAgentToolchainTracked.parse(DeploymentAgentToolchainTracked.utf8(
            DeploymentAgentToolchainProgramState.readBlob(fixture.memory.events, fixture.action.id(), payload.path("rawResultRef"))));
        assertEquals("AGENT_TOOLCHAIN_TRACKED=" + payload.path("receipt") + "\n", retained.path("stdout").textValue());
        ((ObjectNode) payload.path("receipt")).put("executionStatus", "FAILED");
        String changed = payload.toString();
        checkpoint.value = new TaskCheckpoint.Snapshot(checkpoint.value.revision() + 1, changed, true);
        for (int index = 0; index < fixture.memory.events.size(); index++) {
            var event = fixture.memory.events.get(index);
            if (!event.type().equals("CONTROLLER_PROGRAM_RESULT")) continue;
            var result = DeploymentAgentToolchainTracked.parse(event.detail()).put("payloadSha256", DeploymentAgentToolchainPrograms.sha256(changed));
            fixture.memory.events.set(index, new DeploymentAgentActionLog.Event(event.id(), event.type(), event.action(), result.toString()));
        }
        assertThrows(IllegalArgumentException.class, () -> snapshot(fixture.memory));
    }

    @Test
    void notSentCannotReleaseOrReadAsSettledWhenOriginalDispatchExists() throws Exception {
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory();
        var state = memory.state(DeploymentAgentToolchainTrackedTest.SERVER, DeploymentAgentToolchainTrackedTest.LAYOUT);
        var recipe = DeploymentAgentToolchainTrackedTest.recipe("BIND", memory.id.toString());
        var action = state.begin("BIND", recipe.operationId(), "original exact", null, recipe, null);
        state.transport(action, unknown()); state.finish(action, unknown(), false, null, true);
        assertTrue(action.checkpoint().snapshot().released());
        memory.events.add(new DeploymentAgentActionLog.Event(memory.events.size() + 1L, "CONTROLLER_PROGRAM_DISPATCH", action.id(), "{\"formatVersion\":1}"));
        assertThrows(IllegalArgumentException.class, () -> snapshot(memory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipe-before-index", "result-before-settlement", "observation-before-settlement"})
    void retainedProofRejectsReorderedOriginalAndRecoveryEvidence(String change) throws Exception {
        var fixture = original(true);
        settle(fixture);
        var events = fixture.memory.events;
        DeploymentAgentActionLog.Event first, second;
        if (change.equals("recipe-before-index")) {
            first = events.stream().filter(event -> event.type().equals("CONTROLLER_BLOB_END") && DeploymentAgentToolchainTracked.parse(event.detail()).path("kind").asText().equals("RECIPE")).findFirst().orElseThrow();
            second = events.stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_ACTION")).findFirst().orElseThrow();
        } else {
            first = events.stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_SETTLED")).findFirst().orElseThrow();
            second = events.stream().filter(event -> change.equals("result-before-settlement") ? event.type().equals("CONTROLLER_PROGRAM_RESULT")
                : event.type().equals("CONTROLLER_PROGRAM_RECOVERY") && DeploymentAgentToolchainTracked.parse(event.detail()).path("state").asText().equals("OBSERVED")).findFirst().orElseThrow();
        }
        events.set(events.indexOf(first), new DeploymentAgentActionLog.Event(second.id(), first.type(), first.action(), first.detail()));
        events.set(events.indexOf(second), new DeploymentAgentActionLog.Event(first.id(), second.type(), second.action(), second.detail()));
        events.sort(Comparator.comparingLong(DeploymentAgentActionLog.Event::id));
        assertThrows(IllegalArgumentException.class, () -> snapshot(fixture.memory));
    }

    @Test
    void durableSettlementCanCloseAfterReleaseFailureWithoutReplacingUnknownResult() throws Exception {
        var fixture = original(true);
        String payload = fixture.action.checkpoint().snapshot().payload();
        var results = fixture.memory.events.stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT")).toList();
        fixture.memory.failClose = true;
        assertThrows(IllegalStateException.class, () -> settle(fixture));
        assertFalse(fixture.action.checkpoint().snapshot().released());
        assertEquals(Boolean.TRUE, snapshot(fixture.memory).getFirst().get("settled"));
        fixture.memory.failClose = false;
        fixture.state.closeSettled(fixture.action.id());
        assertTrue(fixture.action.checkpoint().snapshot().released());
        assertEquals(payload, fixture.action.checkpoint().snapshot().payload());
        assertEquals(results, fixture.memory.events.stream().filter(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT")).toList());
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(fixture.memory.tasks, fixture.memory.task,
            DeploymentAgentToolchainTrackedTest.SERVER.id(), DeploymentAgentToolchainTrackedTest.LAYOUT, fixture.memory.events));
    }

    @Test
    void missingOriginalResultAfterCheckpointAdvanceDoesNotInventAReplacement() throws Exception {
        var fixture = original(true);
        fixture.memory.events.removeIf(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT"));
        String original = fixture.action.checkpoint().snapshot().payload();
        settle(fixture);
        assertTrue(fixture.action.checkpoint().snapshot().released());
        assertEquals(original, fixture.action.checkpoint().snapshot().payload());
        assertTrue(fixture.memory.events.stream().noneMatch(event -> event.type().equals("CONTROLLER_PROGRAM_RESULT")));
        assertDoesNotThrow(() -> snapshot(fixture.memory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "unknown", "failed", "stderr"})
    void unverifiedTransportRetainsOnlyDigestsAndNeverDynamicOutputOrErrorText(String boundary) throws Exception {
        String secret = "PRIVATE_TRANSPORT_SENTINEL_" + UUID.randomUUID();
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory();
        var state = memory.state(DeploymentAgentToolchainTrackedTest.SERVER, DeploymentAgentToolchainTrackedTest.LAYOUT);
        var recipe = DeploymentAgentToolchainTrackedTest.recipe("BIND", memory.id.toString());
        var action = state.begin("BIND", recipe.operationId(), "original exact", null, recipe, null);
        state.dispatched(action);
        var valid = DeploymentAgentToolchainTrackedTest.observation(recipe, "bind-tracked", "SUCCEEDED", true);
        String stdout = boundary.equals("stderr") ? DeploymentAgentToolchainTrackedTest.result(valid).value().orElseThrow().stdout() : "invalid stdout " + secret;
        String stderr = boundary.equals("stderr") ? "untrusted stderr " + secret : "";
        var result = new OperationResult<CommandResult>(boundary.equals("unknown") ? TaskResult.UNKNOWN : boundary.equals("failed") ? TaskResult.FAILED : TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), stdout, stderr, false)), Set.of("unknown", "failed").contains(boundary)
                ? Optional.of(new Problem(ProblemCode.UNKNOWN, secret, "arbitrary exception " + secret, Map.of("raw", secret), Optional.empty())) : Optional.empty());
        state.transport(action, result); state.finish(action, result, false, null);
        for (var event : memory.events) assertFalse(event.detail().contains(secret));
        String payload = action.checkpoint().snapshot().payload();
        assertFalse(payload.contains(secret));
        var node = DeploymentAgentToolchainTracked.parse(payload);
        var retained = DeploymentAgentToolchainTracked.parse(DeploymentAgentToolchainTracked.utf8(
            DeploymentAgentToolchainProgramState.readBlob(memory.events, action.id(), node.path("rawResultRef"))));
        assertFalse(retained.has("stdout")); assertFalse(retained.has("stderr"));
        assertFalse(retained.toString().contains(secret));
        assertEquals(DeploymentAgentToolchainPrograms.sha256(stdout), retained.path("stdoutSha256").asText());
        assertEquals(DeploymentAgentToolchainPrograms.sha256(stderr), retained.path("stderrSha256").asText());
        assertEquals(result.outcome().name(), retained.path("outcome").asText());
        assertEquals(0, retained.path("exit").intValue());
        assertDoesNotThrow(() -> snapshot(memory));
    }

    private record Fixture(DeploymentAgentToolchainProgramStateTest.Memory memory, DeploymentAgentToolchainProgramState state,
                           DeploymentAgentToolchainProgramState.Action action, DeploymentAgentToolchainTracked.Recipe recipe) { }

    private static Fixture original(boolean uncertain) throws Exception {
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory();
        var state = memory.state(DeploymentAgentToolchainTrackedTest.SERVER, DeploymentAgentToolchainTrackedTest.LAYOUT);
        var recipe = DeploymentAgentToolchainTrackedTest.recipe("BIND", memory.id.toString());
        var action = state.begin("BIND", recipe.operationId(), "original exact", null, recipe, null);
        state.dispatched(action);
        var receipt = DeploymentAgentToolchainTrackedTest.observation(recipe, "bind-tracked", "SUCCEEDED", true);
        var actual = uncertain ? unknown() : DeploymentAgentToolchainTrackedTest.result(receipt);
        state.transport(action, actual); state.finish(action, actual, !uncertain, uncertain ? null : receipt);
        return new Fixture(memory, state, action, recipe);
    }

    private static void settle(Fixture fixture) throws Exception {
        String exact = "fresh observe original", hash = DeploymentAgentToolchainPrograms.sha256(exact);
        String id = fixture.state.recoveryIntent(fixture.action.id(), "observe-tracked", exact);
        fixture.state.recoveryEvent(fixture.action.id(), id, "observe-tracked", "DISPATCHED", hash, null);
        var receipt = DeploymentAgentToolchainTrackedTest.observation(fixture.recipe, "observe-tracked", "UNKNOWN", true);
        var actual = DeploymentAgentToolchainTrackedTest.result(receipt);
        fixture.state.recoveredTransport(id, actual);
        fixture.state.recoveryEvent(fixture.action.id(), id, "observe-tracked", "OBSERVED", hash, receipt);
        fixture.state.settle(fixture.action.id(), DeploymentAgentToolchainTracked.receipt(actual, fixture.recipe, "observe-tracked"));
    }

    private static List<Map<String, Object>> snapshot(DeploymentAgentToolchainProgramStateTest.Memory memory) throws Exception {
        return DeploymentAgentToolchainProgramState.snapshot(memory.tasks, memory.task, DeploymentAgentToolchainTrackedTest.SERVER.id(),
            DeploymentAgentToolchainTrackedTest.LAYOUT, memory.events);
    }

    private static OperationResult<CommandResult> unknown() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "ORIGINAL_ACK_LOST", "Original raw transport remains unknown", Map.of(), Optional.empty())));
    }
}

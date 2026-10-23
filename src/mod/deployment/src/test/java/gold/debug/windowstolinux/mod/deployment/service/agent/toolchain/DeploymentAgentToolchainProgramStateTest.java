package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class DeploymentAgentToolchainProgramStateTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static final ServerId SERVER = new ServerId("original-server");

    static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();

    @Test
    void originalActionsReleaseOnlyAfterDurableResultAndNeverSettleAnotherTaskUnknown() throws Exception {
        var memory = new Memory();
        var state = memory.state(SERVER, LAYOUT);
        var first = state.begin("BIND", "a".repeat(32), "exact-one", null);
        assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
        state.finish(first, success("original bound receipt"), true, null);
        assertTrue(first.checkpoint().snapshot().released());
        var original = JSON.readTree(first.checkpoint().snapshot().payload());
        assertEquals("CONFIRMED", original.path("state").asText());
        assertTrue(original.path("confirmationMeaning").asText().startsWith("ORIGINAL_SDK_COMMAND_ENDED_ONLY"));
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
        var second = state.begin("BIND", "b".repeat(32), "exact-two", null);
        assertNotEquals(first.id(), second.id());
        state.finish(second, new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_UNKNOWN", "controlled", Map.of(), Optional.empty()))), false, null);
        assertFalse(second.checkpoint().snapshot().released());
        assertThrows(ApiException.class, () -> state.begin("BIND", "c".repeat(32), "exact-three", null));
        var other = new Memory();
        var otherAction = other.state(SERVER, LAYOUT).begin("BIND", "c".repeat(32), "exact-other-task", null);
        other.state(SERVER, LAYOUT).finish(otherAction, success("other ended"), true, null);
        assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
        assertEquals(original, JSON.readTree(first.checkpoint().snapshot().payload()));
        var missingResult = new ArrayList<>(other.events);
        missingResult.removeLast();
        assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(other.tasks, other.task, SERVER.id(), LAYOUT, missingResult));
        var failedJournal = new Memory();
        var action = failedJournal.state(SERVER, LAYOUT).begin("BIND", "a".repeat(32), "exact", null);
        failedJournal.failResult = true;
        assertThrows(ApiException.class, () -> failedJournal.state(SERVER, LAYOUT).finish(action, success("ended"), true, null));
        assertFalse(action.checkpoint().snapshot().released());
        assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(failedJournal.tasks, failedJournal.task, SERVER.id(), LAYOUT, failedJournal.events));
    }

    @Test
    void missingCorruptForeignAndRetainedVersionIndexesFailClosed() throws Exception {
        var memory = new Memory();
        var state = memory.state(SERVER, LAYOUT);
        var action = state.begin("READINESS", "a".repeat(32), "exact", null);
        assertThrows(ApiException.class, () -> state.finish(action, success("not a store receipt"), true, null));
        state.finish(action, success("malformed receipt"), false, null);
        assertEquals("UNKNOWN", JSON.readTree(action.checkpoint().snapshot().payload()).path("state").asText());
        for (String field : List.of("task", "server", "layoutSha256", "phase", "operation", "path", "version", "sourceSha256", "requestSha256")) {
            var changed = (ObjectNode) JSON.readTree(memory.events.getFirst().detail());
            changed.put(field, "invalid");
            var events = List.of(new DeploymentAgentActionLog.Event(1, "CONTROLLER_PROGRAM_ACTION", action.id(), changed.toString()));
            assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, events), field);
        }
        var missing = new Memory();
        missing.failBegin = true;
        assertThrows(ApiException.class, () -> missing.state(SERVER, LAYOUT).begin("INSTALL", "b".repeat(32), "exact", null));
        assertEquals(1, missing.events.size());
        assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(missing.tasks, missing.task, SERVER.id(), LAYOUT, missing.events));
        var retained = new Memory();
        var done = retained.state(SERVER, LAYOUT).begin("BIND", "c".repeat(32), "old exact", null);
        retained.state(SERVER, LAYOUT).finish(done, success("ended"), true, null);
        var body = (ObjectNode) JSON.readTree(done.checkpoint().snapshot().payload());
        var index = (ObjectNode) body.path("index");
        index.put("version", "e".repeat(64)).put("path", LAYOUT.toolchains() + "/agent-controller-programs/" + "e".repeat(64));
        index.put("sourceSha256", "d".repeat(64));
        retained.checkpoints.get("agent-controller-program." + done.id()).value = new TaskCheckpoint.Snapshot(5, body.toString(), true);
        retained.events.set(0, new DeploymentAgentActionLog.Event(1, "CONTROLLER_PROGRAM_ACTION", done.id(), index.toString()));
        retained.events.set(1, new DeploymentAgentActionLog.Event(2, "CONTROLLER_PROGRAM_RESULT", done.id(), JSON.writeValueAsString(Map.of(
            "state", "CONFIRMED", "payloadSha256", DeploymentAgentToolchainPrograms.sha256(body.toString())))));
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(retained.tasks, retained.task, SERVER.id(), LAYOUT, retained.events), "retained old versions remain valid original evidence");
    }

    @Test
    void precreationRefusalReleasesOnlyAfterDurableOriginalResultAndRetainsFailure() throws Exception {
        var memory = new Memory();
        String operation = "a".repeat(32);
        var state = memory.state(SERVER, LAYOUT);
        try (var request = precreationRequest(memory, operation)) {
            var result = volumeRefusal(operation);
            var action = state.begin("PREPARE", operation, exact(request), null);
            var proof = DeploymentAgentToolchainFailureEvidence.precreationRefusal(action, "prepare", operation,
                memory.id.toString(), SERVER, LAYOUT, request, result).orElseThrow();
            memory.transitions.clear();
            state.finish(action, result, false, null, false, proof);
            assertEquals(List.of("ADVANCE", "RESULT", "CLOSE"), memory.transitions);
            assertTrue(action.checkpoint().snapshot().released());
            var original = JSON.readTree(action.checkpoint().snapshot().payload());
            assertEquals("FAILED", original.path("state").asText());
            assertEquals("FAILED", original.path("transportOutcome").asText());
            assertTrue(original.path("confirmationMeaning").asText().contains("NOT_CLEANUP_COMPLETE"));
            assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
            var foreign = new Memory();
            var other = foreign.state(SERVER, LAYOUT).begin("PREPARE", operation, exact(request), null);
            assertThrows(ApiException.class, () -> foreign.state(SERVER, LAYOUT).finish(other, result, false, null, false, proof));
            assertFalse(other.checkpoint().snapshot().released());
            assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(foreign.tasks, foreign.task, SERVER.id(), LAYOUT, foreign.events));
            var checkpoint = memory.checkpoints.get("agent-controller-program." + action.id());
            var saved = checkpoint.snapshot();
            var indexed = memory.events.getFirst();
            var ended = memory.events.getLast();
            for (String change : List.of("proofIndex", "futureSource", "sourceBytes", "stdout", "missingProof", "extraProofField", "missingResult", "wrongResultHash")) {
                var altered = (ObjectNode) original.deepCopy();
                var bound = (ObjectNode) altered.path(DeploymentAgentToolchainFailureEvidence.PRECREATION);
                if (change.equals("proofIndex")) ((ObjectNode) bound.path("index")).put("task", foreign.id.toString());
                if (change.equals("futureSource")) {
                    ((ObjectNode) altered.path("index")).put("sourceSha256", "f".repeat(64));
                    ((ObjectNode) bound.path("index")).put("sourceSha256", "f".repeat(64));
                    memory.events.set(0, new DeploymentAgentActionLog.Event(indexed.id(), indexed.type(), indexed.action(), altered.path("index").toString()));
                }
                if (change.equals("sourceBytes")) bound.put("sourceBytes", 101904);
                if (change.equals("stdout")) bound.put("stdout", result.value().orElseThrow().stdout() + "extra\n");
                if (change.equals("missingProof")) altered.remove(DeploymentAgentToolchainFailureEvidence.PRECREATION);
                if (change.equals("extraProofField")) bound.put("cleanupComplete", true);
                String payload = altered.toString();
                checkpoint.value = new TaskCheckpoint.Snapshot(saved.revision(), payload, true);
                memory.events.removeLast();
                if (!change.equals("missingResult"))
                    memory.events.add(new DeploymentAgentActionLog.Event(ended.id(), ended.type(), ended.action(), JSON.writeValueAsString(Map.of(
                        "state", "FAILED", "payloadSha256", change.equals("wrongResultHash") ? "f".repeat(64) : DeploymentAgentToolchainPrograms.sha256(payload)))));
                assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events), change);
                if (!change.equals("missingResult")) memory.events.removeLast();
                memory.events.add(ended);
                memory.events.set(0, indexed);
                checkpoint.value = saved;
            }
            var historical = (ObjectNode) original.deepCopy();
            var retainedIndex = (ObjectNode) historical.path("index");
            retainedIndex.put("version", "e".repeat(64)).put("path", LAYOUT.toolchains() + "/agent-controller-programs/" + "e".repeat(64));
            retainedIndex.put("sourceSha256", "2f82c5c50834f747ff005832edafbc47e9bed72103a98a538a8242d8ed4f3c19");
            ((ObjectNode) historical.path(DeploymentAgentToolchainFailureEvidence.PRECREATION)).put("sourceBytes", 101904);
            ((ObjectNode) historical.path(DeploymentAgentToolchainFailureEvidence.PRECREATION)).set("index", retainedIndex.deepCopy());
            checkpoint.value = new TaskCheckpoint.Snapshot(saved.revision(), historical.toString(), true);
            memory.events.set(0, new DeploymentAgentActionLog.Event(indexed.id(), indexed.type(), indexed.action(), retainedIndex.toString()));
            memory.events.set(memory.events.size() - 1, new DeploymentAgentActionLog.Event(ended.id(), ended.type(), ended.action(), JSON.writeValueAsString(Map.of(
                "state", "FAILED", "payloadSha256", DeploymentAgentToolchainPrograms.sha256(historical.toString())))));
            assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events), "Retained reviewed source is not compared with today's version");
            String probeStdout = volumeRefusal(operation, (1024L + 64) * 1024 * 1024).value().orElseThrow().stdout();
            ((ObjectNode) historical.path(DeploymentAgentToolchainFailureEvidence.PRECREATION)).put("stdout", probeStdout);
            historical.put("stdoutCharacters", probeStdout.length()).put("stdoutSha256", DeploymentAgentToolchainPrograms.sha256(probeStdout));
            checkpoint.value = new TaskCheckpoint.Snapshot(saved.revision(), historical.toString(), true);
            memory.events.set(memory.events.size() - 1, new DeploymentAgentActionLog.Event(ended.id(), ended.type(), ended.action(), JSON.writeValueAsString(Map.of(
                "state", "FAILED", "payloadSha256", DeploymentAgentToolchainPrograms.sha256(historical.toString())))));
            assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events), "The old literal source cannot borrow the new probe threshold");
            checkpoint.value = saved;
            memory.events.set(0, indexed);
            memory.events.set(memory.events.size() - 1, ended);
            state.begin("PREPARE", "b".repeat(32), "new exact request", null);
            assertEquals(original, JSON.readTree(action.checkpoint().snapshot().payload()));
        }
        for (String failure : List.of("ADVANCE", "RESULT", "CLOSE")) {
            var broken = new Memory();
            var originalState = broken.state(SERVER, LAYOUT);
            try (var request = precreationRequest(broken, operation)) {
                var action = originalState.begin("PREPARE", operation, exact(request), null);
                var result = volumeRefusal(operation);
                var proof = DeploymentAgentToolchainFailureEvidence.precreationRefusal(action, "prepare", operation,
                    broken.id.toString(), SERVER, LAYOUT, request, result).orElseThrow();
                broken.failAdvance = failure.equals("ADVANCE");
                broken.failResult = failure.equals("RESULT");
                broken.failClose = failure.equals("CLOSE");
                assertThrows(ApiException.class, () -> originalState.finish(action, result, false, null, false, proof), failure);
                assertFalse(action.checkpoint().snapshot().released());
                assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(broken.tasks, broken.task, SERVER.id(), LAYOUT, broken.events));
                assertThrows(ApiException.class, () -> originalState.begin("PREPARE", "b".repeat(32), "another exact request", null));
            }
        }
        var binding = new Memory();
        var bindingState = binding.state(SERVER, LAYOUT);
        try (var request = new CommandRequest(DeploymentAgentToolchainProgramStore.command("bind", binding.id.toString(), operation,
            List.of("bind", DeploymentAgentToolchainCommandsTest.binding(), "85", operation), LAYOUT), new byte[0], Duration.ofSeconds(100), 65536)) {
            var action = bindingState.begin("BIND", operation, exact(request), null);
            var result = volumeRefusal(operation, (1024L + 64) * 1024 * 1024);
            var proof = DeploymentAgentToolchainFailureEvidence.precreationRefusal(action, "bind", operation,
                binding.id.toString(), SERVER, LAYOUT, request, result).orElseThrow();
            bindingState.finish(action, result, false, null, false, proof);
            assertTrue(action.checkpoint().snapshot().released());
            assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(binding.tasks, binding.task, SERVER.id(), LAYOUT, binding.events));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE_KEYS", "EXTRA_FIELD", "TRAILING_JSON", "WRONG_OPERATION", "REQUEST", "VERSION", "SOURCE", "TASK",
        "PHASE", "PROMOTION", "INSTALL", "CANCELLED", "UNKNOWN", "SUCCEEDED", "NO_EXIT", "TRUNCATED", "STDERR", "EXTRA_LINE",
        "OTHER_FAILURE", "NO_VALUE", "EXIT70", "NONCANONICAL", "AVAILABLE", "RESERVE", "REQUIRED", "FRACTION", "ACTION", "ARGV",
        "OLD_FAILED", "SERVER", "LAYOUT", "PROBE_REQUIRED", "PROBE_AVAILABLE"})
    void precreationProofRejectsUnboundMalformedAndUncertainResults(String change) throws Exception {
        var memory = new Memory();
        String operation = "a".repeat(32);
        var state = memory.state(SERVER, LAYOUT);
        try (var original = precreationRequest(memory, operation);
             var request = new CommandRequest(change.equals("ARGV") ? original.script().replace("'" + operation + "'", "'" + "b".repeat(32) + "'") : original.script(),
                 new byte[0], Duration.ofSeconds(change.equals("REQUEST") ? 99 : 100), 65536)) {
            var action = state.begin("PREPARE", operation, exact(change.equals("REQUEST") ? original : request), null);
            var result = volumeRefusal(operation);
            var command = result.value().orElseThrow();
            String stdout = command.stdout();
            stdout = switch (change) {
                case "DUPLICATE_KEYS" ->
                    stdout.replace("\"stage\":\"VOLUME\"", "\"stage\":\"VOLUME\",\"stage\":\"VOLUME\"");
                case "EXTRA_FIELD" -> stdout.replace("}\n", ",\"extra\":true}\n");
                case "TRAILING_JSON" -> stdout.replace("}\n", "}{}\n");
                case "WRONG_OPERATION" -> stdout.replace(operation, "b".repeat(32));
                case "PROMOTION" -> stdout.replace("VOLUME", "PROMOTION");
                case "OTHER_FAILURE" -> stdout.replace("isolation:HOST_SPACE_REQUIRED", "unknown:HOST_SPACE_REQUIRED");
                case "EXTRA_LINE" -> stdout + "extra\n";
                case "NONCANONICAL" -> stdout.replace("{\"", "{ \"");
                case "AVAILABLE" -> stdout.replace("\"availableBytes\":7", "\"availableBytes\":9663676416");
                case "RESERVE" -> stdout.replace("1073741824", "1073741823");
                case "REQUIRED" -> stdout.replace("9663676416", "9663676415");
                case "PROBE_REQUIRED" -> stdout.replace("9663676416", "1140850687");
                case "PROBE_AVAILABLE" ->
                    stdout.replace("9663676416", "1140850688").replace("\"availableBytes\":7", "\"availableBytes\":1140850688");
                case "FRACTION" -> stdout.replace("\"availableBytes\":7", "\"availableBytes\":7.0");
                default -> stdout;
            };
            var outcome = switch (change) {
                case "CANCELLED" -> TaskResult.CANCELLED;
                case "UNKNOWN" -> TaskResult.UNKNOWN;
                case "SUCCEEDED" -> TaskResult.SUCCEEDED;
                default -> TaskResult.FAILED;
            };
            var actual = new OperationResult<CommandResult>(outcome, change.equals("NO_VALUE") ? Optional.empty() : Optional.of(new CommandResult(
                change.equals("NO_EXIT") ? Optional.empty() : Optional.of(change.equals("EXIT70") ? 70 : 65), stdout,
                change.equals("STDERR") ? " " : "", change.equals("TRUNCATED"))), outcome == TaskResult.SUCCEEDED ? Optional.empty() : result.problem());
            if (Set.of("VERSION", "SOURCE").contains(change)) {
                var checkpoint = memory.checkpoints.get("agent-controller-program." + action.id());
                var body = (ObjectNode) JSON.readTree(checkpoint.value.payload());
                ((ObjectNode) body.path("index")).put(change.equals("VERSION") ? "version" : "sourceSha256", "f".repeat(64));
                checkpoint.value = new TaskCheckpoint.Snapshot(0, body.toString(), false);
            }
            if (change.equals("OLD_FAILED")) state.finish(action, result, false, null);
            var supplied = change.equals("ACTION") ? new DeploymentAgentToolchainProgramState.Action("f".repeat(32), action.checkpoint()) : action;
            var proof = DeploymentAgentToolchainFailureEvidence.precreationRefusal(supplied,
                change.equals("INSTALL") ? "install" : change.equals("PHASE") ? "bind" : "prepare", operation,
                change.equals("TASK") ? UUID.randomUUID().toString() : memory.id.toString(), change.equals("SERVER") ? new ServerId("foreign-server") : SERVER,
                change.equals("LAYOUT") ? DeploymentRemoteLayout.decode(LAYOUT.canonical().replace("/var/lib/windowstolinux", "/var/lib/foreign-controller")) : LAYOUT,
                request, actual);
            assertTrue(proof.isEmpty(), change);
            if (!change.equals("OLD_FAILED")) state.finish(action, actual, false, null);
            assertFalse(action.checkpoint().snapshot().released());
            assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
            assertThrows(ApiException.class, () -> state.begin("PREPARE", "b".repeat(32), "new request", null));
        }
    }

    @Test
    void existingOnlyRefusalRequiresLiveTypedProofAndDurableOriginalResult() throws Exception {
        String operation = "a".repeat(32);
        var old = new Memory();
        try (var request = existingPrecreationRequest(old, operation)) {
            var state = old.state(SERVER, LAYOUT);
            var action = state.begin("PREPARE", operation, exact(request), null);
            state.finish(action, existingRefusal(), false, null);
            assertFalse(action.checkpoint().snapshot().released(), "The old untyped failure remains unconfirmed; no retrospective proof is minted");
            assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(old.tasks, old.task, SERVER.id(), LAYOUT, old.events));
            assertTrue(DeploymentAgentToolchainFailureEvidence.precreationRefusal(action, "prepare", operation,
                old.id.toString(), SERVER, LAYOUT, request, existingRefusal()).isEmpty());
        }
        var memory = new Memory();
        var state = memory.state(SERVER, LAYOUT);
        try (var request = existingPrecreationRequest(memory, operation)) {
            var action = state.begin("PREPARE", operation, exact(request), null);
            var proof = DeploymentAgentToolchainFailureEvidence.precreationRefusal(action, "prepare", operation,
                memory.id.toString(), SERVER, LAYOUT, request, existingRefusal()).orElseThrow();
            memory.transitions.clear();
            state.finish(action, existingRefusal(), false, null, false, proof);
            assertEquals(List.of("ADVANCE", "RESULT", "CLOSE"), memory.transitions);
            assertTrue(action.checkpoint().snapshot().released());
            var original = JSON.readTree(action.checkpoint().snapshot().payload());
            assertEquals("FAILED", original.path("state").asText());
            assertEquals("FAILED", original.path("transportOutcome").asText());
            assertFalse(original.has("receipt"), "No installation or successful SDK receipt is fabricated");
            assertEquals("WTL_AGENT_CONTROLLER_EXISTING_PRECREATION_REFUSAL_1", original.at("/precreationRefusal/format").asText());
            assertEquals(exact(request), original.at("/precreationRefusal/exactAction").asText());
            assertTrue(original.path("confirmationMeaning").asText().contains("SHARED_DIRECTORIES_AND_PREPARE_LOCK_RETAINED"));
            assertTrue(original.path("confirmationMeaning").asText().contains("NOT_CLEANUP_COMPLETE"));
            assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
            var checkpoint = memory.checkpoints.get("agent-controller-program." + action.id());
            var saved = checkpoint.snapshot();
            var indexed = memory.events.getFirst();
            var ended = memory.events.getLast();
            for (String change : List.of("source", "sourceBytes", "body", "request", "operation", "proofIndex", "stdout", "exit", "stderr", "truncated", "problem", "missingResult", "resultHash")) {
                var altered = (ObjectNode) original.deepCopy();
                var retained = (ObjectNode) altered.path(DeploymentAgentToolchainFailureEvidence.PRECREATION);
                switch (change) {
                    case "source" -> retained.put("executedSourceSha256", "f".repeat(64));
                    case "sourceBytes" -> retained.put("executedSourceBytes", 1);
                    case "body" -> retained.put("exactAction", retained.path("exactAction").asText().replace("stdinSha256=", "stdinSha256=f"));
                    case "request" -> retained.put("exactAction", retained.path("exactAction").asText().replace("timeout=", "timeout=PT1S"));
                    case "operation" -> retained.put("exactAction", retained.path("exactAction").asText().replace(operation, "b".repeat(32)));
                    case "proofIndex" -> ((ObjectNode) retained.path("index")).put("task", UUID.randomUUID().toString());
                    case "stdout" -> retained.put("stdout", existingRefusal().value().orElseThrow().stdout() + "extra\n");
                    case "exit" -> altered.put("exit", 0);
                    case "stderr" -> altered.put("stderrCharacters", 1);
                    case "truncated" -> altered.put("truncated", true);
                    case "problem" -> altered.put("problemEvent", "SSH_COMMAND_UNCONFIRMED");
                }
                String payload = altered.toString();
                checkpoint.value = new TaskCheckpoint.Snapshot(saved.revision(), payload, true);
                memory.events.removeLast();
                if (!change.equals("missingResult")) memory.events.add(new DeploymentAgentActionLog.Event(ended.id(), ended.type(), ended.action(), JSON.writeValueAsString(Map.of(
                    "state", "FAILED", "payloadSha256", change.equals("resultHash") ? "f".repeat(64) : DeploymentAgentToolchainPrograms.sha256(payload)))));
                assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events), change);
                if (!change.equals("missingResult")) memory.events.removeLast();
                memory.events.add(ended);
                memory.events.set(0, indexed);
                checkpoint.value = saved;
            }
            var foreign = new Memory();
            var foreignState = foreign.state(SERVER, LAYOUT);
            var foreignAction = foreignState.begin("PREPARE", operation, exact(request), null);
            assertThrows(ApiException.class, () -> foreignState.finish(foreignAction, existingRefusal(), false, null, false, proof));
            assertFalse(foreignAction.checkpoint().snapshot().released());
        }
        for (String failure : List.of("ADVANCE", "RESULT", "CLOSE")) {
            var broken = new Memory();
            var originalState = broken.state(SERVER, LAYOUT);
            try (var request = existingPrecreationRequest(broken, operation)) {
                var action = originalState.begin("PREPARE", operation, exact(request), null);
                var proof = DeploymentAgentToolchainFailureEvidence.precreationRefusal(action, "prepare", operation,
                    broken.id.toString(), SERVER, LAYOUT, request, existingRefusal()).orElseThrow();
                broken.failAdvance = failure.equals("ADVANCE");
                broken.failResult = failure.equals("RESULT");
                broken.failClose = failure.equals("CLOSE");
                assertThrows(ApiException.class, () -> originalState.finish(action, existingRefusal(), false, null, false, proof), failure);
                assertFalse(action.checkpoint().snapshot().released());
                assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(broken.tasks, broken.task, SERVER.id(), LAYOUT, broken.events));
            }
        }
        var changedResult = new Memory();
        var changedState = changedResult.state(SERVER, LAYOUT);
        try (var request = existingPrecreationRequest(changedResult, operation)) {
            var action = changedState.begin("PREPARE", operation, exact(request), null);
            var proof = DeploymentAgentToolchainFailureEvidence.precreationRefusal(action, "prepare", operation,
                changedResult.id.toString(), SERVER, LAYOUT, request, existingRefusal()).orElseThrow();
            var different = new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.of(65),
                existingRefusal().value().orElseThrow().stdout() + "extra\n", "", false)), existingRefusal().problem());
            assertThrows(ApiException.class, () -> changedState.finish(action, different, false, null, false, proof));
            assertFalse(action.checkpoint().snapshot().released());
            assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(changedResult.tasks, changedResult.task, SERVER.id(), LAYOUT, changedResult.events));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "DEFAULT_SOURCE", "REQUEST", "ARGV", "BRANCH", "DESCRIPTOR", "PHASE", "TASK", "ACTION", "INDEX_SOURCE", "SERVER", "LAYOUT",
        "CANCELLED", "UNKNOWN", "SUCCEEDED", "NO_VALUE", "NO_EXIT", "EXIT70", "TRUNCATED", "STDERR", "EXTRA_LINE", "OTHER_FAILURE", "OUTPUT_LIMIT", "PROBLEM"})
    void existingOnlyRefusalRejectsUnboundAndUncertainEvidence(String change) throws Exception {
        var memory = new Memory();
        String operation = "a".repeat(32);
        var state = memory.state(SERVER, LAYOUT);
        try (var original = existingPrecreationRequest(memory, operation)) {
            String source = DeploymentAgentToolchainPrograms.existingController(LAYOUT);
            String script = original.script();
            if (change.equals("SOURCE")) source += "\n# changed\n";
            if (change.equals("DEFAULT_SOURCE")) source = DeploymentAgentToolchainPrograms.controller(LAYOUT);
            if (change.equals("ARGV")) script = script.replace("'" + operation + "'", "'" + "b".repeat(32) + "'");
            if (change.equals("BRANCH")) script = script.replace("'1.24'", "'1.25'");
            if (change.equals("DESCRIPTOR")) script = script.replace(existingRelease("GO", "1.24", "1.24.13", operation), existingRelease("NODE", "24", "24.22.0", operation));
            try (var request = new CommandRequest(script, source.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Duration.ofSeconds(change.equals("REQUEST") ? 99 : 100), change.equals("OUTPUT_LIMIT") ? 65535 : 65536)) {
                var action = state.begin("PREPARE", operation, exact(change.equals("REQUEST") ? original : request), null);
                if (change.equals("INDEX_SOURCE")) {
                    var checkpoint = memory.checkpoints.get("agent-controller-program." + action.id());
                    var body = (ObjectNode) JSON.readTree(checkpoint.value.payload());
                    ((ObjectNode) body.path("index")).put("sourceSha256", "f".repeat(64));
                    checkpoint.value = new TaskCheckpoint.Snapshot(0, body.toString(), false);
                }
                var outcome = switch (change) {
                    case "CANCELLED" -> TaskResult.CANCELLED;
                    case "UNKNOWN" -> TaskResult.UNKNOWN;
                    case "SUCCEEDED" -> TaskResult.SUCCEEDED;
                    default -> TaskResult.FAILED;
                };
                String stdout = existingRefusal().value().orElseThrow().stdout();
                if (change.equals("EXTRA_LINE")) stdout += "extra\n";
                if (change.equals("OTHER_FAILURE")) stdout = stdout.replace("EXISTING_INSTALLATION_REQUIRED", "INSTALLATION_SELECTION_CHANGED");
                var result = new OperationResult<CommandResult>(outcome, change.equals("NO_VALUE") ? Optional.empty() : Optional.of(new CommandResult(
                    change.equals("NO_EXIT") ? Optional.empty() : Optional.of(change.equals("EXIT70") ? 70 : 65), stdout,
                    change.equals("STDERR") ? " " : "", change.equals("TRUNCATED"))), outcome == TaskResult.SUCCEEDED ? Optional.empty()
                        : change.equals("PROBLEM") ? Optional.of(new Problem(ProblemCode.UNKNOWN, "SSH_COMMAND_UNCONFIRMED", "unconfirmed", Map.of(), Optional.empty())) : existingRefusal().problem());
                var supplied = change.equals("ACTION") ? new DeploymentAgentToolchainProgramState.Action("f".repeat(32), action.checkpoint()) : action;
                assertTrue(DeploymentAgentToolchainFailureEvidence.precreationRefusal(supplied, change.equals("PHASE") ? "bind" : "prepare", operation,
                    change.equals("TASK") ? UUID.randomUUID().toString() : memory.id.toString(), change.equals("SERVER") ? new ServerId("foreign") : SERVER,
                    change.equals("LAYOUT") ? DeploymentRemoteLayout.decode(LAYOUT.canonical().replace("/var/lib/windowstolinux", "/var/lib/foreign")) : LAYOUT,
                    request, result).isEmpty(), change);
                state.finish(action, result, false, null);
                assertFalse(action.checkpoint().snapshot().released());
                assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
                assertThrows(ApiException.class, () -> state.begin("READINESS", "b".repeat(32), "later Node readiness", null));
            }
        }
    }

    static CommandRequest existingPrecreationRequest(Memory memory, String operation) {
        return new CommandRequest(DeploymentAgentToolchainPrograms.command(List.of("prepare", "GO", "1.24", "1.24.13", "85", "-", operation,
            existingRelease("GO", "1.24", "1.24.13", operation))),
            DeploymentAgentToolchainPrograms.existingController(LAYOUT).getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ofSeconds(100), 65536);
    }

    static String existingRelease(String ecosystem, String branch, String exact, String operation) {
        var values = new TreeMap<String, String>();
        values.put("format", "WTL_AGENT_RELEASE_1");
        values.put("operation", operation);
        values.put("ecosystem", ecosystem);
        values.put("branch", branch);
        values.put("requestedExact", exact);
        values.put("version", exact);
        values.put("url", ecosystem.equals("GO") ? "https://go.dev/dl/go" + exact + ".linux-amd64.tar.gz"
            : "https://nodejs.org/dist/v" + exact + "/node-v" + exact + "-linux-x64.tar.xz");
        values.put("algorithm", "sha256");
        values.put("digest", "c".repeat(64));
        values.put("layout", "archive");
        values.put("platform", "linux-x86_64");
        values.put("resolverSourceSha256", DeploymentAgentToolchainPrograms.sha256(DeploymentAgentToolchainPrograms.resolver(LAYOUT)));
        try {
            return Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(values));
        } catch (java.io.IOException invalid) {
            throw new AssertionError(invalid);
        }
    }

    static OperationResult<CommandResult> existingRefusal() {
        return new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.of(65),
            "TOOLCHAIN_FAILURE=isolation:EXISTING_INSTALLATION_REQUIRED\n", "", false)),
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO", "controlled original exit", Map.of(), Optional.empty())));
    }

    static CommandRequest precreationRequest(Memory memory, String operation) throws Exception {
        var arguments = List.of("prepare", "JAVA", "21", "21.0.12.1+1", "85", "-", operation,
            DeploymentAgentToolchainReleaseTest.encoded("JAVA", "21", "21.0.12.1+1", operation));
        return new CommandRequest(DeploymentAgentToolchainProgramStore.command("prepare", memory.id.toString(), operation, arguments, LAYOUT),
            new byte[0], Duration.ofSeconds(100), 65536);
    }

    private static String exact(CommandRequest request) {
        return RemoteActions.command(SERVER, "controller-action-state", request).exactAction();
    }

    static OperationResult<CommandResult> volumeRefusal(String operation) {
        return volumeRefusal(operation, 9L * 1024 * 1024 * 1024);
    }

    static OperationResult<CommandResult> volumeRefusal(String operation, long requiredBytes) {
        try {
            var fields = new TreeMap<String, Object>(Map.of("operation", operation, "phase", "HOST_SPACE_REQUIRED", "stage", "VOLUME",
                "availableBytes", 7, "requiredBytes", requiredBytes, "reserveBytes", 1024L * 1024 * 1024));
            return new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.of(65),
                "TOOLCHAIN_FAILURE=isolation:HOST_SPACE_REQUIRED\nTOOLCHAIN_DIAGNOSTIC=" + JSON.writeValueAsString(fields) + "\n", "", false)),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO", "controlled original exit", Map.of(), Optional.empty())));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    static OperationResult<CommandResult> success(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }

    public static final class Memory {
        public final UUID id;

        public Memory() {
            this(UUID.randomUUID());
        }

        public Memory(UUID id) {
            this.id = id;
        }

        public final List<DeploymentAgentActionLog.Event> events = new ArrayList<>();

        final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();

        boolean failBegin, failResult, failAdvance, failClose;

        final List<String> transitions = new ArrayList<>();

        public final TaskContext task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class}, (p, m, a) -> {
            if (m.getName().equals("id")) return identity();
            throw new AssertionError(m.getName());
        });

        public final TaskService tasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(), new Class<?>[]{TaskService.class}, (p, m, a) -> {
            assertSame(task, a[0]);
            if (m.getName().equals("findCheckpoint")) return Optional.ofNullable(checkpoints.get(a[1]));
            if (m.getName().equals("beginCheckpoint")) {
                if (failBegin) throw new IllegalStateException("controlled persistence failure");
                var value = new Checkpoint((String) a[2], this);
                assertNull(checkpoints.putIfAbsent((String) a[1], value));
                return value;
            }
            throw new AssertionError(m.getName());
        });

        public DeploymentAgentToolchainProgramState state(ServerId server, DeploymentRemoteLayout layout) {
            return new DeploymentAgentToolchainProgramState(tasks, task, server, layout, (type, action, detail) -> {
                if (failResult && type.equals("CONTROLLER_PROGRAM_RESULT"))
                    throw new IllegalStateException("controlled journal failure");
                if (type.equals("CONTROLLER_PROGRAM_RESULT")) transitions.add("RESULT");
                events.add(new DeploymentAgentActionLog.Event(events.size() + 1L, type, action, detail));
            }, () -> List.copyOf(events));
        }

        public Object receipts(ServerId server, DeploymentRemoteLayout layout) throws Exception {
            return Map.of(DeploymentAgentToolchainProgramState.FIELD, DeploymentAgentToolchainProgramState.snapshot(tasks, task, server.id(), layout, events));
        }

        public void pending(ServerId server, DeploymentRemoteLayout layout, String phase, String operation, String exact) throws Exception {
            state(server, layout).begin(phase, operation, exact, null);
        }

        public void pending(ServerId server, DeploymentRemoteLayout layout, String phase, String operation, String exact, JsonNode installation) throws Exception {
            state(server, layout).begin(phase, operation, exact, installation);
        }

        public void observed(ServerId server, DeploymentRemoteLayout layout, JsonNode observation) throws Exception {
            var state = state(server, layout);
            var action = state.begin("READINESS", observation.path("action").asText(), "controlled original readiness", null);
            state.finish(action, success("AGENT_CONTROLLER_STORE=" + observation + "\n"), true, observation);
        }

        private UUID identity() {
            return id;
        }
    }

    static final class Checkpoint implements TaskCheckpoint {
        Snapshot value;

        private final Memory memory;

        Checkpoint(String payload, Memory memory) {
            this.memory = memory;
            value = new Snapshot(0, payload, false);
        }

        public Snapshot snapshot() {
            return value;
        }

        public Snapshot advance(Snapshot expected, String next) {
            if (memory.failAdvance) throw new IllegalStateException("controlled checkpoint failure");
            assertEquals(value, expected);
            assertFalse(value.released());
            memory.transitions.add("ADVANCE");
            return value = new Snapshot(value.revision() + 1, next, false);
        }

        public void close() {
            if (memory.failClose) throw new IllegalStateException("controlled release failure");
            memory.transitions.add("CLOSE");
            value = new Snapshot(value.revision() + 1, value.payload(), true);
        }
    }
}

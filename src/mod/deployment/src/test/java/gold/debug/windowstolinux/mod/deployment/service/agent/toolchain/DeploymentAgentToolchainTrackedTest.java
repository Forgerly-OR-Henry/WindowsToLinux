package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentToolchainTrackedTest {
    static final String FINGERPRINT = "SHA256:" + "B".repeat(43);
    static final String OPERATION = "a".repeat(32);
    static final ServerId SERVER = DeploymentAgentToolchainProgramStateTest.SERVER;
    static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();

    static DeploymentAgentToolchainTracked.Recipe recipe(String phase, String task) {
        var args = phase.equals("PREPARE") ? List.of("prepare", "GO", "1.24", "1.24.13", "90", "-", OPERATION,
            DeploymentAgentToolchainReleaseTest.encoded("GO", "1.24", "1.24.13", OPERATION))
            : List.of("bind", DeploymentAgentToolchainCommandsTest.binding(), "90", OPERATION);
        return DeploymentAgentToolchainTracked.create(task, SERVER.id(), FINGERPRINT, LAYOUT, phase, OPERATION, args, false);
    }

    static ObjectNode observation(DeploymentAgentToolchainTracked.Recipe recipe, String verb, String execution, boolean settled) {
        var value = DeploymentAgentToolchainTracked.JSON.createObjectNode().put("formatVersion", 1).put("verb", verb);
        var request = recipe.value();
        for (String key : List.of("taskId", "serverId", "fingerprintSha256", "phase", "operationId", "version", "sourceSha256", "executedSourceSha256", "invocationKind")) value.set(key, request.get(key));
        value.put("layoutSha256", recipe.layout().digest()).put("requestSha256", recipe.sha256()).put("proofState", "VERIFIED")
            .put("executionStatus", execution).put("effectState", settled ? "SETTLED_RETAINED_EFFECTS" : "UNSETTLED")
            .put("cleanupState", settled ? verb.equals("observe-tracked") || verb.equals("cleanup-tracked") ? "VERIFIED" : "NOT_NEEDED" : "REQUIRED")
            .put("creationSha256", "c".repeat(64)).put("stateSha256", "d".repeat(64)).putNull("failureCode");
        var resources = value.putObject("resources");
        for (String key : List.of("unit", "cgroup", "image", "loop", "mount", "placeholder", "promotionTemporary", "bindingTemporary", "children")) resources.put(key, "ABSENT");
        if (!settled) resources.put("image", "OWNED_PRESENT");
        var effects = value.putArray("retainedEffects"); effects.add(recipe.phase().equals("PREPARE") ? "SHARED_INSTALLATION" : "IMMUTABLE_BINDING");
        if (recipe.phase().equals("PREPARE")) {
            var args = recipe.originalArgv();
            var release = DeploymentAgentToolchainRelease.decode(args.get(7), gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.valueOf(args.get(1)),
                args.get(2), args.get(3), recipe.operationId(), recipe.layout());
            value.putNull("bindingSha256"); value.putObject("selection").put("ecosystem", "GO").put("version", "1.24.13")
                .put("ecosystem", args.get(1)).put("version", release.get("version"))
                .put("directory", recipe.layout().toolchains() + "/versions/" + args.get(1).toLowerCase(Locale.ROOT) + "-" + "b".repeat(64)).put("origin", "MANAGED")
                .put("source", release.get("url")).put("sha256", "b".repeat(64));
        } else value.putNull("selection").put("bindingSha256", DeploymentAgentToolchainTracked.hash(Base64.getDecoder().decode(recipe.originalArgv().get(1))));
        return value;
    }

    static OperationResult<CommandResult> result(ObjectNode receipt) {
        return DeploymentAgentToolchainProgramStateTest.success("AGENT_TOOLCHAIN_TRACKED=" + receipt + "\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARE", "BIND"})
    void exactRecipeAndPhysicalSuccessRequireOriginalBytesAndRetainedEffect(String phase) throws Exception {
        var recipe = recipe(phase, UUID.randomUUID().toString());
        assertArrayEquals(recipe.bytes(), DeploymentAgentToolchainTracked.decode(recipe.bytes()).bytes());
        var value = observation(recipe, phase.toLowerCase(Locale.ROOT) + "-tracked", "SUCCEEDED", true);
        assertTrue(DeploymentAgentToolchainTracked.receipt(result(value), recipe, value.path("verb").asText()).succeeded());
        for (String mutation : List.of("unknown", "duplicate", "newline", "foreign", "source")) {
            String raw = new String(recipe.bytes(), StandardCharsets.UTF_8);
            raw = switch (mutation) {
                case "unknown" -> raw.replace("\"formatVersion\":1", "\"formatVersion\":1,\"unknown\":true");
                case "duplicate" -> raw.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1");
                case "newline" -> raw + "\n";
                case "source" -> raw.replace("\"executedSourceSha256\":\"" + recipe.executedSourceSha256(), "\"executedSourceSha256\":\"" + "e".repeat(64));
                default -> raw.replace(OPERATION, "INVALID");
            };
            byte[] invalid = raw.getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainTracked.decode(invalid), mutation);
        }
        for (String mutation : List.of("missing-effect", "resource", "proof", "request", "extra", "stderr", "truncated")) {
            var changed = value.deepCopy();
            if (mutation.equals("missing-effect")) { changed.putArray("retainedEffects"); changed.put("effectState", "SETTLED_NO_EFFECTS"); }
            if (mutation.equals("resource")) ((ObjectNode) changed.path("resources")).put("mount", "OWNED_PRESENT");
            if (mutation.equals("proof")) changed.put("proofState", "MISSING");
            if (mutation.equals("request")) changed.put("requestSha256", "f".repeat(64));
            if (mutation.equals("extra")) changed.put("unknown", true);
            var actual = new OperationResult<CommandResult>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0),
                result(changed).value().orElseThrow().stdout(), mutation.equals("stderr") ? " " : "", mutation.equals("truncated"))), Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainTracked.receipt(actual, recipe, value.path("verb").asText()), mutation);
        }
    }

    @Test
    void originalServerIdentityIsBoundedByUtf8BytesAndFrameTextRejectsAsciiControls() {
        var original = recipe("PREPARE", UUID.randomUUID().toString());
        String boundary = "服".repeat(170) + "ab";
        assertEquals(512, boundary.getBytes(StandardCharsets.UTF_8).length);
        var allowed = original.value().put("serverId", boundary);
        assertEquals(boundary, DeploymentAgentToolchainTracked.decode(allowed.toString().getBytes(StandardCharsets.UTF_8)).serverId());
        var oversized = original.value().put("serverId", "服".repeat(171));
        assertEquals(513, oversized.path("serverId").textValue().getBytes(StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainTracked.decode(oversized.toString().getBytes(StandardCharsets.UTF_8)));
        for (int code = 0; code <= 127; code++) {
            if (code >= 32 && code != 127) continue;
            char control = (char) code;
            var server = original.value().put("serverId", "server" + control);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainTracked.decode(server.toString().getBytes(StandardCharsets.UTF_8)), "server control " + code);
            var argument = original.value();
            ((com.fasterxml.jackson.databind.node.ArrayNode) argument.path("originalArgv")).set(5, DeploymentAgentToolchainTracked.JSON.getNodeFactory().textNode("-" + control));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainTracked.decode(argument.toString().getBytes(StandardCharsets.UTF_8)), "argv control " + code);
        }
    }

    @Test
    void durableRecoverySettlesEffectsWithoutReplacingOriginalUnknownResult() throws Exception {
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory(); var state = memory.state(SERVER, LAYOUT);
        var recipe = recipe("BIND", memory.id.toString());
        var action = state.begin("BIND", OPERATION, "original exact", null, recipe, null);
        assertEquals("CONTROLLER_BLOB_PART", memory.events.getFirst().type());
        assertTrue(memory.events.stream().filter(e -> e.type().equals("CONTROLLER_BLOB_END")).findFirst().orElseThrow().id()
            < memory.events.stream().filter(e -> e.type().equals("CONTROLLER_PROGRAM_ACTION")).findFirst().orElseThrow().id());
        state.dispatched(action); assertThrows(ApiException.class, () -> memory.state(SERVER, LAYOUT).dispatched(action));
        var unknown = new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "ACK_LOST", "actual original transport", Map.of(), Optional.empty())));
        state.transport(action, unknown); state.finish(action, unknown, false, null);
        String original = action.checkpoint().snapshot().payload();
        var ended = memory.events.getLast();
        state = memory.state(SERVER, LAYOUT);
        String recovery = state.recoveryIntent(action.id(), "observe-tracked", "observe exact");
        String hash = DeploymentAgentToolchainPrograms.sha256("observe exact");
        state.recoveryEvent(action.id(), recovery, "observe-tracked", "DISPATCHED", hash, null);
        var receipt = observation(recipe, "observe-tracked", "UNKNOWN", true);
        state.recoveredTransport(recovery, result(receipt));
        state.recoveryEvent(action.id(), recovery, "observe-tracked", "OBSERVED", hash, receipt);
        state.settle(action.id(), DeploymentAgentToolchainTracked.receipt(result(receipt), recipe, "observe-tracked"));
        assertTrue(action.checkpoint().snapshot().released());
        assertEquals(original, action.checkpoint().snapshot().payload());
        assertTrue(memory.events.contains(ended));
        assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, memory.events));
        var incomplete = new ArrayList<>(memory.events); incomplete.removeIf(e -> e.type().equals("CONTROLLER_BLOB_PART") && e.action().equals(recovery));
        assertTrue(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, SERVER.id(), LAYOUT, incomplete));
    }

    @Test
    void cleanupConsumptionAndWorkerFailureAreIndependentOfSdkExitZero() throws Exception {
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory(); var state = memory.state(SERVER, LAYOUT);
        var recipe = recipe("PREPARE", memory.id.toString());
        var action = state.begin("PREPARE", OPERATION, "original exact", null, recipe, null); state.dispatched(action);
        var receipt = observation(recipe, "prepare-tracked", "FAILED", true); var actual = result(receipt);
        state.transport(action, actual); state.finish(action, actual, true, receipt);
        var failed = DeploymentAgentToolchainFailureEvidence.tracked(actual, DeploymentAgentToolchainTracked.receipt(actual, recipe, "prepare-tracked"),
            action.id(), action.checkpoint().snapshot().released(), action.checkpoint().snapshot().payload());
        assertEquals(TaskResult.FAILED, failed.outcome()); assertTrue(DeploymentAgentToolchainFailureEvidence.settledTrackedFailure(failed.problem()));
        assertEquals(Optional.of(0), failed.value().orElseThrow().exitCode());
        assertEquals(actual.value().orElseThrow().stdout(), failed.value().orElseThrow().stdout());
        assertEquals(TaskResult.UNKNOWN, DeploymentAgentToolchainFailureEvidence.tracked(actual,
            DeploymentAgentToolchainTracked.receipt(result(observation(recipe, "prepare-tracked", "FAILED", false)), recipe, "prepare-tracked"),
            action.id(), false, action.checkpoint().snapshot().payload()).outcome());
        String cleanup = state.recoveryIntent(action.id(), "cleanup-tracked", "cleanup exact");
        state.recoveryEvent(action.id(), cleanup, "cleanup-tracked", "DISPATCHED", DeploymentAgentToolchainPrograms.sha256("cleanup exact"), null);
        assertThrows(IllegalArgumentException.class, () -> memory.state(SERVER, LAYOUT).recoveryIntent(action.id(), "cleanup-tracked", "cleanup exact again"));
    }
}

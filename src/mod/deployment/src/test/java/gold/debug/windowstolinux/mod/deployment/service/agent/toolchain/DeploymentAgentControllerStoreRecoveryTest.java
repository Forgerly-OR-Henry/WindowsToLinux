package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentControllerStoreRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings = {"readiness", "install"})
    void completeOriginalStoreFrameAndExactInstallationOwnerCanSettleWithoutRewritingUnknown(String phase) throws Exception {
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory();
        var server = DeploymentAgentToolchainTrackedTest.SERVER; var layout = DeploymentAgentToolchainTrackedTest.LAYOUT;
        String operation = "a".repeat(32), task = memory.id.toString();
        String script = phase.equals("install") ? DeploymentAgentToolchainPrograms.command(List.of(task, operation))
            : DeploymentAgentToolchainProgramStore.command("readiness", task, operation, List.of(), layout);
        byte[] input = phase.equals("install") ? DeploymentAgentToolchainProgramStore.installer(layout).getBytes(StandardCharsets.UTF_8) : new byte[0];
        try (var request = new CommandRequest(script, input, Duration.ofSeconds(30), 65536)) {
            var frame = DeploymentAgentToolchainTracked.JSON.createObjectNode().put("formatVersion", 1).put("task", task).put("server", server.id())
                .put("fingerprintSha256", DeploymentAgentToolchainTracked.hash(DeploymentAgentToolchainTrackedTest.FINGERPRINT.getBytes(StandardCharsets.UTF_8)))
                .put("layout", layout.canonical()).put("phase", phase).put("operation", operation).put("version", DeploymentAgentToolchainProgramStore.version(layout))
                .put("sourceSha256", DeploymentAgentToolchainProgramStore.sourceSha256(layout)).put("scriptSha256", DeploymentAgentToolchainPrograms.sha256(script))
                .put("stdinSha256", DeploymentAgentToolchainTracked.hash(input)).put("stdinBytes", input.length).put("timeout", request.timeout().toString()).put("outputLimit", 65536L);
            var state = memory.state(server, layout);
            var action = state.begin(phase.toUpperCase(Locale.ROOT), operation, RemoteActions.command(server, "state", request).exactAction(), null, null, frame);
            state.dispatched(action);
            var unknown = new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                "STORE_ACK_LOST", "Original store transport remained unknown", Map.of(), Optional.empty())));
            state.transport(action, unknown); state.finish(action, unknown, false, null);
            String original = action.checkpoint().snapshot().payload(); var result = memory.events.getLast();
            var missing = DeploymentAgentToolchainProgramStoreTest.observation(task, operation, false);
            assertThrows(IllegalArgumentException.class, () -> state.settleStore(action.id(), missing));
            if (phase.equals("install")) {
                var foreign = DeploymentAgentToolchainProgramStoreTest.observation(task, operation, true);
                assertThrows(IllegalArgumentException.class, () -> state.settleStore(action.id(), foreign), "Another installation owner cannot confirm the original install");
            }
            var ready = DeploymentAgentToolchainProgramStoreTest.observation(task, operation, true).put("installTask", task).put("installOperation", operation);
            String exact = "new fixed readiness observation", hash = DeploymentAgentToolchainPrograms.sha256(exact);
            String recovery = state.recoveryIntent(action.id(), "readiness", exact);
            state.recoveryEvent(action.id(), recovery, "readiness", "DISPATCHED", hash, null);
            state.recoveredTransport(recovery, DeploymentAgentToolchainProgramStateTest.success("AGENT_CONTROLLER_STORE=" + ready + "\n"));
            state.recoveryEvent(action.id(), recovery, "readiness", "OBSERVED", hash, ready);
            state.settleStore(action.id(), ready);
            assertTrue(action.checkpoint().snapshot().released()); assertEquals(original, action.checkpoint().snapshot().payload());
            assertTrue(memory.events.contains(result));
            assertFalse(DeploymentAgentToolchainProgramState.hasUnconfirmed(memory.tasks, memory.task, server.id(), layout, memory.events));
            var changed = (ObjectNode) DeploymentAgentToolchainTracked.JSON.readTree(original);
            ((ObjectNode) changed.path("storeFrame")).put("timeout", "PT31S");
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainProgramState.validateStoreFrame(changed), "The retained original exact request cannot be guessed from an index");
        }
    }
}

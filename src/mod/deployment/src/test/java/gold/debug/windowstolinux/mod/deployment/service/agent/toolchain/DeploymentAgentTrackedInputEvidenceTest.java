package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentTrackedInputEvidenceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeOriginalRecipeAndActualGuardedSourceAreBothRequiredBeforeDispatch(boolean inline) throws Exception {
        var memory = new DeploymentAgentToolchainProgramStateTest.Memory();
        var server = DeploymentAgentToolchainTrackedTest.SERVER; var layout = DeploymentRemoteLayout.original();
        var state = memory.state(server, layout);
        var ready = DeploymentAgentToolchainProgramStoreTest.observation(memory.id.toString(), "b".repeat(32), true);
        memory.observed(server, layout, ready);
        var base = DeploymentAgentToolchainTrackedTest.recipe("PREPARE", memory.id.toString());
        var recipe = inline ? DeploymentAgentToolchainTracked.create(memory.id.toString(), server.id(), DeploymentAgentToolchainTrackedTest.FINGERPRINT,
            layout, "PREPARE", base.operationId(), base.originalArgv(), true) : base;
        byte[] input = inline ? DeploymentAgentToolchainPrograms.trackedInput(recipe) : recipe.bytes();
        String script = inline ? DeploymentAgentToolchainPrograms.trackedCommand(recipe) : DeploymentAgentToolchainProgramStore.trackedCommand("prepare-tracked", recipe);
        String operation = inline ? "deployment.agent.toolchain.prepare.v1" : DeploymentAgentToolchainProgramStore.PREFIX + "prepare.v1";
        try (var request = new CommandRequest(script, input, Duration.ofSeconds(105), 65536)) {
            var action = state.begin("PREPARE", recipe.operationId(), RemoteActions.command(server, "state", request).exactAction(), ready, recipe, null);
            var targets = inline ? DeploymentAgentToolchainCommands.controllerTargets(server, recipe.operationId(), layout)
                : DeploymentAgentToolchainProgramStore.targets(server, "prepare", recipe.operationId(), recipe.operationId(), layout);
            var intent = RemoteActions.command(server, "e".repeat(64), request).withSemantics(new ActionSemantics(operation, targets));
            var facts = DeploymentAgentToolchainTracked.JSON.valueToTree(DeploymentAgentCommandProtocol.facts(intent, request, layout));
            var receipts = DeploymentAgentToolchainTracked.JSON.valueToTree(memory.receipts(server, layout));
            DeploymentAgentToolchainInputEvidence.requireTrackedCurrent(facts, layout, receipts, memory.id.toString());
            assertEquals("TRACKED_TOOLCHAIN_REQUEST", facts.path("inputKind").asText());
            if (inline) assertNotEquals(recipe.sourceSha256(), recipe.executedSourceSha256());
            for (String mutation : List.of("task", "source", "targets", "recipe", "kind", "metadata-extra", "metadata-kind", "metadata-source", "metadata-missing")) {
                var changed = facts.deepCopy();
                if (mutation.equals("source")) {
                    if (inline) ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("completeExecutableInput", "altered executable");
                    else ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("exactAction", intent.exactAction() + " ");
                }
                if (mutation.equals("targets")) ((com.fasterxml.jackson.databind.node.ObjectNode) changed).putArray("resourceTargets");
                if (mutation.equals("recipe")) ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path(DeploymentAgentToolchainTracked.FIELD)).put("recipeUtf8", new String(recipe.bytes(), StandardCharsets.UTF_8) + "\n");
                if (mutation.equals("kind")) ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("inputKind", "EMPTY");
                var metadata = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path(DeploymentAgentToolchainTracked.FIELD);
                if (mutation.equals("metadata-extra")) metadata.put("unreviewed", true);
                if (mutation.equals("metadata-kind")) metadata.put("invocationKind", "UNREVIEWED");
                if (mutation.equals("metadata-source")) metadata.put("executedSourceSha256", "f".repeat(64));
                if (mutation.equals("metadata-missing")) metadata.remove("executedSourceSha256");
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.requireTrackedCurrent(changed, layout, receipts,
                    mutation.equals("task") ? UUID.randomUUID().toString() : memory.id.toString()), mutation);
            }
            state.dispatched(action);
            var dispatched = DeploymentAgentToolchainTracked.JSON.valueToTree(memory.receipts(server, layout));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.requireTrackedCurrent(facts, layout, dispatched, memory.id.toString()),
                "The same prepare request cannot obtain another executable grant after its original dispatch");
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentToolchainSources;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentDirectoryToolchainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ServerId SERVER = new ServerId("controlled-server");
    private static final String OPERATION = "a".repeat(32);
    private static final DeploymentRemoteLayout LAYOUT = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));

    @Test
    void completeControllerAndResolverUseSelectedMetadataAndSandboxRootsWithOriginalWrappers() {
        assertEquals(DeploymentAgentToolchainPrograms.controller(DeploymentRemoteLayout.original()), DeploymentAgentToolchainPrograms.controller());
        assertEquals(DeploymentAgentToolchainPrograms.resolver(DeploymentRemoteLayout.original()), DeploymentAgentToolchainPrograms.resolver());
        String resolver = DeploymentAgentToolchainPrograms.resolver(LAYOUT);
        String controller = DeploymentAgentToolchainPrograms.controller(LAYOUT);
        assertTrue(resolver.startsWith(DeploymentToolchainSources.metadata(LAYOUT)));
        assertTrue(controller.startsWith(DeploymentToolchainSources.metadata(LAYOUT)));
        assertTrue(controller.contains("EXPECTED_RESOLVER_SOURCE_SHA256 = '" + DeploymentAgentToolchainPrograms.sha256(resolver) + "'"));
        assertTrue(controller.contains("-/srv/wtl/apps -/srv/wtl/config -/srv/wtl/data"));
        assertFalse(controller.contains("/usr/local/lib/windowstolinux"));
        assertFalse(controller.contains("@wtl:"));
        DeploymentAgentToolchainCommands.requirePreparation(DeploymentToolchainType.RUST, "1.98", "-", 60,
            LAYOUT.toolchains() + "/versions/java-" + "b".repeat(64), LAYOUT);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainCommands.requirePreparation(
            DeploymentToolchainType.RUST, "1.98", "-", 60, DeploymentRemoteLayout.original().toolchains() + "/versions/java-" + "b".repeat(64), LAYOUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolve", "dependencies"})
    void customInlineEvidenceRequiresExactCurrentLayoutBytesArgumentsAndTargets(String phase) {
        String body = phase.equals("resolve") ? DeploymentAgentToolchainPrograms.resolver(LAYOUT) : DeploymentAgentToolchainPrograms.dependencies(LAYOUT);
        List<String> arguments = phase.equals("resolve") ? List.of("resolve", "RUST", "1.98", "-", "45", OPERATION) : List.of("NATIVE_BUILD", OPERATION);
        var targets = phase.equals("resolve") ? DeploymentAgentToolchainCommands.resolverTargets(SERVER, DeploymentToolchainType.RUST, "1.98", "-")
            : DeploymentAgentToolchainCommands.dependencyTargets(SERVER, "NATIVE_BUILD");
        try (var request = new CommandRequest(DeploymentAgentToolchainPrograms.command(arguments), body.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 65536)) {
            ObjectNode current = JSON.createObjectNode().put("operation", "deployment.agent.toolchain." + phase + ".v1")
                .put("businessRevision", "c".repeat(64)).put("inputKind", "EXECUTABLE_SCRIPT").put("completeExecutableInput", body)
                .put("exactAction", RemoteActions.command(SERVER, "c".repeat(64), request).exactAction());
            current.set("serverTargets", JSON.valueToTree(Set.of(SERVER.id())));
            current.set("resourceTargets", JSON.valueToTree(targets));
            var facts = JSON.valueToTree(DeploymentAgentToolchainInputEvidence.select(current, LAYOUT).orElseThrow().facts());
            assertEquals(LAYOUT.canonical(), facts.at("/originalRemoteLayout/canonical").asText());
            assertEquals(LAYOUT.digest(), facts.at("/originalRemoteLayout/sha256").asText());
            assertFalse(facts.has("permission"));
            if (phase.equals("resolve")) assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(current));
            current.put("completeExecutableInput", body + "\n# altered");
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(current, LAYOUT));
        }
        assertTrue(DeploymentAgentToolchainCommands.controllerTargets(SERVER, OPERATION, LAYOUT).stream()
            .filter(value -> value.kind().equals("deployment-build")).allMatch(value -> value.id().startsWith(LAYOUT.toolchains())));
    }
}

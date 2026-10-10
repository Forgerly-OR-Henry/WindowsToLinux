package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentToolchainEvidenceTest {
    @ParameterizedTest
    @ValueSource(strings = {"dependencies", "prepare", "bind"})
    void everyAgentOperationCarriesCompleteCurrentExecutableBytes(String phase) throws Exception {
        var server = new ServerId("original-server");
        String source = phase.equals("dependencies") ? DeploymentAgentToolchainPrograms.dependencies() : DeploymentAgentToolchainPrograms.controller();
        try (var request = new CommandRequest("/usr/bin/python3 -I -S -c 'fixed reviewed bootstrap'", source.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30), 65536)) {
            var intent = RemoteActions.command(server, "a".repeat(64), request).withSemantics(new ActionSemantics("deployment.agent.toolchain." + phase + ".v1",
                Set.of(new ActionTarget(server.id(), "deployment-build", "/usr/local/lib/windowstolinux/toolchains"))));
            assertTrue(DeploymentFixedCommandApproval.registered(intent));
            var evidence = DeploymentCommandEvidence.facts(intent, request);
            assertEquals("EXECUTABLE_SCRIPT", evidence.get("inputKind"));
            assertEquals(source, evidence.get("completeExecutableInput"));
            assertEquals(intent.exactAction(), evidence.get("exactAction"));
            assertFalse(evidence.containsKey("installedHelperSha256"));
            assertFalse(evidence.containsKey("agentToolchainPreparationEvidence"));
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup;
import org.junit.jupiter.api.Test;

class DeploymentVerifiedToolchainSetupTest {
    private static final ToolchainRequirement REQUIREMENT = ToolchainRequirement.declared(DeploymentToolchainType.GO,
        ">=1.24.9", "go.mod", ToolchainRequirement.PurposeType.LANGUAGE_TARGET);

    @Test
    void verifiedPortUsesTheOriginalCatalogMinimumAndCompleteBindingWithoutLegacyStdout() throws Exception {
        var port = new Port();
        var result = new DeploymentToolchainSetup(port, DeploymentToolchainCatalog.defaults()).prepare(List.of(REQUIREMENT), 0, 120);
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString()); assertEquals(1, port.binds);
        assertEquals("1.24.13", result.value().orElseThrow().selections().getFirst().version().text());
        port = new Port(); port.version = "1.24.1";
        assertEquals(TaskResult.FAILED, new DeploymentToolchainSetup(port, DeploymentToolchainCatalog.defaults()).prepare(List.of(REQUIREMENT), 0, 120).outcome());
        assertEquals(0, port.binds);
        port = new Port(); port.foreignBinding = true;
        assertEquals(TaskResult.UNKNOWN, new DeploymentToolchainSetup(port, DeploymentToolchainCatalog.defaults()).prepare(List.of(REQUIREMENT), 0, 120).outcome());
    }

    @Test
    void aSettledFailureUnknownOrCancellationCannotAdvanceToBind() throws Exception {
        for (var outcome : List.of(TaskResult.FAILED, TaskResult.UNKNOWN, TaskResult.CANCELLED)) {
            var port = new Port(); port.outcome = outcome;
            var result = new DeploymentToolchainSetup(port, DeploymentToolchainCatalog.defaults()).prepare(List.of(REQUIREMENT), 0, 120);
            assertEquals(outcome, result.outcome()); assertEquals(0, port.binds);
        }
    }

    private static final class Port implements DeploymentToolchainSetup.VerifiedCommands {
        TaskResult outcome = TaskResult.SUCCEEDED;
        String version = "1.24.13";
        boolean foreignBinding;
        int binds;
        public OperationResult<CommandResult> prepare(DeploymentToolchainType ecosystem, String branch, String exact, int seconds, String javaHome) {
            throw new AssertionError("Typed preparation cannot manufacture a legacy command result");
        }
        public OperationResult<CommandResult> bind(String encoded) { throw new AssertionError("Typed binding cannot manufacture stdout"); }
        public OperationResult<DeploymentToolchainSetup.VerifiedSelection> prepareVerified(DeploymentToolchainType ecosystem, String branch, String exact, int seconds, String javaHome) {
            assertEquals(DeploymentToolchainType.GO, ecosystem); assertEquals("1.24", branch);
            return new OperationResult<>(outcome, outcome == TaskResult.SUCCEEDED ? Optional.of(new DeploymentToolchainSetup.VerifiedSelection(
                ecosystem, version, "/usr/local/lib/windowstolinux/toolchains/versions/go-" + "b".repeat(64), DeploymentResolvedToolchains.OriginType.MANAGED,
                "https://go.dev/dl/original", "b".repeat(64))) : Optional.empty(), outcome == TaskResult.SUCCEEDED ? Optional.empty()
                : Optional.of(new Problem(outcome == TaskResult.CANCELLED ? ProblemCode.CANCELLED : outcome == TaskResult.FAILED ? ProblemCode.IO_FAILURE : ProblemCode.UNKNOWN,
                    "CONTROLLED_TYPED_" + outcome, "Controlled original typed outcome", Map.of(), Optional.empty())));
        }
        public OperationResult<String> bindVerified(String encoded) {
            binds++;
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(foreignBinding ? "f".repeat(64)
                : DeploymentAgentToolchainTracked.hash(Base64.getDecoder().decode(encoded))), Optional.empty());
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentToolchainInputEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ServerId SERVER = new ServerId("original-server");

    private static final String REVISION = "c".repeat(64);

    private static final String OPERATION = "a".repeat(32);

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "bind", "dependencies", "native", "resolve"})
    void everyProgramRequiresCurrentRendererFrameAndCompleteTargets(String phase) {
        var current = current(phase);
        var facts = DeploymentAgentToolchainInputEvidence.select(current).orElseThrow().facts();
        assertEquals(true, facts.get("inputMatchesRenderer"));
        assertEquals(true, facts.get("launcherAndArgumentsMatch"));
        assertEquals(true, facts.get("targetsMatchTypedArguments"));
        assertEquals(facts.get("stdinSha256"), facts.get("bundledSourceSha256"));
        assertNotEquals(facts.get("stdinSha256"), facts.get("exactActionSha256"));
        assertEquals(current.get("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8).length, facts.get("utf8Bytes"));
        assertFalse(facts.containsKey("approved"));
        assertFalse(facts.containsKey("installed"));
        assertFalse(facts.containsKey("safe"));
        assertEquals(phase.equals("native") ? "NATIVE_BUILD" : phase.equals("dependencies") ? "PYTHON"
            : phase.equals("bind") ? "EXACT_TOOLCHAIN_BINDING" : "RUST", facts.get("profile"));
    }

    @Test
    void currentBodyAndMeasuredHeaderCannotBorrowAProductName() {
        var changed = current("prepare");
        changed.put("completeExecutableInput", changed.path("completeExecutableInput").asText() + "\n#changed");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(changed));
        var header = current("prepare");
        header.put("exactAction", header.path("exactAction").asText().replace("stdinSha256=", "stdinSha256=0"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(header));
        var kind = current("prepare");
        kind.put("inputKind", "OPAQUE_BOUND_DATA");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(kind));
    }

    @Test
    void launchFlagsExtraCommandsAndPhaseReplacementAreRejected() {
        for (String script : List.of(DeploymentAgentToolchainPrograms.command(arguments("prepare")).replace(" -I -S ", " -I "),
            DeploymentAgentToolchainPrograms.command(arguments("prepare")) + "\n/bin/true",
            DeploymentAgentToolchainPrograms.command(arguments("bind")),
            DeploymentAgentToolchainPrograms.command(arguments("prepare")).replace("/usr/bin/python3", "/tmp/python3"))) {
            var current = current("prepare");
            rebind(current, script, Duration.ofSeconds(60));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(current));
        }
    }

    @Test
    void targetOmissionAdditionDuplicatesAndOperationReplacementAreRejected() {
        var omitted = current("prepare");
        omitted.withArray("resourceTargets").remove(0);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(omitted));
        var added = current("prepare");
        added.withArray("resourceTargets").add(JSON.valueToTree(new ActionTarget(SERVER.id(), "system-package", "gcc")));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(added));
        var duplicate = current("prepare");
        duplicate.withArray("resourceTargets").add(duplicate.withArray("resourceTargets").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(duplicate));
        var changedOperation = current("prepare");
        var args = new ArrayList<>(arguments("prepare"));
        args.set(6, "b".repeat(32));
        rebind(changedOperation, DeploymentAgentToolchainPrograms.command(args), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(changedOperation));
    }

    @Test
    void dependencyProfileCannotChooseArbitraryPackagesOrBorrowControllerTargets() {
        var arbitrary = current("dependencies");
        rebind(arbitrary, DeploymentAgentToolchainPrograms.command(List.of("RUST", OPERATION)), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(arbitrary));
        var scope = current("dependencies");
        scope.set("resourceTargets", JSON.valueToTree(DeploymentAgentToolchainCommands.controllerTargets(SERVER, OPERATION)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(scope));
        for (String phase : List.of("dependencies", "native")) {
            String profile = phase.equals("native") ? "NATIVE_BUILD" : "PYTHON";
            for (String missing : List.of("APT:configured-authenticated-sources", "DNF:configured-authenticated-sources", "DNF:centos:crb:transaction-only",
                "APT:authenticated-dependency-closure:" + profile, "DNF:authenticated-dependency-closure:" + profile)) {
                var incomplete = current(phase);
                var targets = incomplete.withArray("resourceTargets");
                for (int index = 0; index < targets.size(); index++)
                    if (targets.get(index).path("id").asText().equals(missing)) {
                        targets.remove(index);
                        break;
                    }
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(incomplete));
            }
            var extra = current(phase);
            extra.withArray("resourceTargets").add(JSON.valueToTree(new ActionTarget(SERVER.id(), "system-repository", "DNF:third-party")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(extra));
            var wrongProfile = current(phase);
            wrongProfile.withArray("resourceTargets").add(JSON.valueToTree(new ActionTarget(SERVER.id(), "system-package-transaction", "APT:authenticated-dependency-closure:RUBY")));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(wrongProfile));
        }
    }

    @Test
    void bindingMustRemainCanonicalAndBudgetMayClipTheSshDeadline() {
        var invalid = current("bind");
        rebind(invalid, DeploymentAgentToolchainPrograms.command(List.of("bind", "Jw==", "60", OPERATION)), Duration.ofSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(invalid));
        var clipped = current("prepare");
        rebind(clipped, DeploymentAgentToolchainPrograms.command(arguments("prepare")), Duration.ofSeconds(15));
        assertEquals("PT15S", DeploymentAgentToolchainInputEvidence.select(clipped).orElseThrow().facts().get("timeout"));
    }

    @Test
    void foreignOperationsCannotSupplyTheReservedBinding() {
        var current = current("prepare");
        current.put("operation", "deployment.model.command.v1");
        assertTrue(DeploymentAgentToolchainInputEvidence.select(current).isEmpty());
        current.putObject(DeploymentAgentToolchainInputEvidence.FIELD).put("inputMatchesRenderer", true);
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainInputEvidence.select(current));
    }

    private static ObjectNode current(String phase) {
        String actualPhase = phase.equals("native") ? "dependencies" : phase;
        String profile = phase.equals("native") ? "NATIVE_BUILD" : "PYTHON";
        boolean dependencies = actualPhase.equals("dependencies");
        var current = JSON.createObjectNode().put("operation", "deployment.agent.toolchain." + actualPhase + ".v1")
            .put("businessRevision", REVISION).put("inputKind", "EXECUTABLE_SCRIPT")
            .put("completeExecutableInput", dependencies ? DeploymentAgentToolchainPrograms.dependencies()
                : phase.equals("resolve") ? DeploymentAgentToolchainPrograms.resolver() : DeploymentAgentToolchainPrograms.controller());
        current.set("serverTargets", JSON.valueToTree(List.of(SERVER.id())));
        Set<ActionTarget> targets = dependencies ? DeploymentAgentToolchainCommands.dependencyTargets(SERVER, profile)
            : phase.equals("resolve") ? DeploymentAgentToolchainCommands.resolverTargets(SERVER, gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.RUST, "1.95", "1.95.0")
            : DeploymentAgentToolchainCommands.controllerTargets(SERVER, OPERATION);
        current.set("resourceTargets", JSON.valueToTree(targets));
        rebind(current, DeploymentAgentToolchainPrograms.command(arguments(phase)), Duration.ofSeconds(60));
        return current;
    }

    private static List<String> arguments(String phase) {
        return switch (phase) {
            case "prepare" -> List.of("prepare", "RUST", "1.95", "1.95.0", "60", "-", OPERATION,
                DeploymentAgentToolchainReleaseTest.encoded("RUST", "1.95", "1.95.0", OPERATION));
            case "resolve" -> List.of("resolve", "RUST", "1.95", "1.95.0", "60", OPERATION);
            case "bind" -> List.of("bind", DeploymentAgentToolchainCommandsTest.binding(), "60", OPERATION);
            case "dependencies" -> List.of("PYTHON", OPERATION);
            case "native" -> List.of("NATIVE_BUILD", OPERATION);
            default -> throw new IllegalArgumentException("Unknown test phase");
        };
    }

    private static void rebind(ObjectNode current, String script, Duration timeout) {
        try (var request = new CommandRequest(script, current.path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8), timeout, 65536)) {
            current.put("exactAction", RemoteActions.command(SERVER, REVISION, request).exactAction());
        }
    }
}

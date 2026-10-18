package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentPlatformProbeBindingRegressionTest {
    static Stream<String> variants() {
        return Stream.of("HOST", "DOCKER", "PODMAN");
    }

    @ParameterizedTest
    @MethodSource("variants")
    void bindsAllExactVariantsWithoutClaimingObservedProgramSafety(String variant) throws Exception {
        String script = variant.equals("HOST") ? DeploymentPlatformCapabilityProbe.render()
            : DeploymentPlatformCapabilityProbe.renderForContainer(ContainerEngineType.valueOf(variant));
        String operation = variant.equals("HOST") ? "deployment.candidate.host-probe.v1" : "deployment.candidate.container-probe.v1";
        var facts = facts(operation, script, new byte[0]);
        var binding = facts.path("platformProbe");
        assertEquals(variant, binding.path("variant").asText());
        assertEquals(script.getBytes(StandardCharsets.UTF_8).length, binding.path("scriptBytes").asInt());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8))), binding.path("scriptSha256").asText());
        assertEquals("EXACT_PRODUCT_REQUEST_SCRIPT_AND_EMPTY_STDIN", binding.path("comparison").asText());
        assertEquals(0, binding.path("stdinBytes").asInt());
        assertTrue(binding.path("meaning").asText().contains("not live program trust"));
        assertTrue(binding.path("meaning").asText().contains("container variants can initialize storage"));
        assertFalse(binding.has("safe"));
        assertTrue(facts.path("exactAction").asText().endsWith(script));
    }

    @Test
    void anyChangedEnvironmentGuardArgumentOrAppendedEffectLosesTheBinding() throws Exception {
        String original = DeploymentPlatformCapabilityProbe.render();
        for (String script : java.util.List.of(original.replace("env -i", "env"), original.replace("cd -- /", "cd -- /tmp"),
            original.replace("--no-plugins", ""), original.replace("-I -S -B", "-I"),
            original.replace("probe_file \"$executable\"", "true"), original + "printf changed\n")) {
            assertNotEquals(original, script);
            assertFalse(facts("deployment.candidate.host-probe.v1", script, new byte[0]).has("platformProbe"));
        }
    }

    @Test
    void nonemptyInputWrongVariantAndDifferentProgramsNeverGainProductBinding() throws Exception {
        String script = DeploymentPlatformCapabilityProbe.render();
        assertFalse(facts("deployment.candidate.host-probe.v1", script, new byte[]{1}).has("platformProbe"));
        assertFalse(facts("deployment.candidate.container-probe.v1", script, new byte[0]).has("platformProbe"));
        assertFalse(facts("deployment.candidate.host-probe.v1", "printf arbitrary\n", new byte[0]).has("platformProbe"));
    }

    private static com.fasterxml.jackson.databind.JsonNode facts(String operation, String script, byte[] input) throws Exception {
        var server = new ServerId("fixture-server");
        try (var request = new CommandRequest(script, input, Duration.ofMinutes(2), 65536)) {
            var intent = RemoteActions.command(server, "frozen", request).withSemantics(new ActionSemantics(operation,
                Set.of(new ActionTarget(server.id(), "deployment-host", server.id()))));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request));
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentWorkloadReviewEvidenceRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DeploymentCommand COMMAND = new DeploymentCommand("bin/tool", List.of("--title", "字段 \"值\""));

    private static final DeploymentHealthCheck CHECK = new DeploymentHealthCheck.Command(COMMAND, "OK", 10);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decodesEveryProducerFieldIncludingOptionalCommandsCompanionsAndWorkers(boolean workers) throws Exception {
        var decoded = JSON.valueToTree(DeploymentWorkloadReviewEvidence.decode(payload(workers), false));
        assertEquals(workers ? "application-v2" : "application-v1", decoded.path("format").asText());
        assertEquals("1", decoded.path("reviewedFlagLiteral").asText());
        assertEquals("bin/tool", decoded.at("/command/entrypoint").asText());
        assertEquals("字段 \"值\"", decoded.at("/command/arguments/1").asText());
        assertEquals("bin/tool", decoded.at("/verification/command/entrypoint").asText());
        assertEquals("bin/tool", decoded.at("/client/command/entrypoint").asText());
        assertEquals("native", decoded.at("/companions/0/sourcePath").asText());
        assertEquals("8080", decoded.at("/endpoints/0/hostPort").asText());
        assertEquals("/data/input", decoded.at("/inputs/0/accessPath").asText());
        assertEquals(workers ? 1 : 0, decoded.path("workers").size());
        assertEquals("COMMAND", decoded.at("/health/kind").asText());
        assertEquals("OK", decoded.at("/health/expectedOutput").asText());
        assertFalse(decoded.has("commandGrant"));
        assertFalse(decoded.has("liveOwnership"));
    }

    static Stream<DeploymentHealthCheck> healthChecks() {
        return Stream.of(new DeploymentHealthCheck.Http(java.net.URI.create("http://127.0.0.1:8080/health"), 200, 10),
            new DeploymentHealthCheck.Tcp(8080, 10, 2), new DeploymentHealthCheck.Process(10, 2), CHECK,
            new DeploymentHealthCheck.Udp(8080, "01", "02", Optional.empty(), 10),
            new DeploymentHealthCheck.Udp(8080, "", "", Optional.of(COMMAND), 10));
    }

    @ParameterizedTest
    @MethodSource("healthChecks")
    void everyHealthVariantRetainsAllCountedFields(DeploymentHealthCheck check) throws Exception {
        var decoded = JSON.valueToTree(DeploymentWorkloadReviewEvidence.decode(DeploymentWorkloadArguments.healthPayload(check), true));
        assertEquals("health-v1", decoded.path("format").asText());
        assertEquals(10, decoded.at("/health/timeoutSeconds").asInt());
        if (check instanceof DeploymentHealthCheck.Command)
            assertEquals("字段 \"值\"", decoded.at("/health/command/arguments/1").asText());
        if (check instanceof DeploymentHealthCheck.Udp udp && udp.probe().isPresent())
            assertEquals("bin/tool", decoded.at("/health/probe/command/entrypoint").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_NUL", "TRAILING", "TRUNCATED", "INVALID_UTF8", "BAD_COUNT", "OVERSIZED"})
    void malformedEncodedOrCountedPayloadsCannotBecomeReviewEvidence(String mutation) {
        byte[] bytes = Base64.getDecoder().decode(payload(false));
        String text = new String(bytes, StandardCharsets.UTF_8);
        bytes = switch (mutation) {
            case "MISSING_NUL" -> Arrays.copyOf(bytes, bytes.length - 1);
            case "TRAILING" -> (text + "extra\0").getBytes(StandardCharsets.UTF_8);
            case "TRUNCATED" -> "application-v1\0DAEMON\0".getBytes(StandardCharsets.UTF_8);
            case "INVALID_UTF8" -> new byte[]{(byte) 0xc3, 0};
            case "BAD_COUNT" ->
                text.replace("bin/tool\0" + "2\0", "bin/tool\0" + "65\0").getBytes(StandardCharsets.UTF_8);
            case "OVERSIZED" -> new byte[65537];
            default -> throw new AssertionError();
        };
        String encoded = Base64.getEncoder().encodeToString(bytes);
        if (mutation.equals("INVALID_UTF8"))
            assertThrows(java.nio.charset.CharacterCodingException.class, () -> DeploymentWorkloadReviewEvidence.decode(encoded, false));
        else
            assertThrows(IllegalArgumentException.class, () -> DeploymentWorkloadReviewEvidence.decode(encoded, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish-deployment", "publish-container", "application-health"})
    void originalLiteralFramesBindTheDecodedArgumentToDeclaredResources(String verb) throws Exception {
        var facts = frame(verb, "VALID");
        assertEquals("demo", facts.at("/workloadInput/application").asText());
        assertEquals("b".repeat(64), facts.at("/workloadInput/ownershipManifestSha256").asText());
        assertEquals("bin/tool", facts.at(verb.equals("application-health") ? "/workloadInput/health/command/entrypoint" : "/workloadInput/command/entrypoint").asText());
        if (!verb.equals("application-health")) {
            assertEquals(13, facts.at("/workloadInput/publicationFrame/literalArgumentCount").asInt());
            assertEquals(7, facts.at("/workloadInput/publicationFrame/workloadPayloadArgumentPosition").asInt());
            assertFalse(facts.at("/workloadInput/publicationFrame/runtimeToolchainBinding/present").asBoolean(true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TARGET", "CANDIDATE"})
    void mismatchedPublicationResourcesFailBeforeReview(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> frame("publish-deployment", mutation));
    }

    @Test
    void protectedOrUnexpectedStdinNeverEntersTheWorkloadDecoder() throws Exception {
        assertFalse(frame("publish-deployment", "STDIN").has("workloadInput"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completePublicationPositionsPreserveSecretsContentEscapesAndOptionalRuntimeTools(boolean tools) throws Exception {
        var words = new ArrayList<>(List.of("/helper", "publish-deployment", "demo", "demo-" + "a".repeat(16), "b".repeat(64), "c".repeat(64),
            "identity-v2", "SYSTEMD_STATIC", payload(false), "d".repeat(64), "1", "db-password", "2", "e".repeat(64),
            "demo", "demo", "1", "data", "/srv/data", "rw", "FILE", "MANAGED", "-", "-", "seed/file's.csv", "-", "-"));
        if (tools) words.addAll(List.of("tools-v1", "f".repeat(64)));
        words.add("process");
        String script = words.stream().map(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel::shellQuote)
            .collect(java.util.stream.Collectors.joining(" "));
        var node = JSON.valueToTree(DeploymentWorkloadReviewEvidence.publicationArguments(script).orElseThrow());
        assertEquals(words.size() - 2, node.path("literalArgumentCount").asInt());
        assertEquals(payload(false), node.at("/arguments/6/value").asText());
        assertEquals("secret[0].payloadSha256", node.at("/arguments/11/role").asText());
        assertEquals("e".repeat(64), node.at("/arguments/11/value").asText());
        assertEquals("seed/file's.csv", node.at("/arguments/22/value").asText());
        assertEquals(tools, node.at("/runtimeToolchainBinding/present").asBoolean());
        assertEquals("process", node.at("/runtimeDispatchArguments/0").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNQUOTED", "APPENDED", "UNCLOSED"})
    void noncanonicalShellBodiesNeverReceiveTheCompleteArgumentTable(String mutation) {
        String body = switch (mutation) {
            case "UNQUOTED" -> "helper publish-deployment demo";
            case "APPENDED" -> "'/helper' 'publish-deployment'; printf altered";
            default -> "'/helper' 'publish-deployment";
        };
        assertTrue(DeploymentWorkloadReviewEvidence.publicationArguments(body).isEmpty());
    }

    private JsonNode frame(String verb, String mutation) throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var server = new ServerId("fixture");
        boolean health = verb.equals("application-health");
        String candidate = "demo-" + "a".repeat(16);
        String script = "'" + layout.helperPath() + "' '" + verb + "' 'demo' "
            + (health ? "'" + "b".repeat(64) + "' '" + DeploymentWorkloadArguments.healthPayload(CHECK) + "'"
            : "'" + candidate + "' '" + "c".repeat(64) + "' '" + "b".repeat(64) + "' 'identity-v2' 'SYSTEMD_STATIC' '" + payload(false) + "' '" + "d".repeat(64) + "' '0' 'demo' 'demo' '0' 'process'");
        var targets = new HashSet<ActionTarget>();
        targets.add(new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo") + (mutation.equals("TARGET") ? ".other" : "")));
        if (!health)
            targets.add(new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate) + (mutation.equals("CANDIDATE") ? ".other" : "")));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script), mutation.equals("STDIN") ? new byte[]{7} : new byte[0], Duration.ofSeconds(90), 4096)) {
            var intent = RemoteActions.command(server, "d".repeat(64), request).withSemantics(new ActionSemantics(
                health ? "deployment.publication.health.v1" : "deployment.publication.publish.v1", targets));
            return JSON.valueToTree(DeploymentCommandEvidence.facts(intent, request));
        }
    }

    private static String payload(boolean workers) {
        var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true, COMMAND, "",
            List.of(new DeploymentEndpoint("http", DeploymentEndpoint.ProtocolType.HTTP, "127.0.0.1", 8080, 8080, DeploymentEndpoint.ExposureType.INTERNAL, "")),
            Optional.of(COMMAND), "OK", Optional.of(COMMAND),
            List.of(new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentInputField("input", "/srv/input", "/data/input")),
            List.of(new DeploymentCompanion("native", "native", DeploymentCompanion.BuildType.CMAKE_SERVICE, "native/build/tool", "NATIVE_TOOL")),
            "cli", workers ? List.of(new DeploymentWorker("worker", COMMAND)) : List.of());
        return DeploymentWorkloadArguments.payload(new DeploymentRuntimeDefinition.ManagedProcess(CHECK, DeploymentIdentityMode.SYSTEMD_STATIC, workload));
    }
}

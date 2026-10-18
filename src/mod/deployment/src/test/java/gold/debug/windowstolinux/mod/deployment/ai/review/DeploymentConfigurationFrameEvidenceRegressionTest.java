package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentConfigurationFrameEvidenceRegressionTest {
    private final DeploymentRemoteLayout layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));

    @ParameterizedTest
    @CsvSource({"systemd,false", "systemd,true", "container,false", "container,true"})
    void bindsActualEmptyOrOpaquePayloadToItsOwnDigestAndDestination(String format, boolean nonempty) throws Exception {
        byte[] data = nonempty ? "PRIVATE_VALUE=opaque-fixture\n".getBytes(StandardCharsets.UTF_8) : new byte[0];
        var facts = facts(format, data, "VALID");
        var frame = facts.path("stagedConfiguration");
        assertEquals("c".repeat(64), frame.path("configurationIdentity").asText());
        assertEquals(hash(data), frame.path("payloadSha256").asText());
        assertNotEquals(frame.path("configurationIdentity").asText(), frame.path("payloadSha256").asText());
        assertEquals(data.length, frame.path("payloadBytes").asInt());
        assertEquals(destination(format), frame.path("destination").asText());
        assertFalse(facts.toString().contains("opaque-fixture"));
        assertFalse(frame.has("commandGrant"));
        assertFalse(frame.has("verifiedOwnership"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIZE", "DIGEST", "TARGET"})
    void mismatchedFrameOrSemanticDestinationCannotBecomeReviewEvidence(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts("systemd", new byte[]{7}, mutation));
    }

    @Test
    void appendedArgumentsNeverReceiveTheExactFrameBinding() throws Exception {
        assertFalse(facts("systemd", new byte[0], "EXTRA").has("stagedConfiguration"));
    }

    private JsonNode facts(String format, byte[] data, String mutation) throws Exception {
        String digest = mutation.equals("DIGEST") ? "d".repeat(64) : hash(data);
        int bytes = data.length + (mutation.equals("SIZE") ? 1 : 0);
        String script = "'" + layout.helperPath() + "' 'stage-config' 'demo' '" + "c".repeat(64) + "' '" + format + "' '" + digest + "' '" + bytes + "'"
            + (mutation.equals("EXTRA") ? " 'extra'" : "");
        var server = new ServerId("fixture");
        var target = destination(format) + (mutation.equals("TARGET") ? ".other" : "");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), data, Duration.ofSeconds(90), 4096)) {
            var intent = RemoteActions.command(server, "b".repeat(64), request).withSemantics(new ActionSemantics(
                "deployment.publication.config.v1", Set.of(new ActionTarget(server.id(), "deployment-configuration", target))));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
        }
    }

    private String destination(String format) {
        return layout.configurationRoot("demo") + "/" + "c".repeat(64) + "/" + format + ".env";
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }
}

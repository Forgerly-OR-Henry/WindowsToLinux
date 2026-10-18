package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentConfigurationStagingEvidenceRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receiverSourceMatchesPinnedHelperForDefaultAndCapturedRoots(boolean custom) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data")) : DeploymentRemoteLayout.original();
        var facts = DeploymentManagedHelperBundle.configurationStagingImplementationFacts(layout);
        var json = new ObjectMapper().valueToTree(facts);
        assertEquals(DeploymentManagedHelperBundle.helperSha256(layout), json.path("helperSha256").asText());
        String helper = DeploymentManagedHelperBundle.renderScript(layout);
        for (var source : json.path("sources")) {
            String body = source.path("source").asText();
            assertTrue(helper.contains(body));
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))), source.path("sha256").asText());
        }
        String receiver = json.at("/sources/1/source").asText();
        assertTrue(receiver.contains("receive_immutable_payload()"));
        assertTrue(receiver.contains("stage_configuration()"));
        assertTrue(receiver.contains("stage_secret()"));
        assertTrue(receiver.contains("install -o root -g root -m 400"));
        assertFalse(json.has("payload"));
        assertFalse(json.has("commandGrant"));
        assertTrue(DeploymentAiJson.facts(facts, 1048576).length() < 1048576);
    }

    @ParameterizedTest
    @ValueSource(strings = {"config", "secret"})
    void dataAndSecretPayloadsRemainOpaqueAndAbsentFromSharedReviewJson(String kind) throws Exception {
        var layout = DeploymentRemoteLayout.original();
        String protectedText = "fixture-protected-value-NEVER-SEND-THIS-TO-THE-MODEL";
        byte[] payload = protectedText.getBytes(StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        String script = "'" + layout.helperPath() + "' 'stage-" + kind + "' 'demo' '"
            + (kind.equals("config") ? "b".repeat(64) + "' 'systemd" : "credential' '1") + "' '" + digest + "' '" + payload.length + "'";
        var server = new ServerId("fixture");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), payload, Duration.ofSeconds(90), 4096)) {
            String destination = kind.equals("config") ? layout.configurationRoot("demo") + "/" + "b".repeat(64) + "/systemd.env"
                : layout.configurationRoot("demo") + "/secrets/credential/1/value";
            var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(
                "deployment.publication." + kind + ".v1", Set.of(new ActionTarget(server.id(), kind.equals("config") ? "deployment-configuration" : "deployment-secret", destination))));
            var facts = DeploymentCommandEvidence.facts(intent, request, layout);
            assertEquals("OPAQUE_BOUND_DATA", facts.get("inputKind"));
            assertFalse(facts.containsKey("completeExecutableInput"));
            assertFalse(facts.containsKey("configurationStagingImplementation"), "Large source belongs only to ASSISTED scope");
            var all = new LinkedHashMap<String, Object>(facts);
            all.put("configurationStagingImplementation", DeploymentManagedHelperBundle.configurationStagingImplementationFacts(layout));
            assertFalse(new ObjectMapper().writeValueAsString(all).contains(protectedText));
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.ai.review;

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

import static org.junit.jupiter.api.Assertions.*;

class DeploymentToolchainImplementationEvidenceRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparationReceivesActualPinnedImplementationAndFitsTheExistingRequestBudget(boolean custom) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data")) : DeploymentRemoteLayout.original();
        String complete = DeploymentManagedHelperBundle.renderScript(layout);
        var server = new ServerId("fixture");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' prepare-toolchains 'prepare' 'JAVA' '21' '-' '120' '-'", layout), new byte[0], Duration.ofSeconds(135), 65536)) {
            var intent = RemoteActions.command(server, "b".repeat(64), request).withSemantics(new ActionSemantics(
                "deployment.toolchain.prepare.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
            var facts = new LinkedHashMap<String, Object>(DeploymentCommandEvidence.facts(intent, request, layout));
            assertFalse(facts.containsKey("toolchainImplementation"), "Shared Agent/STATIC evidence must not gain this large assisted-only payload");
            facts.put("toolchainImplementation", DeploymentManagedHelperBundle.toolchainImplementationFacts(layout));
            var json = new ObjectMapper().valueToTree(facts);
            var implementation = json.path("toolchainImplementation");
            assertEquals(DeploymentManagedHelperBundle.helperSha256(layout), implementation.path("helperSha256").asText());
            assertEquals(3, implementation.path("sources").size());
            String contract = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.text(layout);
            assertEquals(DeploymentManagedHelperBundle.buildEntrySha256(layout), implementation.path("buildEntrySha256").asText());
            assertTrue(contract.contains(implementation.path("helperSha256").asText()));
            assertTrue(contract.contains(implementation.path("buildEntrySha256").asText()));
            String selected = implementation.path("selectedDispatchSource").asText();
            assertTrue(complete.contains(selected));
            assertTrue(selected.contains("prepare_official_toolchains \"$@\""));
            assertFalse(selected.contains("build-entry"));
            assertFalse(selected.contains("run_restricted_build"));
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(selected.getBytes(StandardCharsets.UTF_8))), implementation.path("selectedDispatchSha256").asText());
            for (var source : implementation.path("sources")) {
                String text = source.path("source").asText();
                assertTrue(complete.contains(text));
                assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))), source.path("sha256").asText());
            }
            assertTrue(implementation.at("/sources/1/source").asText().contains("/usr/bin/python3 -I -"));
            assertTrue(implementation.at("/sources/2/source").asText().contains("prepare_official_toolchains \"$@\""));
            assertFalse(implementation.has("verifiedInstallation"));
            assertFalse(implementation.has("commandGrant"));
            assertTrue(DeploymentAiJson.facts(facts, 1048576).length() < 1048576);
            assertEquals("JAVA", json.at("/toolchainPreparation/ecosystem").asText());
        }
    }
}

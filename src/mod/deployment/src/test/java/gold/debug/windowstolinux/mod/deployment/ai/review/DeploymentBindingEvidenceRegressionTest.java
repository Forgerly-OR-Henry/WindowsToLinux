package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Encoding proof never implies installation or path ownership. / 编码证据不等同于安装或路径所有权。 */
class DeploymentBindingEvidenceRegressionTest {
    @ParameterizedTest
    @CsvSource({"/fixture/lib/toolchains/versions/java-a,DESCENDANT", "/fixture/lib/toolchains,SAME",
        "/fixture/lib/toolchains-evil/java-a,OUTSIDE", "/usr/lib/jvm/java-21,OUTSIDE"})
    void comparesWholeComponentsAgainstCapturedRootWithoutClaimingLiveTrust(String directory, String relation) throws Exception {
        var layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/fixture/lib", "/fixture/apps",
            "/fixture/state", "/fixture/config", "/fixture/data"));
        String payload = "WTL-TOOLS-1\tcurrent\nJAVA\tLANGUAGE_TARGET\tMjE=\tcG9tLnhtbA==\tSERIES\t21\t21.0.12.1+1\t"
            + directory + "\tSYSTEM\tc3lzdGVtOi91c3IvYmluL2phdmE=\t" + "b".repeat(64) + "\n";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        var server = new ServerId("test-server");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' prepare-toolchains 'bind' '" + encoded + "'", layout), new byte[0], Duration.ofSeconds(30), 8192)) {
            var intent = RemoteActions.command(server, "frozen", request).withSemantics(new ActionSemantics(
                "deployment.toolchain.bind.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
            var binding = new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout))
                .path("decodedToolchainBinding");
            assertEquals(layout.toolchains(), binding.path("managedToolchainsRoot").asText());
            assertEquals(relation, binding.at("/toolchains/0/lexicalRelationToManagedRoot").asText());
            for (String field : Set.of("canonicalBase64", "strictUtf8", "canonicalTypedBinding"))
                assertTrue(binding.path("literalValidation").path(field).asBoolean());
            assertEquals("LITERAL_ENCODING_ONLY_NOT_LIVE_INSTALLATION_OR_AUTHORITY",
                binding.at("/literalValidation/scope").asText());
            assertFalse(binding.has("verified"));
            assertFalse(binding.has("installed"));
        }
    }
}

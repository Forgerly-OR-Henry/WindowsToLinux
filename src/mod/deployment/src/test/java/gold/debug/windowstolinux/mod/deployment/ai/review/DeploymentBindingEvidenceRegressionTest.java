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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Encoding proof never implies installation or path ownership. / 编码证据不等同于安装或路径所有权。 */
class DeploymentBindingEvidenceRegressionTest {
    @Test
    void managedArchiveDigestAndInstallationCacheIdentityDescribeDistinctObjects() throws Exception {
        var layout = DeploymentRemoteLayout.original();
        String artifact = "a47ac940abd12399d59ad15c877e7113fa35f2b9ec7e6a8a045d4fd8b9741dea";
        String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(("RUST\n1.95.0\n" + artifact).getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(artifact, key);
        String directory = layout.toolchains() + "/versions/rust-" + key;
        String source = Base64.getEncoder().encodeToString("https://static.rust-lang.org/dist/rust-1.95.0-x86_64-unknown-linux-gnu.tar.gz".getBytes(StandardCharsets.UTF_8));
        String payload = "WTL-TOOLS-1\tcurrent\nRUST\tLANGUAGE_TARGET\tMS45NQ==\tQ2FyZ28udG9tbA==\tSERIES\t1.95\t1.95.0\t"
            + directory + "\tMANAGED\t" + source + "\t" + artifact + "\n";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        var server = new ServerId("fixture");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' prepare-toolchains 'bind' '" + encoded + "'", layout), new byte[0], Duration.ofSeconds(30), 8192)) {
            var intent = RemoteActions.command(server, "business-plan", request).withSemantics(new ActionSemantics(
                "deployment.toolchain.bind.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
            var facts = new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
            var binding = facts.path("decodedToolchainBinding");
            assertEquals(artifact, binding.at("/toolchains/0/sha256").asText());
            assertEquals(directory, binding.at("/toolchains/0/directory").asText());
            assertEquals("DESCENDANT", binding.at("/toolchains/0/lexicalRelationToManagedRoot").asText());
            assertEquals("1.95", binding.at("/toolchains/0/requirement/declaration").asText());
            assertEquals("Cargo.toml", binding.at("/toolchains/0/requirement/source").asText());
            assertEquals("LANGUAGE_TARGET", binding.at("/toolchains/0/requirement/purpose").asText());
            assertEquals("SERIES", binding.at("/toolchains/0/requirement/constraint").asText());
            assertEquals("1.95", binding.at("/toolchains/0/requirement/version").asText());
            assertTrue(binding.path("bindingDigestMeaning").isTextual());
            assertFalse(binding.has("verifiedInstallation"));
        }
    }

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
            var evidence = new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
            assertEquals("bind", evidence.path("toolchainSubcommand").asText());
            var binding = evidence.path("decodedToolchainBinding");
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

    @ParameterizedTest
    @CsvSource({"RUST,1.95,-,1777,-", "JAVA,21,21.0.12.1+1,30,/fixture/java", "NODE,22,-,7200,-"})
    void decodesTheActualPreparationArgumentPositions(String ecosystem, String branch, String exact, int seconds, String javaHome) throws Exception {
        var evidence = preparation("'prepare' '" + ecosystem + "' '" + branch + "' '" + exact + "' '" + seconds + "' '" + javaHome + "'");
        assertEquals(ecosystem, evidence.path("ecosystem").asText());
        assertEquals(branch, evidence.path("catalogBranch").asText());
        assertEquals(exact, evidence.path("requestedExact").asText());
        assertEquals(seconds, evidence.path("preparationTimeoutSeconds").asInt());
        assertEquals(javaHome, evidence.path("javaHome").asText());
        assertFalse(evidence.has("installed"));
        assertFalse(evidence.has("commandGrant"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "'prepare' 'RUST' '1.95' '-' '29' '-'", "'prepare' 'RUST' '1.95' '-' '7201' '-'",
        "'prepare' 'RUST' '1.95' '-' '1777' '-' 'extra'", "'prepare' 'RUST' 'RUST' '1.95' '1777' '-'",
        "'prepare' 'JAVA' '21' '17.0.1' '1777' '-'", "'prepare' 'RUST' '1.95' '-' '1777' '-'; true"
    })
    void doesNotInventParsedEvidenceForMalformedOrAppendedPreparation(String arguments) throws Exception {
        assertTrue(preparation(arguments).isMissingNode());
    }

    private static com.fasterxml.jackson.databind.JsonNode preparation(String arguments) throws Exception {
        var layout = DeploymentRemoteLayout.original();
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' prepare-toolchains " + arguments, layout), new byte[0], Duration.ofSeconds(1800), 65536)) {
            var server = new ServerId("fixture");
            var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(
                "deployment.toolchain.prepare.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout)).path("toolchainPreparation");
        }
    }
}

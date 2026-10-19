package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java.MavenBuildWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentBuildCollectorEvidenceRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualCollectorRoundTripsIncludingUnicodeCompatibilityAndBindsCurrentCandidate(boolean custom) throws Exception {
        var facts = facts("VALID", custom);
        assertEquals("backend", facts.at("/buildCollector/application").asText());
        assertEquals(256, facts.at("/buildCollector/memoryMiB").asInt());
        assertEquals("ordinary", facts.at("/buildCollector/engine").asText());
        assertFalse(facts.path("buildCollector").has("verifiedIsolation"));
        assertFalse(facts.path("buildCollector").has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STDIN", "PYTHON", "REPORT", "DIGEST", "APPLICATION", "TARGET", "UNGUARDED"})
    void modifiedCollectorInputOrTargetNeverGetsProducerEvidence(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts(mutation, true));
    }

    @Test
    void exactMavenFragmentDescribesPinnedCandidateLocalBootstrapWithoutTrustingOtherCommands() throws Exception {
        String fragment = maven();
        var facts = new ObjectMapper().valueToTree(MavenBuildWriter.bootstrapEvidence("# 中文前缀\n" + fragment + "\nprintf extra\n").orElseThrow());
        assertEquals("SHA512", facts.path("algorithm").asText());
        int offset = "# 中文前缀\n".getBytes(StandardCharsets.UTF_8).length;
        assertEquals(offset, facts.path("utf8ByteOffset").asInt());
        byte[] bytes = fragment.getBytes(StandardCharsets.UTF_8);
        assertEquals(bytes.length, facts.path("utf8ByteLength").asInt());
        assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), facts.path("fragmentSha256").asText());
        assertTrue(MavenBuildWriter.bootstrapEvidence(fragment + "\n" + fragment).isEmpty());
        assertTrue(MavenBuildWriter.bootstrapReviewInstructions(fragment).orElseThrow().contains("independently pinned"));
        assertTrue(MavenBuildWriter.bootstrapReviewInstructions(fragment + "\n" + fragment).isEmpty());
        assertEquals(128, facts.path("expectedArchiveDigest").asText().length());
        assertTrue(facts.path("url").asText().startsWith("https://repo.maven.apache.org/"));
        assertTrue(facts.path("candidateLocalHome").asText().startsWith("$mutable/home/"));
        assertTrue(facts.path("meaning").asText().contains("full body's"));
        assertFalse(facts.has("verifiedDownload"));
        assertFalse(facts.has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"URL", "DIGEST", "EXTRACTION"})
    void changedMavenBootstrapCannotReceiveThePinnedFragmentDescription(String mutation) throws Exception {
        String fragment = maven();
        String changed = switch (mutation) {
            case "URL" -> fragment.replace("repo.maven.apache.org", "foreign.invalid");
            case "DIGEST" -> fragment.replace("831a8591", "831a8592");
            case "EXTRACTION" -> fragment.replace("--strip-components=1", "--strip-components=0");
            default -> throw new AssertionError();
        };
        assertNotEquals(fragment, changed);
        assertTrue(MavenBuildWriter.bootstrapEvidence(changed).isEmpty());
        assertTrue(MavenBuildWriter.bootstrapReviewInstructions(changed).isEmpty());
    }

    private static String maven() throws Exception {
        var method = MavenBuildWriter.class.getDeclaredMethod("prepareMaven");
        method.setAccessible(true);
        return (String) method.invoke(null);
    }

    private JsonNode facts(String mutation, boolean custom) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data")) : DeploymentRemoteLayout.original();
        var workspace = new DeploymentRemoteWorkspace("backend", "a".repeat(64), layout);
        byte[] input = "printf 'fixed build\\n'\n".getBytes(StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        String script = DeploymentBuildOutputProtocol.script(workspace, 60, 16, 256, 8192, "ordinary", digest, "-",
            List.of(List.of("BUILD_OK", "兼容 'phrase'")));
        script = switch (mutation) {
            case "PYTHON" -> script.replace("overlap = max", "overlap = min");
            case "REPORT" -> script.replace("WTL_BUILD_REPORT=1", "WTL_BUILD_REPORT=2");
            case "DIGEST" -> script.replace(digest, "b".repeat(64));
            case "APPLICATION" -> script.replace("build-run 'backend'", "build-run 'foreign'");
            default -> script;
        };
        if (mutation.equals("STDIN")) input = "printf changed\n".getBytes(StandardCharsets.UTF_8);
        var server = new ServerId("fixture");
        try (var request = new CommandRequest(mutation.equals("UNGUARDED") ? script : DeploymentManagedHelperBundle.verifiedCommand(script, layout),
            input, Duration.ofSeconds(75), 65536)) {
            var intent = RemoteActions.command(server, "b".repeat(64), request).withSemantics(new ActionSemantics(
                "deployment.build.run.v1", Set.of(new ActionTarget(server.id(), "deployment-build", workspace.candidateRoot() + (mutation.equals("TARGET") ? ".other" : "")))));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
        }
    }
}

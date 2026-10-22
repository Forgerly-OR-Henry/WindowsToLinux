package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAssistedBuildArchiveRegressionTest {
    @Test
    void comparesAll64CharactersToExactFrontendArchiveWithoutTrustingExecutableBody() throws Exception {
        var facts = facts("VALID");
        assertEquals("a".repeat(64), facts.get("expectedArchiveSha256"));
        assertEquals(64, facts.get("sha256Characters"));
        assertEquals("frontend", ((Map<?, ?>) facts.get("archive")).get("relativeRoot"));
        assertFalse(facts.containsKey("verifiedSource"));
        assertFalse(facts.containsKey("commandGrant"));
    }

    @Test
    void equalContentInDifferentComponentRootsKeepsEachReviewedApplicationBinding() throws Exception {
        for (String component : List.of("frontend", "backend")) {
            var facts = facts("SAME_CONTENT", "deployed-" + component, component);
            assertEquals(component, ((Map<?, ?>) facts.get("archive")).get("relativeRoot"));
            assertEquals("a".repeat(64), facts.get("expectedArchiveSha256"));
            assertTrue(facts.get("candidate").toString().startsWith("deployed-" + component + "-"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HASH", "SHORT", "DUPLICATE_GUARD", "DUPLICATE_ARCHIVE", "TARGET", "COLLECTOR_INPUT", "OPERATION",
        "NO_BINDING", "WRONG_APPLICATION", "WRONG_ROOT", "WRONG_SERVER", "WRONG_LAYOUT", "ARCHIVE_SNAPSHOT", "ARCHIVE_PATH", "ARCHIVE_BYTES", "SOURCE_REVISION"})
    void alteredLiteralHashInputCandidateOrAmbiguousSourceCannotAcquireComparison(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts(mutation));
    }

    private Map<String, Object> facts(String mutation) throws Exception {
        return facts(mutation, "frontend", "frontend");
    }

    private Map<String, Object> facts(String mutation, String application, String component) throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var workspace = new DeploymentRemoteWorkspace(application, "a".repeat(64), layout);
        String guard = "test \"$(sha256sum \"$archive\" | awk '{print $1}')\" = '" + (mutation.equals("HASH") ? "c".repeat(64) : mutation.equals("SHORT") ? "a".repeat(63) : "a".repeat(64)) + "'\n";
        byte[] input = (guard + (mutation.equals("DUPLICATE_GUARD") ? guard : "") + "printf 'untrusted later body still needs independent review\\n'\n").getBytes(StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        String script = DeploymentBuildOutputProtocol.script(workspace, 60, 16, 256, 8192, "ordinary", digest, "-", List.of());
        if (mutation.equals("COLLECTOR_INPUT")) input = guard.getBytes(StandardCharsets.UTF_8);
        var server = new ServerId("fixture");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), input, Duration.ofSeconds(75), 65536)) {
            var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(
                mutation.equals("OPERATION") ? "deployment.publication.observe.v1" : "deployment.build.run.v1", Set.of(new ActionTarget(server.id(), "deployment-build", workspace.candidateRoot() + (mutation.equals("TARGET") ? ".other" : "")))));
            var selected = archive(component, "a".repeat(64));
            var sibling = archive(component.equals("frontend") ? "backend" : "frontend",
                mutation.equals("SAME_CONTENT") ? "a".repeat(64) : "b".repeat(64));
            var archives = mutation.equals("DUPLICATE_ARCHIVE") ? List.of(selected, selected) : List.of(sibling, selected);
            var association = new LinkedHashMap<>(selected);
            association.put("application", mutation.equals("WRONG_APPLICATION") ? "other" : application);
            association.put("server", mutation.equals("WRONG_SERVER") ? "other" : server.id());
            association.put("remoteLayoutSha256", mutation.equals("WRONG_LAYOUT") ? "c".repeat(64) : layout.digest());
            switch (mutation) {
                case "WRONG_ROOT" -> association.put("relativeRoot", "other-root");
                case "ARCHIVE_SNAPSHOT" -> association.put("archiveSnapshotId", UUID.randomUUID().toString());
                case "ARCHIVE_PATH" -> association.put("localArchivePath", "other.tar.gz");
                case "ARCHIVE_BYTES" -> association.put("archiveBytes", 101L);
                case "SOURCE_REVISION" -> association.put("sourceManifestSha256", "f".repeat(64));
            }
            return DeploymentAssistedScope.buildArchiveFacts(archives,
                mutation.equals("NO_BINDING") ? Map.of() : Map.of(application, Map.copyOf(association)), intent, request, layout);
        }
    }

    private static Map<String, Object> archive(String root, String hash) {
        return Map.of("archiveSha256", hash, "relativeRoot", root, "originSnapshotId", "original-source",
            "sourceManifestSha256", "d".repeat(64), "archiveSnapshotId", "archive-" + root,
            "localArchivePath", root + ".tar.gz", "archiveBytes", 100L);
    }
}

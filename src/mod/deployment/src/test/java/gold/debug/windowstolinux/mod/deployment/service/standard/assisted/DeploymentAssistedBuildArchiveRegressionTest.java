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

    @ParameterizedTest
    @ValueSource(strings = {"HASH", "SHORT", "DUPLICATE_GUARD", "DUPLICATE_ARCHIVE", "TARGET", "COLLECTOR_INPUT", "OPERATION"})
    void alteredLiteralHashInputCandidateOrAmbiguousSourceCannotAcquireComparison(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts(mutation));
    }

    private Map<String, Object> facts(String mutation) throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var workspace = new DeploymentRemoteWorkspace("frontend", "a".repeat(64), layout);
        String guard = "test \"$(sha256sum \"$archive\" | awk '{print $1}')\" = '" + (mutation.equals("HASH") ? "c".repeat(64) : mutation.equals("SHORT") ? "a".repeat(63) : "a".repeat(64)) + "'\n";
        byte[] input = (guard + (mutation.equals("DUPLICATE_GUARD") ? guard : "") + "printf 'untrusted later body still needs independent review\\n'\n").getBytes(StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        String script = DeploymentBuildOutputProtocol.script(workspace, 60, 16, 256, 8192, "ordinary", digest, "-", List.of());
        if (mutation.equals("COLLECTOR_INPUT")) input = guard.getBytes(StandardCharsets.UTF_8);
        var server = new ServerId("fixture");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), input, Duration.ofSeconds(75), 65536)) {
            var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(
                mutation.equals("OPERATION") ? "deployment.publication.observe.v1" : "deployment.build.run.v1", Set.of(new ActionTarget(server.id(), "deployment-build", workspace.candidateRoot() + (mutation.equals("TARGET") ? ".other" : "")))));
            Map<String, Object> frontend = Map.of("archiveSha256", "a".repeat(64), "relativeRoot", "frontend");
            var archives = mutation.equals("DUPLICATE_ARCHIVE") ? List.of(frontend, frontend)
                : List.of(Map.<String, Object>of("archiveSha256", "b".repeat(64), "relativeRoot", "backend"), frontend);
            return DeploymentAssistedScope.buildArchiveFacts(archives, intent, request, layout);
        }
    }
}

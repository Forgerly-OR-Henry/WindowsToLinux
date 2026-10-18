package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentOriginalStatusFrameRegressionTest {
    private final UUID task = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private final UUID operation = UUID.fromString("87654321-4321-4321-4321-cba987654321");

    @ParameterizedTest
    @ValueSource(strings = {"ROOT", "ATTEMPT", "CONTAINER", "ATTEMPT_CONTAINER", "RELEASE", "DEFAULT"})
    void bindsLiteralIdentityAndEveryDerivedObservationPath(String variant) throws Exception {
        var facts = facts(variant).path("originalPublicationStatus");
        assertEquals("demo", facts.path("application").asText());
        assertEquals(task.toString(), facts.path("task").asText());
        assertEquals(variant.startsWith("ATTEMPT") ? operation.toString() : task.toString(), facts.path("operation").asText());
        assertEquals(variant.equals("RELEASE") ? "d".repeat(64) : "none", facts.path("expectedRelease").asText());
        assertTrue(facts.path("derivedReleaseRoot").asText().endsWith("/demo"));
        assertTrue(facts.path("derivedCandidateRoot").asText().endsWith("/demo-" + "a".repeat(16)));
        assertTrue(facts.path("derivedSnapshotRoot").asText().contains(facts.path("operation").asText()));
        assertFalse(facts.has("verifiedOwnership"));
        assertFalse(facts.has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASE_ROOT", "CANDIDATE_ROOT", "SNAPSHOT_ROOT", "LOCK_ROOT", "SERVER", "CANDIDATE_APP", "CANDIDATE_PREFIX", "BACKEND", "ATTEMPT_ARITY"})
    void mismatchedTargetsAndArgumentRolesCannotBecomeStatusEvidence(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts(mutation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXTRA", "STDIN", "OTHER_OPERATION"})
    void alteredEnvelopeReceivesNoExactStatusEvidence(String mutation) throws Exception {
        assertFalse(facts(mutation).has("originalPublicationStatus"));
    }

    private JsonNode facts(String variant) throws Exception {
        var layout = variant.equals("DEFAULT") ? DeploymentRemoteLayout.original() : new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));
        var server = new ServerId("fixture");
        boolean attempt = variant.startsWith("ATTEMPT");
        String candidate = (variant.equals("CANDIDATE_APP") ? "other" : variant.equals("CANDIDATE_PREFIX") ? "demo-other" : "demo") + "-" + "a".repeat(16);
        String script = "'" + layout.helperPath() + "' 'publication-original-status" + (attempt ? "-attempt" : "")
            + "' 'demo' '" + "c".repeat(64) + "' '" + task + "'"
            + (attempt && !variant.equals("ATTEMPT_ARITY") ? " '" + operation + "'" : "")
            + " '" + candidate + "' '" + (variant.equals("RELEASE") ? "d".repeat(64) : "none")
            + "' '" + (variant.contains("CONTAINER") || variant.equals("BACKEND") ? "container" : "native")
            + "' '" + (variant.contains("CONTAINER") ? "podman" : "none") + "'" + (variant.equals("EXTRA") ? " 'extra'" : "");
        var targets = Set.of(new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo") + (variant.equals("RELEASE_ROOT") ? ".other" : "")),
            new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot("demo-" + "a".repeat(16)) + (variant.equals("CANDIDATE_ROOT") ? ".other" : "")),
            new ActionTarget(server.id(), "deployment-snapshot", layout.snapshotRoot("demo", attempt ? operation : task) + (variant.equals("SNAPSHOT_ROOT") ? ".other" : "")),
            new ActionTarget(server.id(), "deployment-application-locks", layout.applicationLockRoot("demo") + (variant.equals("LOCK_ROOT") ? ".other" : "")));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout),
            variant.equals("STDIN") ? new byte[]{1} : new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = RemoteActions.command(variant.equals("SERVER") ? new ServerId("foreign") : server, "b".repeat(64), request)
                .withSemantics(new ActionSemantics(variant.equals("OTHER_OPERATION") ? "deployment.publication.observe.v1" : "deployment.publication.original-status.v1", targets));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
        }
    }
}

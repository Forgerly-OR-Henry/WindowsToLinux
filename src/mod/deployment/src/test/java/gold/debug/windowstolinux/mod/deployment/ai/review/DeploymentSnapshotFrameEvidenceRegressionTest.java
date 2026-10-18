package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentSnapshotFrameEvidenceRegressionTest {
    private final DeploymentRemoteLayout layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));

    private final UUID taskId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @ParameterizedTest
    @ValueSource(strings = {"snapshot-deployment", "snapshot-container"})
    void bindsTheRealPublicArityOwnershipAndDerivedDestinations(String verb) throws Exception {
        var facts = facts(verb, "VALID");
        var frame = facts.path("publicationSnapshot");
        assertEquals("c".repeat(64), frame.path("ownershipManifestSha256").asText());
        assertEquals(taskId.toString(), frame.path("task").asText());
        assertEquals(layout.applicationRoot("demo"), frame.path("derivedReleaseRoot").asText());
        assertEquals(layout.snapshotRoot("demo", taskId), frame.path("possibleSnapshotRoot").asText());
        assertTrue(facts.path("snapshotTaskMatchesOriginalContext").asBoolean());
        assertFalse(frame.has("previousPresent"));
        assertFalse(frame.has("verifiedOwnership"));
        assertFalse(frame.has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASE", "SNAPSHOT", "TASK"})
    void mismatchedResourceOrOriginalTaskCannotBecomeBoundEvidence(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts("snapshot-deployment", mutation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXTRA", "STDIN"})
    void changedFramesNeverReceiveTheExactSnapshotBinding(String mutation) throws Exception {
        assertFalse(facts("snapshot-deployment", mutation).has("publicationSnapshot"));
    }

    private JsonNode facts(String verb, String mutation) throws Exception {
        var server = new ServerId("fixture");
        var task = (TaskContext) java.lang.reflect.Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("id")) return mutation.equals("TASK") ? UUID.randomUUID() : taskId;
                throw new AssertionError(method.getName());
            });
        String script = "'" + layout.helperPath() + "' '" + verb + "' 'demo' '" + "c".repeat(64) + "' '" + taskId + "'"
            + (mutation.equals("EXTRA") ? " 'extra'" : "");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout),
            mutation.equals("STDIN") ? new byte[]{1} : new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = RemoteActions.command(server, "b".repeat(64), request).withSemantics(new ActionSemantics(
                "deployment.publication.snapshot.v1", Set.of(
                new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo") + (mutation.equals("RELEASE") ? ".other" : "")),
                new ActionTarget(server.id(), "deployment-snapshot", layout.snapshotRoot("demo", taskId) + (mutation.equals("SNAPSHOT") ? ".other" : "")))));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout, null, task));
        }
    }
}

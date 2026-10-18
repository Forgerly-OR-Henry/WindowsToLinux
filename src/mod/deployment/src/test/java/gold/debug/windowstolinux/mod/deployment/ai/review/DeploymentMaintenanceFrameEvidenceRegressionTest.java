package gold.debug.windowstolinux.mod.deployment.ai.review;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentMaintenanceFrameEvidenceRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsDistinctDigestRolesAndActualLockEffects(boolean custom) throws Exception {
        var frame = facts("VALID", custom).path("publicationMaintenance");
        assertEquals("c".repeat(64), frame.path("ownershipManifestSha256").asText());
        assertEquals("d".repeat(64), frame.path("releaseSha256").asText());
        assertEquals("deployment-" + "d".repeat(64), frame.path("maintenanceToken").asText());
        assertEquals(layout(custom).applicationLockRoot("demo") + "/maintenance", frame.path("maintenanceMarker").asText());
        assertFalse(frame.has("verifiedOwnership"));
        assertFalse(frame.has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASE_TARGET", "LOCK_TARGET", "TOKEN", "APPLICATION"})
    void rejectsUnboundResourcesOrDifferentToken(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts(mutation, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXTRA", "STDIN", "BEGIN"})
    void changedCommandsNeverReceiveMaintenanceEndEvidence(String mutation) throws Exception {
        assertFalse(facts(mutation, true).has("publicationMaintenance"));
    }

    private static DeploymentRemoteLayout layout(boolean custom) {
        return custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data")) : DeploymentRemoteLayout.original();
    }

    private JsonNode facts(String mutation, boolean custom) throws Exception {
        var layout = layout(custom);
        var server = new ServerId("fixture");
        String script = "'" + layout.helperPath() + "' 'application-maintenance' '" + (mutation.equals("BEGIN") ? "begin" : "end")
            + "' '" + (mutation.equals("APPLICATION") ? "other" : "demo") + "' '" + "c".repeat(64) + "' 'deployment-"
            + (mutation.equals("TOKEN") ? "e" : "d").repeat(64) + "' '" + "d".repeat(64) + "'" + (mutation.equals("EXTRA") ? " 'extra'" : "");
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout),
            mutation.equals("STDIN") ? new byte[]{1} : new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = RemoteActions.command(server, "b".repeat(64), request).withSemantics(new ActionSemantics(
                "deployment.publication.maintenance-end.v1", Set.of(
                new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo") + (mutation.equals("RELEASE_TARGET") ? ".other" : "")),
                new ActionTarget(server.id(), "deployment-application-locks", layout.applicationLockRoot("demo") + (mutation.equals("LOCK_TARGET") ? ".other" : "")))));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
        }
    }
}

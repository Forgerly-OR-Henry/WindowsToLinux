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

class DeploymentManagedApplicationFrameRegressionTest {
    private final DeploymentRemoteLayout layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));

    @ParameterizedTest
    @ValueSource(strings = {"retain", "inspect-runtime", "observe-deployment"})
    void bindsCommonApplicationOwnershipArgumentsAndDerivedPaths(String verb) throws Exception {
        var frame = facts(verb, "VALID").path("managedApplication");
        assertEquals("demo", frame.path("application").asText());
        assertEquals("c".repeat(64), frame.path("ownershipManifestSha256").asText());
        assertEquals(layout.applicationRoot("demo"), frame.path("derivedReleaseRoot").asText());
        assertEquals(verb.equals("retain"), frame.has("containerCredentialRoot"));
        if (verb.equals("retain"))
            assertEquals(layout.configurationRoot("demo") + "/.container-credentials", frame.path("containerCredentialRoot").asText());
        assertFalse(frame.has("verifiedOwnership"));
        assertFalse(frame.has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASE", "CREDENTIALS", "APPLICATION"})
    void rejectsUnboundRetentionEffects(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts("retain", mutation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXTRA", "STDIN", "ACTION"})
    void alteredCommandsNeverReceiveExactFrameEvidence(String mutation) throws Exception {
        assertFalse(facts("retain", mutation).has("managedApplication"));
    }

    private JsonNode facts(String verb, String mutation) throws Exception {
        var server = new ServerId("fixture");
        String script = "'" + layout.helperPath() + "' '" + (mutation.equals("ACTION") ? "candidate-cleanup" : verb)
            + "' '" + (mutation.equals("APPLICATION") ? "other" : "demo") + "' '" + "c".repeat(64) + "'" + (mutation.equals("EXTRA") ? " 'extra'" : "");
        var targets = new HashSet<ActionTarget>();
        targets.add(new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo") + (mutation.equals("RELEASE") ? ".other" : "")));
        if (verb.equals("retain"))
            targets.add(new ActionTarget(server.id(), "deployment-secret", layout.configurationRoot("demo") + "/.container-credentials" + (mutation.equals("CREDENTIALS") ? ".other" : "")));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout),
            mutation.equals("STDIN") ? new byte[]{1} : new byte[0], Duration.ofSeconds(60), 4096)) {
            String operation = verb.equals("retain") ? "retain" : verb.equals("inspect-runtime") ? "inspect" : "observe";
            var intent = RemoteActions.command(server, "b".repeat(64), request).withSemantics(new ActionSemantics(
                "deployment.publication." + operation + ".v1", targets));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
        }
    }
}

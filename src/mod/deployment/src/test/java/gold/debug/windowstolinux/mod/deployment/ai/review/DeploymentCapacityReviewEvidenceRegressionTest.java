package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentCapacityReviewEvidenceRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capacityIsBoundToItsOwnExactInterfaceAndTargetWithoutHostProbeTrust(boolean custom) throws Exception {
        var facts = facts(custom, "VALID");
        assertTrue(facts.has("capacityProbe"));
        assertFalse(facts.has("platformProbe"));
        assertEquals(custom ? "/fixture/state" : "/var/lib", facts.at("/capacityProbe/target").asText());
        assertFalse(facts.path("capacityProbe").has("availableBytes"));
        assertFalse(facts.path("capacityProbe").has("commandGrant"));
        if (!custom) {
            String body = "set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\n"
                + "/usr/bin/stat -f -c '%a %S' -- '/var/lib'\n";
            assertEquals("exec /usr/bin/env -i PATH=/usr/bin:/bin LC_ALL=C HOME=/dev/null "
                + "/bin/bash --noprofile --norc -p -c '" + body.replace("'", "'\"'\"'") + "'\n",
                DeploymentCandidateChannel.capacityScript(DeploymentRemoteLayout.original()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCRIPT", "STDIN", "ENVIRONMENT", "STAT_PATH", "SHELL_PATH"})
    void changedRequestsReceiveNoExactCapacityBinding(String mutation) throws Exception {
        assertFalse(facts(false, mutation).has("capacityProbe"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TARGET", "SERVER", "EXTRA"})
    void inconsistentDeclaredResourcesCannotReceiveTheBinding(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts(false, mutation));
    }

    private JsonNode facts(boolean custom, String mutation) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data")) : DeploymentRemoteLayout.original();
        var server = new ServerId("fixture");
        String original = DeploymentCandidateChannel.capacityScript(layout);
        String script = switch (mutation) {
            case "SCRIPT" -> original + "printf altered\n";
            case "ENVIRONMENT" -> original.replace("/usr/bin/env -i ", "/usr/bin/env ");
            case "STAT_PATH" -> original.replace("/usr/bin/stat ", "stat ");
            case "SHELL_PATH" -> original.replace("/bin/bash --noprofile", "bash --noprofile");
            default -> original;
        };
        if (Set.of("SCRIPT", "ENVIRONMENT", "STAT_PATH", "SHELL_PATH").contains(mutation))
            assertNotEquals(original, script);
        var targets = new HashSet<ActionTarget>();
        targets.add(new ActionTarget(mutation.equals("SERVER") ? "other" : server.id(), "deployment-workspace",
            DeploymentCandidateChannel.capacityTarget(layout) + (mutation.equals("TARGET") ? "/other" : "")));
        if (mutation.equals("EXTRA")) targets.add(new ActionTarget(server.id(), "deployment-workspace", "/other"));
        try (var request = new CommandRequest(script, mutation.equals("STDIN") ? new byte[]{7} : new byte[0], Duration.ofSeconds(20), 1024)) {
            var intent = RemoteActions.command(server, "a".repeat(64), request).withSemantics(new ActionSemantics("deployment.candidate.capacity.v1", targets));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
        }
    }
}

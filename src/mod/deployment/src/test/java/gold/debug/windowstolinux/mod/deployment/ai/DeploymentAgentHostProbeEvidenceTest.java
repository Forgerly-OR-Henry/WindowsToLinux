package gold.debug.windowstolinux.mod.deployment.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentHostProbeEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHostProbe;
import org.junit.jupiter.api.Test;

/** Controlled request validation only; no live model or SSH. / 仅受控请求校验，不调用真实模型或 SSH。 */
class DeploymentAgentHostProbeEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    private final ServerId server = new ServerId("baseline-fixture");

    private Map<String, Object> current(String operation, String script, byte[] input, long limit) throws Exception {
        var method = Class.forName("gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence")
            .getDeclaredMethod("facts", gold.debug.windowstolinux.appapi.task.action.ActionIntent.class, CommandRequest.class);
        method.setAccessible(true);
        try (var request = new CommandRequest(script, input, Duration.ofSeconds(90), limit)) {
            var intent = RemoteActions.command(server, "fixture-route", request).withSemantics(new ActionSemantics(operation,
                Set.of(new ActionTarget(server.id(), "deployment-host", server.id()))));
            var facts = new LinkedHashMap<String, Object>();
            ((Map<?, ?>) method.invoke(null, intent, request)).forEach((key, value) -> facts.put((String) key, value));
            return facts;
        }
    }

    private Map<String, Object> baseline() throws Exception {
        return current("deployment.candidate.host-probe.v1", DeploymentAgentHostProbe.baseline(), new byte[0], 4096);
    }

    @Test
    void navigationBindsDisplayedScriptAndEveryRangeWithoutInventingRemoteHashesOrStdin() throws Exception {
        var current = baseline();
        var facts = json.valueToTree(DeploymentAgentHostProbeEvidence.select(json.valueToTree(current)).orElseThrow());
        assertEquals("EMPTY", current.get("inputKind"));
        assertFalse(current.containsKey("completeExecutableInput"));
        assertEquals(sha256(DeploymentAgentHostProbe.baseline()), facts.path("scriptSha256").asText());
        assertEquals(json.valueToTree(List.of("apt-get", "dnf", "systemctl")), facts.path("presenceOnly"));
        assertTrue(facts.path("meaning").asText().contains("No remote binary SHA"));
        String source = current.get("exactAction").toString();
        for (var location : facts.path("locations")) {
            assertEquals("/exactAction", location.path("sourcePointer").asText());
            int start = location.path("startCharacter").asInt(), end = location.path("endCharacter").asInt();
            String fragment = source.substring(start, end);
            assertEquals(sha256(fragment), location.path("utf8Sha256").asText());
            assertEquals(1 + source.substring(0, start).chars().filter(c -> c == '\n').count(), location.path("startLine").asInt());
            assertEquals(location.path("startLine").asInt() + fragment.stripTrailing().chars().filter(c -> c == '\n').count(),
                location.path("endLine").asInt());
        }
    }

    @Test
    void sameLabelAndChangedSourceCannotBorrowNavigationAndNonemptyInputOrWrongBoundsReject() throws Exception {
        for (String script : List.of(DeploymentAgentHostProbe.baseline() + "printf changed > /etc/unreviewed\n",
            DeploymentAgentHostProbe.baseline().replace("/usr/bin/uname -m", "/usr/bin/uname -a"),
            DeploymentAgentHostProbe.baseline().replace("PATH=/usr/sbin:/usr/bin:/sbin:/bin", "PATH=/tmp:/usr/bin"),
            ". /tmp/unverified.sh\n")) {
            var current = current("deployment.candidate.host-probe.v1", script, new byte[0], 4096);
            assertTrue(DeploymentAgentHostProbeEvidence.select(json.valueToTree(current)).isEmpty());
            current.put(DeploymentAgentHostProbeEvidence.FIELD, Map.of("scriptSha256", "forged"));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHostProbeEvidence.select(json.valueToTree(current)));
        }
        for (var current : List.of(current("deployment.candidate.host-probe.v1", DeploymentAgentHostProbe.baseline(), new byte[]{1}, 4096),
            current("deployment.candidate.host-probe.v1", DeploymentAgentHostProbe.baseline(), new byte[0], 8192)))
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHostProbeEvidence.select(json.valueToTree(current)));
        assertTrue(DeploymentAgentHostProbeEvidence.select(json.valueToTree(current("other-operation", DeploymentAgentHostProbe.baseline(), new byte[0], 4096))).isEmpty());
    }

    @Test
    void conversationRegeneratesSourceAndOriginalBindingBeforeApiAndRejectsInjectedNavigation() throws Exception {
        var fixture = new NativeAiFixture();
        var conversation = new DeploymentApprovalConversation(fixture.context, fixture.task, server, "fixture-fingerprint", "a".repeat(64), "fixture-route");
        var current = baseline();
        var bound = conversation.facts("Review current command", current, "fixture-route", fixture.task);
        var original = (ObjectNode) json.valueToTree(bound);
        assertTrue(conversation.messages(original.toString(), "fixture-route", fixture.task).getFirst().text()
            .contains(DeploymentAgentHostProbeEvidence.INSTRUCTION));
        for (String field : List.of("scriptSha256", "scriptBytes", "presenceOnly", "locations")) {
            var changed = original.deepCopy();
            ((ObjectNode) changed.path(DeploymentAgentHostProbeEvidence.FIELD)).put(field, "forged");
            assertThrows(IllegalArgumentException.class, () -> conversation.messages(changed.toString(), "fixture-route", fixture.task));
        }
        for (String field : List.of("reviewSession", "serverTargets", "resourceTargets", "exactAction", "operation", "inputKind")) {
            var changed = original.deepCopy().put(field, "forged");
            assertThrows(IllegalArgumentException.class, () -> conversation.messages(changed.toString(), "fixture-route", fixture.task));
        }
        current.put(DeploymentAgentHostProbeEvidence.FIELD, Map.of());
        assertThrows(IllegalArgumentException.class, () -> conversation.facts("Review", current, "fixture-route", fixture.task));
        assertTrue(fixture.requests.isEmpty());
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}

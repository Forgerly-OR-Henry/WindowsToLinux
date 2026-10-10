package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentInlineApprovalEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ServerId SERVER = new ServerId("original-server");

    private static final String REVISION = "c".repeat(64);

    private static final String OPERATION = "a".repeat(32);

    @Test
    void actualApprovalRequestKeepsCompleteControllerAndCurrentNavigationWithFinalDenial() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.configurationRevision = REVISION;
        var conversation = conversation(fixture);
        var ai = new DeploymentAiJson(fixture.context, conversation);
        fixture.nativeReply(new AiToolCall("current-controller-denial", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Independent current controller refusal\"}"));
        var original = facts();
        var verdict = ai.askCommand("Review the exact current Agent preparation", original,
            REVISION, fixture.task, DeploymentReviewDecision::malformed);
        assertEquals("DENY", verdict.path("decision").asText());
        assertEquals(1, fixture.requests.size(), "A valid denial is retained without automatic re-review");
        assertTrue(fixture.replies.isEmpty());
        var request = fixture.requests.getFirst();
        var current = JSON.readTree(request.messages().getLast().text());
        assertEquals(DeploymentAgentToolchainPrograms.controller(), current.path("completeExecutableInput").asText());
        assertEquals(original.get("exactAction"), current.path("exactAction").asText());
        var evidence = current.path(DeploymentAgentToolchainInputEvidence.FIELD);
        assertEquals(JSON.valueToTree(DeploymentAgentToolchainInputEvidence.select(current).orElseThrow().facts()), evidence);
        assertTrue(evidence.path("locations").size() >= 20);
        String policy = request.messages().getFirst().text();
        assertTrue(policy.contains("mechanically bound Agent prepare/bind entrypoints only"));
        assertTrue(policy.contains("recursive disclosure of their source is not required"));
        assertTrue(policy.contains("Vendor execution at root, a privileged vendor callback"));
        assertTrue(policy.contains("unresolved enforcement check still requires DENY"));
        assertFalse(evidence.has("liveIsolationVerified"));
        assertFalse(evidence.has("approved"));
    }

    @Test
    void changedNavigationSourceAndClaimsAreRejectedBeforeAnotherApprovalRequest() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.configurationRevision = REVISION;
        var conversation = conversation(fixture);
        var current = (ObjectNode) JSON.valueToTree(conversation.facts("Review complete current preparation", facts(), REVISION, fixture.task));
        for (String mutation : List.of("location", "source", "digest", "extra")) {
            var changed = current.deepCopy();
            var binding = (ObjectNode) changed.path(DeploymentAgentToolchainInputEvidence.FIELD);
            switch (mutation) {
                case "location" -> ((ObjectNode) binding.path("locations").get(0)).put("startLine", 100000);
                case "source" ->
                    changed.put("completeExecutableInput", changed.path("completeExecutableInput").asText() + "\n# changed");
                case "digest" -> binding.put("bundledSourceSha256", "d".repeat(64));
                case "extra" -> binding.put("liveIsolationVerified", true);
            }
            assertThrows(IllegalArgumentException.class, () -> conversation.messages(JSON.writeValueAsString(changed), REVISION, fixture.task), mutation);
        }
        var forged = new LinkedHashMap<>(facts());
        forged.put(DeploymentAgentToolchainInputEvidence.FIELD, Map.of("locations", List.of()));
        assertThrows(IllegalArgumentException.class, () -> conversation.facts("Review current preparation", forged, REVISION, fixture.task));
        assertEquals(0, fixture.requests.size());
    }

    private static DeploymentApprovalConversation conversation(NativeAiFixture fixture) {
        return new DeploymentApprovalConversation(fixture.context, fixture.task, SERVER, "verified-fingerprint", "a".repeat(64), REVISION);
    }

    private static Map<String, Object> facts() throws Exception {
        var evidence = Class.forName("gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence")
            .getDeclaredMethod("facts", ActionIntent.class, CommandRequest.class);
        evidence.setAccessible(true);
        String source = DeploymentAgentToolchainPrograms.controller();
        var arguments = List.of("prepare", "RUST", "1.95", "1.95.0", "60", "-", OPERATION,
            DeploymentAgentToolchainReleaseTest.encoded("RUST", "1.95", "1.95.0", OPERATION));
        try (var request = new CommandRequest(DeploymentAgentToolchainPrograms.command(arguments), source.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 65536)) {
            var intent = RemoteActions.command(SERVER, REVISION, request).withSemantics(new ActionSemantics("deployment.agent.toolchain.prepare.v1",
                DeploymentAgentToolchainCommands.controllerTargets(SERVER, OPERATION)));
            var facts = new LinkedHashMap<String, Object>();
            ((Map<?, ?>) evidence.invoke(null, intent, request)).forEach((key, value) -> facts.put((String) key, value));
            return facts;
        }
    }
}

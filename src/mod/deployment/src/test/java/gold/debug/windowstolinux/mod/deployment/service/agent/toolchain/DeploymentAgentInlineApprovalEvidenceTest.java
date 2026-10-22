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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"PYTHON", "NATIVE_BUILD"})
    void actualNativeApprovalKeepsFullSourceClosureAndBaselineWithFinalDenial(String profile) throws Exception {
        var fixture = new NativeAiFixture();
        fixture.configurationRevision = REVISION;
        var conversation = conversation(fixture);
        fixture.nativeReply(new AiToolCall("current-native-denial", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Independent current transaction refusal\"}"));
        var verdict = new DeploymentAiJson(fixture.context, conversation).askCommand("Review the exact current OS dependency transaction",
            facts(profile), REVISION, fixture.task, DeploymentReviewDecision::malformed);
        assertEquals("DENY", verdict.path("decision").asText());
        assertEquals(1, fixture.requests.size());
        var current = JSON.readTree(fixture.requests.getFirst().messages().getLast().text());
        assertEquals(DeploymentAgentToolchainPrograms.dependencies(), current.path("completeExecutableInput").asText());
        var evidence = current.path(DeploymentAgentToolchainInputEvidence.FIELD);
        assertEquals(JSON.valueToTree(DeploymentAgentToolchainInputEvidence.select(current).orElseThrow().facts()), evidence);
        assertEquals(profile, evidence.path("profile").asText());
        assertTrue(evidence.path("locations").size() >= 15);
        assertEquals("CURRENT_SOURCE_AND_DECLARED_SCOPE_ONLY", evidence.path("nativeTransaction").path("evidenceKind").asText());
        for (String mutation : List.of("location", "scope", "baseline", "claim")) {
            var changed = (ObjectNode) current.deepCopy();
            var binding = (ObjectNode) changed.path(DeploymentAgentToolchainInputEvidence.FIELD);
            var transaction = (ObjectNode) binding.path("nativeTransaction");
            switch (mutation) {
                case "location" -> ((ObjectNode) binding.path("locations").get(0)).put("startLine", 100000);
                case "scope" -> transaction.withArray("transactionTargets").remove(0);
                case "baseline" -> transaction.put("osBaseline", "Fixed OS SHA already verified");
                case "claim" -> transaction.put("approved", true);
            }
            assertThrows(IllegalArgumentException.class,
                () -> conversation.messages(JSON.writeValueAsString(changed), REVISION, fixture.task), mutation);
        }
        assertEquals(1, fixture.requests.size(), "Changed evidence cannot create another approval request");
    }

    private static DeploymentApprovalConversation conversation(NativeAiFixture fixture) {
        return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApprovalConversation(fixture.context, fixture.task, SERVER, "verified-fingerprint", "a".repeat(64), REVISION);
    }

    private static Map<String, Object> facts() throws Exception {
        return facts("prepare");
    }

    private static Map<String, Object> facts(String profile) throws Exception {
        boolean nativeTransaction = !profile.equals("prepare");
        String source = nativeTransaction ? DeploymentAgentToolchainPrograms.dependencies() : DeploymentAgentToolchainPrograms.controller();
        var arguments = nativeTransaction ? List.of(profile, OPERATION)
            : List.of("prepare", "RUST", "1.95", "1.95.0", "60", "-", OPERATION,
            DeploymentAgentToolchainReleaseTest.encoded("RUST", "1.95", "1.95.0", OPERATION));
        try (var request = new CommandRequest(DeploymentAgentToolchainPrograms.command(arguments), source.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 65536)) {
            var intent = RemoteActions.command(SERVER, REVISION, request).withSemantics(new ActionSemantics(
                "deployment.agent.toolchain." + (nativeTransaction ? "dependencies" : "prepare") + ".v1",
                nativeTransaction ? DeploymentAgentToolchainCommands.dependencyTargets(SERVER, profile)
                    : DeploymentAgentToolchainCommands.controllerTargets(SERVER, OPERATION)));
            var facts = new LinkedHashMap<String, Object>();
            facts.putAll(gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol.facts(intent, request));
            return facts;
        }
    }
}

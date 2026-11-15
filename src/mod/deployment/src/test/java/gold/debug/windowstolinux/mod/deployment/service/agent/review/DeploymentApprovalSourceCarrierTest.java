package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.ai.review.MirroredInstallerTestFixture;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentApprovalSourceCarrierTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String current(MirroredInstallerTestFixture fixture, DeploymentAgentApprovalConversation conversation,
                                  gold.debug.windowstolinux.appapi.linux.command.CommandRequest command) throws Exception {
        return DeploymentAiJson.facts(conversation.facts("Review exact complete installer", fixture.facts("TRACKED", command),
            fixture.model.configurationRevision, fixture.task), 1048576);
    }

    @Test
    void quotesBackslashesNonBmpOriginalNewlinesAndDelimiterTextAreRawUserData() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = fixture.request(fixture.installer("TRACKED", true)
            + "\r\n# exact quote \" ' slash \\ nonBMP 🙂\n# WTL-APPROVAL-SOURCE-FRAME-1\r\n")) {
            var conversation = fixture.conversation();
            String complete = current(fixture, conversation, command);
            var messages = conversation.messages(complete, fixture.model.configurationRevision, fixture.task);
            JsonNode restored = DeploymentApprovalSourceCarrier.restore(complete, messages);
            assertEquals(JSON.readTree(complete), restored);
            assertArrayEquals(command.stdin(), DeploymentInstallerEvidence.reconstruct(restored.path("completeExecutableInput")).getBytes(StandardCharsets.UTF_8));
            var manifest = JSON.readTree(messages.getLast().text()).path(DeploymentApprovalSourceCarrier.FIELD);
            assertEquals(DeploymentInstallerEvidence.readableBodies(restored.path("completeExecutableInput")).size(),
                manifest.path("frames").size());
            assertEquals(MirroredInstallerTestFixture.sha(complete), manifest.path("currentSha256").asText());
            int runtimeInsertions = 0;
            for (var row : manifest.path("frames")) {
                String raw = restored.at(row.path("pointer").asText()).textValue();
                var message = messages.get(row.path("messageIndex").intValue());
                assertEquals(AiRole.USER, message.role());
                var insertion = row.path("jsonStringInsertion");
                String expected = raw;
                if (insertion.isObject()) {
                    assertEquals(DeploymentInstallerEvidence.MIRROR_SOURCE_POINTER, row.path("pointer").asText());
                    runtimeInsertions++;
                    int offset = insertion.path("bodyOffset").intValue();
                    String shell = restored.at(insertion.path("sourcePointer").textValue()).textValue();
                    String repeated = JSON.writeValueAsString(shell.substring(insertion.path("sourceOffset").intValue(),
                        insertion.path("sourceNext").intValue()));
                    assertEquals(repeated, raw.substring(offset, offset + insertion.path("encodedCharacters").intValue()));
                    expected = raw.substring(0, offset) + raw.substring(offset + repeated.length());
                }
                assertEquals(expected, message.text().substring(row.path("headerCharacters").intValue()));
                if (raw.length() > 100) assertFalse(messages.getFirst().text().contains(raw), "No complete executable body belongs in SYSTEM");
            }
            assertEquals(1, runtimeInsertions);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "LITERAL", "SAME_LENGTH", "MISSING", "DUPLICATE", "ORDER", "UNKNOWN_POINTER",
        "HEADER_LENGTH", "ROLE", "EXTRA_PREFIX_REBASED", "UNKNOWN_PREFIX_REBASED", "CROSS_CURRENT", "CROSS_TASK", "CROSS_REVISION", "CROSS_SERVER", "CROSS_FINGERPRINT", "CROSS_SOURCE_REVISION"})
    void alteredMissingRepeatedReorderedOrForeignFramesRefuseBeforeSend(String mutation) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = fixture.request(fixture.installer("TRACKED", true))) {
            var conversation = fixture.conversation();
            String complete = current(fixture, conversation, command);
            var messages = new ArrayList<>(conversation.messages(complete, fixture.model.configurationRevision, fixture.task));
            ObjectNode projection = (ObjectNode)JSON.readTree(messages.getLast().text());
            var frames = projection.at("/approvalSourceCarrier/frames");
            int first = frames.get(0).path("messageIndex").intValue();
            switch (mutation) {
                case "SOURCE", "LITERAL", "SAME_LENGTH" -> {
                    var row = frames.get(mutation.equals("LITERAL") ? 3 : 0);
                    int index = row.path("messageIndex").intValue(), start = row.path("headerCharacters").intValue();
                    String text = messages.get(index).text();
                    String changed = mutation.equals("SAME_LENGTH")
                        ? text.substring(0, start) + (text.charAt(start) == '#' ? '!' : '#') + text.substring(start + 1)
                        : text.substring(0, start) + "#" + text.substring(start);
                    assertEquals(text.length() + (mutation.equals("SAME_LENGTH") ? 0 : 1), changed.length());
                    messages.set(index, user(changed));
                }
                case "MISSING" -> messages.remove(first);
                case "DUPLICATE" -> messages.add(first, messages.get(first));
                case "ORDER" -> Collections.swap(messages, first, first + 1);
                case "ROLE" -> messages.set(first, new AiMessage(AiRole.SYSTEM, messages.get(first).text(), List.of()));
                case "EXTRA_PREFIX_REBASED", "UNKNOWN_PREFIX_REBASED" -> {
                    messages.add(first, mutation.equals("EXTRA_PREFIX_REBASED") ? messages.get(first)
                        : user(DeploymentApprovalSourceCarrier.FRAME_PREFIX + "{\"unknown\":true}\nextra-source"));
                    // Rebase every manifest and body descriptor index, so rejection cannot rely on a stale index.
                    for (var row : frames) {
                        ((ObjectNode)row).put("messageIndex", row.path("messageIndex").intValue() + 1);
                        ((ObjectNode)projection.at(row.path("pointer").asText())).put("messageIndex", row.path("messageIndex").intValue());
                    }
                    messages.set(messages.size() - 1, user(projection.toString()));
                }
                case "UNKNOWN_POINTER" -> {
                    ((ObjectNode)frames.get(0)).put("pointer", "/completeExecutableInput/sources/unknown");
                    messages.set(messages.size() - 1, user(projection.toString()));
                }
                case "HEADER_LENGTH" -> {
                    ((ObjectNode)frames.get(0)).put("headerCharacters", frames.get(0).path("headerCharacters").intValue() + 1);
                    messages.set(messages.size() - 1, user(projection.toString()));
                }
                case "CROSS_CURRENT", "CROSS_TASK", "CROSS_REVISION", "CROSS_SERVER", "CROSS_FINGERPRINT", "CROSS_SOURCE_REVISION" -> {
                    ObjectNode changed = (ObjectNode)JSON.readTree(complete);
                    if (mutation.equals("CROSS_CURRENT")) changed.put("currentReviewContract", "A different original current contract");
                    else ((ObjectNode)changed.path("reviewSession")).put(switch (mutation) {
                        case "CROSS_TASK" -> "task"; case "CROSS_REVISION" -> "configurationRevision"; case "CROSS_SERVER" -> "server";
                        case "CROSS_FINGERPRINT" -> "fingerprint"; case "CROSS_SOURCE_REVISION" -> "originalSourceRevision"; default -> throw new AssertionError(mutation);
                    }, "foreign-binding");
                    String other = changed.toString();
                    assertThrows(IllegalArgumentException.class, () -> DeploymentApprovalSourceCarrier.restore(other, messages), "Old frames never supply a different current");
                    if (!mutation.equals("CROSS_CURRENT"))
                        assertThrows(IllegalArgumentException.class, () -> conversation.messages(other, fixture.model.configurationRevision, fixture.task));
                    assertTrue(fixture.model.requests.isEmpty());
                    return;
                }
                default -> throw new AssertionError(mutation);
            }
            assertThrows(IllegalArgumentException.class, () -> DeploymentApprovalSourceCarrier.restore(complete, messages));
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @Test
    void callerCannotPresetCarrierAndNavigationCannotChangeCurrentFacts() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = fixture.request(fixture.installer("TRACKED", true))) {
            var conversation = fixture.conversation();
            var supplied = new LinkedHashMap<>(fixture.facts("TRACKED", command));
            supplied.put(DeploymentApprovalSourceCarrier.FIELD, Map.of("format", DeploymentApprovalSourceCarrier.FORMAT));
            assertThrows(IllegalArgumentException.class, () -> conversation.facts("review", supplied, fixture.model.configurationRevision, fixture.task));
            ObjectNode changed = (ObjectNode)JSON.readTree(current(fixture, conversation, command));
            ((ObjectNode)changed.at("/helperInstallationSourceEvidence/installerLocations/0")).put("characters", 1);
            assertThrows(IllegalArgumentException.class, () -> conversation.messages(changed.toString(), fixture.model.configurationRevision, fixture.task));
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @Test
    void boundedFormatCorrectionRebuildsTheSameCompleteCurrentFrames() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = fixture.request(fixture.installer("TRACKED", true))) {
            fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("APPROVE", "fixture", "stop",
                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), List.of(), Optional.empty())), Optional.empty()));
            fixture.model.nativeReply(new AiToolCall("corrected-denial", "submit_result", "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Current complete evidence still requires denial\"}"));
            var conversation = fixture.conversation();
            var result = new DeploymentAiJson(fixture.context, conversation).askCommand("Review full installer", fixture.facts("TRACKED", command),
                fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed);
            assertEquals("DENY", result.path("decision").asText());
            assertEquals(2, fixture.model.requests.size());
            var first = fixture.model.requests.get(0).messages();
            var second = fixture.model.requests.get(1).messages();
            assertEquals(first.getLast(), second.getLast(), "Correction keeps the final exact current manifest");
            var manifest = JSON.readTree(first.getLast().text()).at("/approvalSourceCarrier/frames");
            for (var row : manifest) {
                int index = row.path("messageIndex").intValue();
                assertEquals(first.get(index), second.get(index), "No mutable previous-source bypass during correction");
            }
            assertTrue(second.get(second.size() - 2).text().startsWith("SUBMISSION_REJECTED: native submit_result is missing."));
            assertEquals(MirroredInstallerTestFixture.restoreCarrier(first), MirroredInstallerTestFixture.restoreCarrier(second));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "APPROVE"})
    void completeCarrierNeverGrantsAuthorityAndEveryApproveNeedsFreshOriginalHostGrant(String decision) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = fixture.request(fixture.installer("TRACKED", true))) {
            var grants = new AtomicInteger();
            var identities = new HashSet<UUID>();
            var host = (InteractionService)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p,m,a) -> {
                assertEquals("approveReviewedAction", m.getName());
                assertEquals(fixture.intent("TRACKED", command), a[0]);
                assertEquals(Optional.of(command), a[1]);
                assertSame(fixture.task, a[4]);
                assertEquals("APPROVE", decision);
                grants.incrementAndGet();
                var grant = new ApprovalGrant(UUID.randomUUID());
                assertTrue(identities.add(grant.id()));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            var conversation = fixture.conversation();
            var scope = new DeploymentCommandReviewScope(fixture.context, host, fixture.task, "AUTOMATIC", false,
                () -> fixture.model.configurationRevision, () -> conversation);
            int attempts = decision.equals("APPROVE") ? 2 : 1;
            for (int index = 0; index < attempts; index++) {
                fixture.model.nativeReply(new AiToolCall("current-verdict-" + index, "submit_result", "{\"decision\":\"" + decision + "\",\"requiresUserConfirmation\":false,\"reason\":\"Fresh independent current verdict\"}"));
                if (decision.equals("DENY")) {
                    var failure = assertThrows(ApiException.class, () -> DeploymentCommandReviewRouter.approve(scope.interaction(), fixture.intent("TRACKED", command), command, fixture.task));
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", failure.problem().eventCode());
                } else assertEquals(TaskResult.SUCCEEDED, DeploymentCommandReviewRouter.approve(scope.interaction(), fixture.intent("TRACKED", command), command, fixture.task).outcome());
            }
            assertEquals(attempts, fixture.model.requests.size());
            assertEquals(decision.equals("APPROVE") ? 2 : 0, grants.get());
        }
    }

    private static AiMessage user(String text) { return new AiMessage(AiRole.USER, text, List.of()); }
}

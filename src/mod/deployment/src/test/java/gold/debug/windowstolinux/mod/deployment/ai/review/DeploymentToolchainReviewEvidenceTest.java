package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentToolchainReviewEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int R19_REMAINING_BYTES = 544107;
    private static final String PHASE = "toolchainReviewEvidence";

    @Test
    void dependencyReviewRejectsAWrapperForAnotherTaskOrBudgetBeforeModelAndHost() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var other = new MirroredInstallerTestFixture();
             var original = request("dependencies", fixture)) {
            String body = DeploymentMirrorTasks.originalCommand(original.script());
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            for (String wrapper : List.of(DeploymentMirrorTasks.preparationCommand(body, other.task, 120),
                DeploymentMirrorTasks.preparationCommand(body, fixture.task, 119))) {
                try (var request = new CommandRequest(wrapper, original.stdin(), original.timeout(), original.outputLimitBytes())) {
                    var intent = intent("dependencies", fixture, request);
                    assertThrows(IllegalArgumentException.class,
                        () -> protocol.inspect(intent, request, fixture.layout, fixture.task));
                }
            }
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "bind", "dependencies"})
    void responsesCarryCompleteCurrentBytesOnceAndKeepFreshHostAuthorization(String kind) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = request(kind, fixture)) {
            var intent = intent(kind, fixture, request);
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            var base = protocol.inspect(intent, request, fixture.layout, fixture.task);
            DeploymentCurrentCommandEvidence observed = kind.equals("prepare") ? assisted.scope : (action, command, task) -> {
                var current = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(action, task));
                if (kind.equals("dependencies")) current.put("currentNativeBuildPrerequisites", Map.of("fixedTransaction",
                    DeploymentToolchainDependencies.inspect(action, command, fixture.layout)));
                return current;
            };
            Callable<OperationResult<ApprovalGrant>> review = kind.equals("prepare")
                ? () -> ((DeploymentCommandReview) assisted.scope.interaction()).approveCommand(intent, request, fixture.task)
                : () -> DeploymentFixedCommandApproval.approveWithEvidence(assisted.context, assisted.host, intent, request,
                    fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false, observed);

            reply(fixture, verdict("denied", "DENY"));
            var denied = assertThrows(ApiException.class, review::call);
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
            assertEquals(0, assisted.hosts.get());
            var initial = fixture.model.requests.getFirst();
            byte[] wire = wire(initial, R19_REMAINING_BYTES);
            var projected = JSON.readTree(initial.messages().getLast().text());
            var restored = restore(JSON.readTree(wire));
            var expected = new LinkedHashMap<>(base);
            expected.put("currentCommandEvidence", observed.facts(intent, request, fixture.task));
            expected.put("authorizationPolicy", "AUTOMATIC");
            expected.put("effectSummary", intent.effectSummary());
            expected.put("presentationLocale", "en");
            expected.put("commandAuthorizationStage", Map.of("phase", "INDEPENDENT_REVIEW_BEFORE_HOST_GRANT",
                "hostGrantRequestedOnlyAfterApprove", true, "hostGrantIssuedByThisBoundary", false, "exactHostGrantRequiredBeforeDispatch", true));
            assertEquals(JSON.readTree(DeploymentAiJson.facts(expected, 1048576)), restored);
            assertEquals(intent.exactAction(), restored.path("exactAction").asText());
            if (kind.equals("dependencies"))
                assertArrayEquals(request.stdin(), restored.path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8));
            assertEquals(AiPurpose.APPROVAL, initial.purpose());
            assertEquals(65536, request.outputLimitBytes());
            assertEquals(1048576, initial.maxRequestBytes());
            var baselineMessages = List.of(initial.messages().getFirst(), new AiMessage(AiRole.USER,
                DeploymentAiJson.facts(expected, 1048576), List.of()));
            int baseline = wire(copy(initial, baselineMessages, initial.maxRequestBytes()), initial.maxRequestBytes()).length;
            assertTrue(baseline - wire.length > 130000, "Real Responses encoding should show removal of duplicate executable bytes");
            System.out.printf("TOOLCHAIN_REVIEW_WIRE kind=%s originalFactsCharacters=%d projectedFactsCharacters=%d baselineBytes=%d initialBytes=%d ceiling=%d%n",
                kind, restored.toString().length(), initial.messages().getLast().text().length(), baseline, wire.length, R19_REMAINING_BYTES);

            String snapshot = projected.at("/" + PHASE + "/snapshotSha256").asText();
            reply(fixture, read("stale", "0".repeat(64), "/exactAction", 0));
            reply(fixture, read("exact", snapshot, "/exactAction", 0));
            String sourcePointer = kind.equals("dependencies") ? "/completeExecutableInput" : "/" + PHASE + "/installedHelperSource";
            reply(fixture, read("source", snapshot, sourcePointer, 0));
            reply(fixture, verdict("approved", "APPROVE"));
            assertEquals(TaskResult.SUCCEEDED, review.call().outcome());
            assertEquals(List.of(intent), assisted.grantedIntents);
            assertEquals(5, fixture.model.requests.size());
            assertEquals(initial.messages(), fixture.model.requests.get(1).messages(), "Every review starts with only the complete current facts");
            var finalRequest = fixture.model.requests.getLast();
            var pages = finalRequest.messages().stream().flatMap(message -> message.toolResult().stream()).toList();
            assertEquals(3, pages.size());
            assertTrue(pages.get(0).error());
            assertFalse(pages.get(1).error());
            assertFalse(pages.get(2).error());
            assertPage(pages.get(1), snapshot, intent.exactAction());
            assertPage(pages.get(2), snapshot, kind.equals("dependencies") ? restored.path("completeExecutableInput").asText()
                : DeploymentManagedHelperBundle.renderScript(fixture.layout));
            System.out.printf("TOOLCHAIN_REVIEW_CONTINUATION kind=%s actualBytes=%d ceiling=%d%n", kind,
                wire(finalRequest, R19_REMAINING_BYTES).length, R19_REMAINING_BYTES);
        }
    }

    private static CommandRequest request(String kind, MirroredInstallerTestFixture fixture) {
        byte[] stdin = new byte[0];
        String command;
        long seconds;
        if (kind.equals("dependencies")) {
            seconds = 120;
            command = DeploymentToolchainDependencies.command(DeploymentToolchainDependencies.frame("NATIVE_BUILD", "b".repeat(32),
                seconds, fixture.server, fixture.task.id().toString()));
            stdin = DeploymentToolchainDependencies.dependencies(fixture.layout).getBytes(StandardCharsets.UTF_8);
        } else {
            seconds = kind.equals("prepare") ? 135 : 180;
            String arguments = kind.equals("prepare") ? "'prepare' 'NODE' '22' '-' '120' '-'" : "'bind' '" + binding(fixture) + "'";
            command = DeploymentManagedHelperBundle.verifiedCommand("'" + fixture.layout.helperPath() + "' prepare-toolchains " + arguments, fixture.layout);
        }
        String wrapped = DeploymentMirrorTasks.preparationCommand(command, fixture.task, seconds);
        return new CommandRequest(wrapped, stdin, Duration.ofSeconds(seconds), 65536);
    }

    private static ActionIntent intent(String kind, MirroredInstallerTestFixture fixture, CommandRequest request) {
        var targets = kind.equals("dependencies")
            ? DeploymentToolchainDependencies.dependencyTargets(fixture.server, "NATIVE_BUILD", "b".repeat(32), fixture.layout)
            : Set.of(new ActionTarget(fixture.server.id(), "deployment-build", fixture.layout.toolchains()));
        return RemoteActions.command(fixture.server, "a".repeat(64), request).withSemantics(new ActionSemantics("deployment.toolchain." + kind + ".v1", targets));
    }

    private static String binding(MirroredInstallerTestFixture fixture) {
        String payload = "WTL-TOOLS-1\tcurrent\nJAVA\tLANGUAGE_TARGET\tMjE=\tcG9tLnhtbA==\tSERIES\t21\t21.0.12.1+1\t"
            + fixture.layout.toolchains() + "/versions/java-" + "c".repeat(64) + "\tSYSTEM\tc3lzdGVtOi91c3IvYmluL2phdmE=\t" + "d".repeat(64) + "\n";
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode restore(JsonNode wire) throws Exception {
        var messages = wire.path("input");
        var projection = (ObjectNode) JSON.readTree(messages.get(messages.size() - 1).at("/content/0/text").asText());
        var manifest = projection.path(PHASE);
        var sources = new LinkedHashMap<String, String>();
        int firstFrame = messages.size() - manifest.path("frames").size() - 1;
        for (var frame : manifest.path("frames")) {
            var message = messages.get(firstFrame + frame.path("ordinal").intValue());
            assertEquals("user", message.path("role").asText());
            String source = message.at("/content/0/text").asText().substring(frame.path("headerCharacters").intValue());
            assertIdentity(frame, source);
            assertNull(sources.put(frame.path("sourcePointer").asText(), source));
        }
        String exact = restoreValue(projection.path("exactAction"), sources);
        projection.put("exactAction", exact);
        if (projection.has("completeExecutableInput")) {
            projection.put("completeExecutableInput", restoreValue(projection.path("completeExecutableInput"), sources));
            var script = projection.path("commandScript");
            String body = exact.substring(script.path("startCharacter").intValue());
            assertIdentity(script, body);
            projection.put("commandScript", body);
            var transaction = (ObjectNode) projection.at("/currentCommandEvidence/currentNativeBuildPrerequisites");
            assertEquals("/toolchainDependencies", transaction.at("/fixedTransaction/sourcePointer").asText());
            transaction.set("fixedTransaction", projection.path("toolchainDependencies"));
        } else {
            ((ObjectNode) projection.path("mirrorTransport")).put("runtimeSource", sources.get("/mirrorTransport/runtimeSource"));
        }
        projection.remove(PHASE);
        return projection;
    }

    private static String restoreValue(JsonNode value, Map<String, String> sources) throws Exception {
        if (value.isTextual()) return value.textValue();
        var result = new StringBuilder();
        for (var part : value.path("segments")) {
            String source = sources.get(part.path("sourcePointer").asText());
            result.append(switch (part.path("kind").asText()) {
                case "LITERAL" -> part.path("text").asText();
                case "SOURCE_TEXT" -> source;
                case "SHELL_SINGLE_QUOTE" -> "'" + source.replace("'", "'\"'\"'") + "'";
                case "JSON_STRING" -> JSON.writeValueAsString(source);
                default -> throw new AssertionError(part);
            });
        }
        assertIdentity(value, result.toString());
        return result.toString();
    }

    private static void assertIdentity(JsonNode node, String original) throws Exception {
        assertEquals(original.length(), node.path("characters").intValue());
        assertEquals(original.getBytes(StandardCharsets.UTF_8).length, node.path("utf8Bytes").intValue());
        assertEquals(MirroredInstallerTestFixture.sha(original), node.path("sha256").asText());
    }

    private static void assertPage(AiToolResult page, String snapshot, String original) throws Exception {
        var payload = JSON.readTree(page.text());
        assertEquals(snapshot, payload.path("snapshotSha256").asText());
        assertEquals(original.substring(0, 120), payload.at("/value/text").asText());
        assertEquals(120, payload.at("/value/next").intValue());
        assertTrue(payload.at("/value/hasMore").asBoolean());
        assertEquals(MirroredInstallerTestFixture.sha(original), payload.at("/value/sha256").asText());
    }

    private static AiToolCall verdict(String id, String decision) throws Exception {
        return new AiToolCall(id, "submit_result", JSON.writeValueAsString(Map.of("decision", decision,
            "requiresUserConfirmation", false, "reason", "Controlled independent review")));
    }

    private static AiToolCall read(String id, String snapshot, String pointer, int offset) throws Exception {
        return new AiToolCall(id, "evidence", JSON.writeValueAsString(Map.of("snapshotSha256", snapshot, "pointer", pointer, "offset", offset, "limit", 120)));
    }

    private static void reply(MirroredInstallerTestFixture fixture, AiToolCall call) {
        var usage = new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new AiResponse("", "fixture-model", "tool_calls", usage, List.of(call), Optional.empty())), Optional.empty()));
    }

    private static byte[] wire(AiRequest original, int maximum) {
        return ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES).encode("gpt-6-luna",
            copy(original, original.messages(), maximum), 65536, ModelReasoningLevel.HIGH, Map.of());
    }

    private static AiRequest copy(AiRequest original, List<AiMessage> messages, int maximum) {
        return new AiRequest(original.purpose(), messages, original.maxOutputTokens(), original.timeout(), original.jsonObjectResponse(),
            original.tools(), maximum, original.requiredToolName(), original.generationRecoveryAttempts(), Optional.of("gpt-6-luna"), Optional.of(AiReasoningMode.HIGH));
    }
}

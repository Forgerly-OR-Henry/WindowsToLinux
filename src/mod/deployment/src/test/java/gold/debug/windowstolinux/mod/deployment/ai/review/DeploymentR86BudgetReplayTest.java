package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelRequestBudgetException;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentKotlinJavaRequirement;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.AssetLendingBuildReviewFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentR86BudgetReplayTest {
    private static final int WIRE_LIMIT = 1_048_576;
    private static final int RESERVE = 65_536;
    private static final String SOURCE_FRAME = "WTL-TOOLCHAIN-REVIEW-SOURCE-1\n";
    private static final String RUNTIME = "/mirrorTransport/runtimeSource";
    private static final String SHELL = "/toolchainReviewEvidence/mirrorBootstrapShell";
    private static final String POLICY = "/toolchainReviewEvidence/mirrorPolicySource";
    private static final String HELPER = "/toolchainReviewEvidence/installedHelperSource";
    private static final String JAVA_HOME = "/usr/local/lib/windowstolinux/toolchains/versions/java-938fc512b75b867be5675b5a9cb0f43723d9b4127124cf4b8c061591c82c8249";
    private static final String JAVA_RECEIPT = "TOOLCHAIN=JAVA|21.0.13+9|" + JAVA_HOME
        + "|MANAGED|https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz"
        + "|d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c\n";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path scratch;

    @Test
    void assetLendingOriginalArchiveAndBindingAtObservedCeilings() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.DOTNET_METADATA,
                DownloadPoint.DOTNET_SDK, DownloadPoint.NUGET);
             var command = AssetLendingBuildReviewFixture.build(fixture, assetSource(), scratch);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            String prefix = "/buildReviewEvidence/projectArchive/members/";
            var batches = List.of(List.of(
                new Cursor(prefix + 2, 562, 6005), new Cursor(prefix + 3, 0, 565), new Cursor(prefix + 4, 0, 2837),
                new Cursor(prefix + 5, 0, 105), new Cursor(prefix + 6, 0, 1891), new Cursor(prefix + 7, 0, 95)));
            replay("asset-lending", fixture, assisted, command, command.facts(), "buildReviewEvidence",
                DeploymentFixedCommandApproval.commandReviewInstructions(true) + DeploymentAssistedScope.standardBuildReviewInstructions(),
                49_533, new int[] {92_120, 65_640}, batches);
        }
    }

    @Test
    void surveyScoringKotlinPreparationAtObservedCeilings() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.JAVA_JDK,
                DownloadPoint.JAVA_METADATA, DownloadPoint.KOTLIN_DISTRIBUTION, DownloadPoint.KOTLIN_METADATA,
                DownloadPoint.GRADLE_DISTRIBUTION, DownloadPoint.GRADLE_PLUGIN, DownloadPoint.MAVEN_REPOSITORY,
                DownloadPoint.RUBY_METADATA, DownloadPoint.RUBY_DISTRIBUTION, DownloadPoint.RUBYGEMS,
                DownloadPoint.GNU_MAKE_SOURCE, DownloadPoint.NODE_METADATA, DownloadPoint.NODE_DISTRIBUTION, DownloadPoint.NODE_PACKAGE);
             var command = AssetLendingBuildReviewFixture.capture(fixture, "a".repeat(64), new DeploymentBuildBudget(1800),
                Optional.of(JAVA_RECEIPT),
                channel -> channel.prepare(DeploymentToolchainType.KOTLIN, "2.0", "2.0.21", 120,
                    JAVA_HOME,
                    new DeploymentKotlinJavaRequirement(ToolchainRequirement.ConstraintType.SERIES, "21", "21.0.13+9")));
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var facts = new LinkedHashMap<>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(command.intent(), command.request(), fixture.layout, fixture.task));
            facts.put("currentCommandEvidence", assisted.scope.effectFacts(command.intent(), fixture.task));
            var batches = List.of(
                List.of(new Cursor(RUNTIME, 3984, 8000), new Cursor(RUNTIME, 13799, 8000), new Cursor(RUNTIME, 16326, 8000),
                    new Cursor(RUNTIME, 20251, 8000), new Cursor(RUNTIME, 76296, 8000), new Cursor(SHELL, 16505, 8000),
                    new Cursor(POLICY, 0, 8000), new Cursor(POLICY, 8000, 5217)),
                List.of(new Cursor(RUNTIME, 28251, 4530), new Cursor(HELPER, 174000, 8000), new Cursor(HELPER, 201000, 8000)),
                List.of(new Cursor(RUNTIME, 13799, 8000), new Cursor(RUNTIME, 21799, 6452), new Cursor(RUNTIME, 76296, 8000),
                    new Cursor(SHELL, 16505, 8000), new Cursor(POLICY, 0, 8000), new Cursor(POLICY, 8000, 5217)));
            replay("survey-scoring", fixture, assisted, command, facts, "toolchainReviewEvidence",
                DeploymentFixedCommandApproval.commandReviewInstructions(false), 36_301,
                new int[] {192_934, 168_792, 131_561, 102_678}, batches);
        }
    }

    private static void replay(String name, MirroredInstallerTestFixture fixture, DeploymentNativeHelperPackageImpactTest.Assisted assisted,
                               AssetLendingBuildReviewFixture.Captured command, Map<String, Object> facts, String manifest,
                               String instruction, int observedFacts, int[] ceilings, List<List<Cursor>> reads) throws Exception {
        fixture.model.nativeReply(denied());
        var decision = new DeploymentAiJson(assisted.context).askCommand(instruction, facts, fixture.model.configurationRevision,
            fixture.task, DeploymentReviewDecision::malformed, command.intent(), command.request());
        assertEquals("DENY", decision.path("decision").asText());
        assertEquals(0, assisted.hosts.get());
        var template = fixture.model.requests.getFirst();
        var projected = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, WIRE_LIMIT), command.intent(),
            command.request(), fixture.layout, archive(facts)).orElseThrow();
        String snapshot = JSON.readTree(projected.messages().getLast().text()).path(manifest).path("snapshotSha256").textValue();
        assertNotNull(snapshot);
        var base = new ArrayList<>(projected.messages());
        base.addFirst(template.messages().getFirst());
        int fixed = encode(template, base, WIRE_LIMIT, 0);
        int fixedCalibrated = encode(template, base, WIRE_LIMIT, observedFacts);
        describeFixedEnvelope(name, template, base);
        var complete = run(template, projected, base, snapshot, reads, ceilings, 0, false);
        assertEquals("COMPLETE", complete.coverage());
        var bounded = run(template, projected, base, snapshot, reads, ceilings, observedFacts, true);
        System.out.printf("R86_BUDGET_REPLAY project=%s fixed=%d fixedAtFactsFloor=%d exactFrameBytes=%s "
                + "replay=%s fullCoverage=%s boundedCoverage=%s%n",
            name, fixed, fixedCalibrated, complete.sizes(), bounded.sizes(), complete.coverage(), bounded.coverage());
    }

    private record Cursor(String pointer, int offset, int limit) { }
    private record Run(List<String> sizes, String coverage) { }

    private static Run run(AiRequest template, DeploymentCommandSourceEvidence projected, List<AiMessage> base, String snapshot,
                           List<List<Cursor>> reads, int[] limits, int observedFacts, boolean constrain) throws Exception {
        var messages = new ArrayList<>(base);
        var reader = projected.reader(64);
        reader.offerInitialSource(messages);
        var sizes = new ArrayList<String>();
        for (int index = 0; index < limits.length; index++) {
            int bound = constrain ? limits[index] : WIRE_LIMIT;
            try {
                sizes.add(Integer.toString(encode(template, messages, bound, observedFacts)));
            } catch (ModelRequestBudgetException exceeded) {
                sizes.add(exceeded.encodedBytes() + ">" + bound);
                var problem = new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Local encoder bound",
                    Map.of("maximumRequestBytes", Integer.toString(bound),
                        "encodedRequestBytes", Integer.toString(exceeded.encodedBytes())), Optional.empty());
                if (!reader.repackBeforeSend(messages, problem)) return new Run(sizes, "LOCAL_UNSENT");
                try {
                    sizes.add(Integer.toString(encode(template, messages, bound, observedFacts)));
                } catch (ModelRequestBudgetException rejected) {
                    sizes.add(rejected.encodedBytes() + ">" + bound);
                    return new Run(sizes, "LOCAL_UNSENT");
                }
            }
            if (index == reads.size()) break;
            var calls = new ArrayList<AiToolCall>();
            for (var cursor : reads.get(index)) {
                calls.add(new AiToolCall("recorded-" + index + "-" + calls.size(), "evidence", JSON.writeValueAsString(
                    Map.of("snapshotSha256", snapshot, "pointer", cursor.pointer(), "offset", cursor.offset(), "limit", cursor.limit()))));
            }
            var response = physical(calls);
            var results = reader.read(response);
            assertEquals(calls.size(), results.size());
            // R86 cursors are retained even when a synthetic official-only mirror policy has shorter ranges.
            if (results.stream().anyMatch(AiToolResult::error)) {
                System.out.printf("R86_BUDGET_REPLAY rejectedFixtureReads=%s%n", results.stream().filter(AiToolResult::error)
                    .map(AiToolResult::text).toList());
            }
            reader.continueReview(messages, response, results);
        }
        reader.read(physical(List.of(denied())));
        return new Run(sizes, reader.approvalEvidenceBlock().orElse("COMPLETE"));
    }

    private static AiResponse physical(List<AiToolCall> calls) {
        var usage = new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var attempt = new AiGenerationAttempt(UUID.randomUUID(), 1, "fixture-approval", "OPENAI_RESPONSES", TaskResult.SUCCEEDED,
            "AI_GENERATION_COMPLETE", "NONE", 0L, usage, 1, RESERVE);
        return new AiResponse("", "fixture-approval", "tool_calls", usage, calls,
            Optional.of(new AiContinuation(UUID.randomUUID(), 64)), Optional.empty(), Optional.empty(), List.of(attempt));
    }

    private static AiToolCall denied() {
        return new AiToolCall("offline-denial", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Offline fixture\"}");
    }

    private static int encode(AiRequest template, List<AiMessage> messages, int bound, int observedFacts) throws Exception {
        return encoded(template, messages, bound, observedFacts).length;
    }

    private static byte[] encoded(AiRequest template, List<AiMessage> messages, int bound, int observedFacts) throws Exception {
        var framed = new ArrayList<>(messages);
        var last = framed.getLast();
        int padding = observedFacts - last.text().length() - 24;
        if (padding > 0 && last.text().startsWith("{")) {
            var facts = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(last.text());
            facts.put("replayFixturePadding", "x".repeat(padding));
            framed.set(framed.size() - 1, new AiMessage(AiRole.USER, facts.toString(), List.of()));
        }
        var request = new AiRequest(template.purpose(), framed, template.maxOutputTokens(), template.timeout(),
            template.jsonObjectResponse(), template.tools(), bound, template.requiredToolName(), template.generationRecoveryAttempts(),
            Optional.of("gpt-6-luna"), Optional.of(AiReasoningMode.HIGH));
        return ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES)
            .encode("gpt-6-luna", request, RESERVE, ModelReasoningLevel.HIGH, Map.of());
    }

    private static void describeFixedEnvelope(String project, AiRequest template, List<AiMessage> messages) throws Exception {
        byte[] body = encoded(template, messages, WIRE_LIMIT, 0);
        var items = JSON.readTree(body).path("input");
        assertEquals(messages.size(), items.size());
        int serializedItems = 0;
        for (int index = 0; index < items.size(); index++) {
            String text = items.get(index).at("/content/0/text").textValue();
            assertEquals(messages.get(index).text(), text);
            int size = JSON.writeValueAsBytes(items.get(index)).length;
            serializedItems += size;
            String role = messages.get(index).role().name();
            String component = index == 0 ? "system" : index == items.size() - 1 ? "facts" : "source-frame";
            if (text.startsWith(SOURCE_FRAME))
                component = JSON.readTree(text.lines().skip(1).findFirst().orElseThrow()).path("sourcePointer").asText(component);
            System.out.printf("R86_FIXED_MESSAGE project=%s ordinal=%d role=%s component=%s encodedBytes=%d utf8Bytes=%d sha256=%s%n",
                project, index, role, component, size, text.getBytes(StandardCharsets.UTF_8).length,
                DeploymentEvidencePages.sha256(text));
        }
        var facts = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(messages.getLast().text());
        var keys = new ArrayList<String>();
        facts.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            var field = JSON.createObjectNode();
            field.set(key, facts.get(key));
            int wireBytes = JSON.writeValueAsBytes(field.toString()).length - 4;
            if (wireBytes >= 512) System.out.printf("R86_FIXED_FACT project=%s field=%s quotedBytes=%d%n", project, key, wireBytes);
        }
        var distinctSystemLines = new HashSet<String>();
        long duplicateSystemBytes = messages.getFirst().text().lines().filter(line -> line.length() >= 32)
            .filter(line -> !distinctSystemLines.add(line)).mapToLong(line -> line.getBytes(StandardCharsets.UTF_8).length + 1).sum();
        System.out.printf("R86_FIXED_SYSTEM project=%s exactRepeatedLongLineBytes=%d%n", project, duplicateSystemBytes);
        System.out.printf("R86_FIXED_ENVELOPE project=%s nonMessageBytes=%d%n", project, body.length - serializedItems);
    }

    private static Optional<gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiveReview.Evidence> archive(Map<String, Object> facts) {
        if (facts.get("currentCommandEvidence") instanceof Map<?, ?> current
            && current.get("currentBuildArchiveSource") instanceof gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiveReview.Evidence value)
            return Optional.of(value);
        return Optional.empty();
    }

    private static Path assetSource() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path source = root.resolve("test/multi-language/success-asset-lending");
            if (Files.isRegularFile(source.resolve("backend/AssetLending.csproj"))) return source;
            root = root.getParent();
        }
        throw new AssertionError("Missing original Asset Lending fixture");
    }

    public static void main(String[] arguments) throws Exception {
        var replay = new DeploymentR86BudgetReplayTest();
        replay.scratch = Path.of(arguments[1]).toRealPath();
        switch (arguments[0]) {
            case "asset-lending" -> replay.assetLendingOriginalArchiveAndBindingAtObservedCeilings();
            case "survey-scoring" -> replay.surveyScoringKotlinPreparationAtObservedCeilings();
            default -> throw new IllegalArgumentException(arguments[0]);
        }
    }
}

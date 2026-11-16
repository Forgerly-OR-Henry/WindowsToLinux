package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandSourceEvidence;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.ai.review.MirroredInstallerTestFixture;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentSourceArchiveReviewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    Path temporary;

    @Test
    void originalAssetLendingBackendTextReachesTheIndependentBuildRequestWithItsExactBytes() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            Path source = Files.createDirectory(temporary.resolve("source"));
            Path backend = Files.createDirectory(source.resolve("backend"));
            var paths = new ArrayList<String>();
            try (var files = Files.list(assetBackend())) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    Files.copy(file, backend.resolve(file.getFileName()));
                    paths.add("backend/" + file.getFileName());
                }
            }
            Files.createDirectory(source.resolve("web"));
            Files.writeString(source.resolve("web/build.mjs"), "unrelated_sibling_marker()\n");
            paths.add("web/build.mjs");
            var material = new DeploymentSourceMaterial("Asset Lending",
                new Snapshot(UUID.randomUUID(), "a".repeat(64), source.toRealPath()), paths, List.of());
            var archive = writeArchive(material, fixture.task);
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertEquals("COMPLETE", captured.coverage());
            assertTrue(captured.members().stream().anyMatch(member -> member.path().equals("AssetLending.csproj")));
            try (var input = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive.path())))) {
                for (var member : captured.members()) {
                    assertEquals(member.path(), input.getNextEntry().getName());
                    assertArrayEquals(input.readAllBytes(), member.text().getBytes(StandardCharsets.UTF_8));
                }
                assertNull(input.getNextEntry());
            }
            verifyBuildRequest(fixture, archive, captured);
        }
    }

    @Test
    void protectedAndOpaqueMembersNeverBecomeReviewTextAndSnapshotIdentityIsChecked() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            Path source = Files.createDirectory(temporary.resolve("source"));
            Path backend = Files.createDirectory(source.resolve("backend"));
            Files.writeString(backend.resolve("build.sh"), "printf '复验'\r\n");
            Files.writeString(backend.resolve("configuration.txt"), "password=fixture-placeholder\n");
            Files.writeString(backend.resolve(".env"), "FIXTURE_ONLY=exclude-this-value\n");
            Files.write(backend.resolve("binary.dat"), new byte[]{(byte) 0xFF, 0});
            var material = new DeploymentSourceMaterial("Fixture", new Snapshot(UUID.randomUUID(), "a".repeat(64),
                source.toRealPath()), List.of("backend/build.sh", "backend/configuration.txt", "backend/.env",
                "backend/binary.dat"), List.of());
            var archive = writeArchive(material, fixture.task);
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertEquals("PARTIAL", captured.coverage());
            assertEquals(List.of("SOURCE_POLICY", "NON_TEXT", "PROTECTED_CONTENT"),
                captured.omitted().stream().map(DeploymentSourceArchiveReview.Omitted::reason).toList());
            assertEquals("printf '复验'\r\n", captured.members().getFirst().text());
            assertFalse(JSON.writeValueAsString(captured).contains("fixture-placeholder"));
            assertFalse(JSON.writeValueAsString(captured).contains("exclude-this-value"));
            var impostor = new DeploymentSourceMaterial("Fixture", new Snapshot(UUID.randomUUID(), "a".repeat(64),
                source.toRealPath()), material.includedFiles(), List.of());
            assertThrows(IOException.class, () -> DeploymentSourceArchiveReview.capture(impostor, archive, fixture.task));
        }
    }

    @Test
    void oversizedComponentProvidesCoverageStatusWithoutOpeningEitherSnapshot() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            UUID origin = UUID.randomUUID();
            FileSnapshot metadataOnly = new FileSnapshot() {
                @Override public UUID id() { return origin; }
                @Override public String sha256() { throw new AssertionError("Unexpected source digest re-scan"); }
                @Override public Path directory() { throw new AssertionError("Unexpected snapshot read"); }
                @Override public void close() { }
            };
            var material = new DeploymentSourceMaterial("Large fixture", metadataOnly, List.of("backend/asset.dat"), List.of());
            var archive = new DeploymentSourceArchive(metadataOnly, "c".repeat(64), 900, 65537, origin,
                "a".repeat(64), "backend");
            var review = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertEquals("OUTSIDE_INLINE_REVIEW_BOUNDS", review.coverage());
            assertEquals(1, review.memberCount());
            assertTrue(review.members().isEmpty());
        }
    }

    private DeploymentSourceArchive writeArchive(DeploymentSourceMaterial material, TaskContext task) throws Exception {
        Path output = Files.createDirectory(temporary.resolve("archive"));
        Path file = output.resolve("source.tar.gz");
        long total;
        try (var stream = Files.newOutputStream(file)) {
            total = DeploymentSourceTar.write(material, "backend", stream, task);
        }
        byte[] bytes = Files.readAllBytes(file);
        return new DeploymentSourceArchive(new Snapshot(UUID.randomUUID(), "b".repeat(64), output), digest(bytes),
            bytes.length, total, material.snapshot().id(), material.snapshot().sha256(), "backend");
    }

    private static void verifyBuildRequest(MirroredInstallerTestFixture fixture, DeploymentSourceArchive archive,
                                           DeploymentSourceArchiveReview.Evidence captured) throws Exception {
        String body = "set -eu\ntest \"$(sha256sum \"$archive\" | awk '{print $1}')\" = '"
            + archive.sha256() + "'\ndotnet publish --no-restore\n";
        String input = DeploymentMirrorTasks.withMaterials(DeploymentMirrorRuntime.wrapBuild(
            body, DeploymentMirrorTasks.policy(fixture.task), 120), fixture.task.id());
        var workspace = new DeploymentRemoteWorkspace("asset-lending", "a".repeat(64), fixture.layout);
        String collector = DeploymentBuildOutputProtocol.script(workspace, 120, 16, 512, 8192, "ordinary",
            MirroredInstallerTestFixture.sha(input), "-", List.of(List.of("BUILD_OK")));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(collector, fixture.layout),
            input.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(135), 65536)) {
            var intent = RemoteActions.command(fixture.server, fixture.model.configurationRevision, request)
                .withSemantics(new ActionSemantics("deployment.build.run.v1",
                    Set.of(new ActionTarget(fixture.server.id(), "deployment-build", workspace.candidateRoot()))));
            var facts = new LinkedHashMap<String, Object>(new DeploymentFixedCommandProtocol(fixture.context)
                .inspect(intent, request, fixture.layout, fixture.task));
            var bound = Map.of("archiveSha256", archive.sha256(), "sourceManifestSha256", archive.sourceManifestSha256(),
                "relativeRoot", archive.relativeRoot(), "fileCount", captured.memberCount());
            var reviewer = new DeploymentAiJson(fixture.context);
            facts.put("currentCommandEvidence", Map.of("currentBuildArchive", Map.of("archive", bound)));
            var withoutContents = deny(fixture, reviewer, facts, intent, request);
            facts.put("currentCommandEvidence", Map.of("currentBuildArchive", Map.of("archive", bound),
                "currentBuildArchiveSource", captured));
            var sentRequest = deny(fixture, reviewer, facts, intent, request);
            byte[] wire = encode(sentRequest);
            int observedReplay = 155_816 + wire.length - encode(withoutContents).length;
            assertTrue(observedReplay < 344_125, "Observed R38 build request ceiling includes the additional source");
            var sent = sentRequest.messages();
            var projected = JSON.readTree(sent.getLast().text());
            assertTrue(projected.at("/currentCommandEvidence/currentBuildArchiveSource").isMissingNode());
            var members = projected.at("/buildReviewEvidence/projectArchive/members");
            assertEquals(captured.memberCount(), members.size());
            var texts = retainedTexts(JSON.readTree(wire).path("input"),
                projected.at("/buildReviewEvidence/snapshotSha256").asText());
            for (int i = 0; i < members.size(); i++) {
                var member = members.get(i);
                String pointer = member.path("sourcePointer").asText();
                assertEquals(captured.members().get(i).text(), texts.getOrDefault(pointer, ""));
                assertEquals(captured.members().get(i).sha256(), member.path("sha256").asText());
            }
            assertFalse(new String(wire, StandardCharsets.UTF_8).contains("unrelated_sibling_marker"));
            verifyBudgetAndOriginalAliases(fixture, intent, request, facts, sentRequest, captured);
            verifyArchiveHistory(fixture, intent, request, facts, bound, captured);
            System.out.printf("R38_ASSET_BUILD_SOURCE_REPLAY files=%d fixture=%d observedWithAdditional=%d cap=344125%n",
                members.size(), wire.length, observedReplay);
        }
    }

    private static void verifyArchiveHistory(MirroredInstallerTestFixture fixture, ActionIntent intent,
            CommandRequest request, Map<String, Object> facts, Map<String, ?> selected,
            DeploymentSourceArchiveReview.Evidence captured) throws Exception {
        var other = Map.of("archiveSha256", "e".repeat(64), "relativeRoot", "web", "marker", "sibling-archive-proof");
        var history = Map.of("archives", List.of(selected, other), "meaning", "Original source archive observations");
        facts.put("currentCommandEvidence", Map.of("currentBuildArchive", Map.of("archive", selected),
            "currentBuildArchiveSource", captured, "sourceArchiveObservations", history));
        var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576),
            intent, request, fixture.layout).orElseThrow();
        String projectedText = evidence.messages().getLast().text();
        var projected = JSON.readTree(projectedText);
        var descriptor = projected.at("/buildReviewEvidence/sourceArchiveHistory");
        assertEquals("/currentCommandEvidence/currentBuildArchive/archive", descriptor.path("selectedArchivePointer").asText());
        assertEquals(JSON.valueToTree(selected), projected.at(descriptor.path("selectedArchivePointer").asText()));
        assertFalse(projectedText.contains("sibling-archive-proof"));
        String snapshot = projected.at("/buildReviewEvidence/snapshotSha256").asText();
        var page = evidence.reader(1).read(reply(evidenceCall("history", snapshot,
            descriptor.path("sourcePointer").asText(), 0, 8000))).getFirst();
        assertFalse(page.error());
        String originalHistory = page.text().split("\n", 3)[2];
        assertEquals(JSON.valueToTree(history), JSON.readTree(originalHistory));
        assertEquals(descriptor.path("sha256").asText(), MirroredInstallerTestFixture.sha(originalHistory));
    }

    private static void verifyBudgetAndOriginalAliases(MirroredInstallerTestFixture fixture, ActionIntent intent,
            CommandRequest command, Map<String, Object> facts, AiRequest review,
            DeploymentSourceArchiveReview.Evidence captured) throws Exception {
        var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576),
            intent, command, fixture.layout).orElseThrow();
        var messages = new ArrayList<>(List.of(review.messages().getFirst()));
        messages.addAll(evidence.messages());
        int base = encode(reviewWithMessages(review, messages)).length;
        var reader = evidence.reader(2);
        reader.offerInitialSource(messages);
        int complete = encode(reviewWithMessages(review, messages)).length;
        var problem = new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Local encoder bound",
            Map.of("maximumRequestBytes", Integer.toString(base), "encodedRequestBytes", Integer.toString(complete)), Optional.empty());
        assertTrue(reader.repackBeforeSend(messages, problem));
        assertEquals(base, encode(reviewWithMessages(review, messages)).length);
        reader.read(reply(new AiToolCall("approval", "submit_result",
            "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Fixture\"}")));
        assertEquals(Optional.of("REQUIRED_SOURCE_MISSING"), reader.approvalEvidenceBlock());

        var manifest = JSON.readTree(evidence.messages().getLast().text()).path("buildReviewEvidence");
        String snapshot = manifest.path("snapshotSha256").asText();
        reader = evidence.reader(2);
        reader.offerInitialSource(messages);
        assertTrue(captured.members().getFirst().text().length() >= 100);
        var memberRead = reader.read(reply(evidenceCall("member", snapshot,
            "/currentCommandEvidence/currentBuildArchiveSource/members/0/text", 0, 100))).getFirst();
        assertFalse(memberRead.error());
        var memberHeader = JSON.readTree(memberRead.text().split("\n", 3)[1]);
        assertEquals(captured.members().getFirst().sha256(), memberHeader.at("/value/sha256").asText());
        assertTrue(memberHeader.at("/value/newRanges").isArray());
        assertEquals(0, memberHeader.at("/value/newRanges").size());

        var literal = manifest.path("bootstrapShellLiteral");
        assertEquals("JSON_STRING", literal.path("encoding").asText());
        var target = literal.path("target");
        String original = DeploymentMirrorRuntime.runtimeSource().substring(target.path("offset").asInt(), target.path("next").asInt());
        assertEquals(target.path("sha256").asText(), MirroredInstallerTestFixture.sha(original));
        var literalRead = reader.read(reply(evidenceCall("literal", snapshot, target.path("pointer").asText(),
            target.path("offset").asInt(), 80))).getFirst();
        assertFalse(literalRead.error());
        assertEquals(original.substring(0, 80), literalRead.text().split("\n", 3)[2]);
    }

    private static AiRequest reviewWithMessages(AiRequest review, List<AiMessage> messages) {
        return new AiRequest(review.purpose(), messages, review.maxOutputTokens(), review.timeout(), review.jsonObjectResponse(),
            review.tools(), review.maxRequestBytes(), review.requiredToolName(), review.generationRecoveryAttempts());
    }

    private static AiToolCall evidenceCall(String id, String snapshot, String pointer, int offset, int limit) throws Exception {
        return new AiToolCall(id, "evidence", JSON.writeValueAsString(
            Map.of("snapshotSha256", snapshot, "pointer", pointer, "offset", offset, "limit", limit)));
    }

    private static AiResponse reply(AiToolCall call) {
        var usage = new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new AiResponse("", "fixture-model", "tool_calls", usage, List.of(call), Optional.empty());
    }

    private static Map<String, String> retainedTexts(JsonNode input, String snapshot) throws Exception {
        String banner = "WTL-COMMAND-RETAINED-SOURCE-1\n";
        var frames = new ArrayList<String>();
        for (var message : input) {
            String text = message.at("/content/0/text").asText();
            if (text.startsWith(banner)) frames.add(text);
        }
        assertEquals(1, frames.size());
        String frame = frames.getFirst();
        int headerEnd = frame.indexOf('\n', banner.length());
        var header = JSON.readTree(frame.substring(banner.length(), headerEnd));
        assertEquals(snapshot, header.path("snapshotSha256").asText());
        assertTrue(header.path("pendingRequired").isMissingNode());
        int at = headerEnd + 1;
        var sources = new LinkedHashMap<String, String>();
        for (var range : header.path("ranges")) {
            int next = at + range.path("next").asInt() - range.path("offset").asInt();
            String pointer = range.path("pointer").asText();
            if (pointer.startsWith("/buildReviewEvidence/projectArchive/members/")) {
                assertEquals(0, range.path("offset").asInt());
                assertNull(sources.put(pointer, frame.substring(at, next)));
            }
            at = next;
        }
        assertEquals(frame.length(), at);
        return sources;
    }

    private static AiRequest deny(MirroredInstallerTestFixture fixture, DeploymentAiJson reviewer, Map<String, Object> facts,
                                  ActionIntent intent, CommandRequest request)
        throws Exception {
        fixture.model.nativeReply(new AiToolCall("fixture-verdict", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Fixture denial\"}"));
        var result = reviewer.askCommand("Review the original test input", facts, fixture.model.configurationRevision,
            fixture.task, DeploymentReviewDecision::malformed, intent, request);
        assertEquals("DENY", result.path("decision").asText());
        return fixture.model.requests.getLast();
    }

    private static byte[] encode(AiRequest request) {
        return ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES)
            .encode("gpt-6-luna", request, 65536, ModelReasoningLevel.HIGH, Map.of());
    }

    private static Path assetBackend() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path backend = current.resolve("test/multi-language/success-asset-lending/backend");
            if (Files.isRegularFile(backend.resolve("AssetLending.csproj")))
                return backend;
        }
        throw new IllegalStateException("Asset Lending backend fixture missing");
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Snapshot(UUID id, String sha256, Path directory) implements FileSnapshot {
        @Override public void close() { }
    }
}

package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelRequestBudgetException;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.ai.review.MirroredInstallerTestFixture;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DirectoryDiffBuildReviewFixture;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentDirectoryDiffBuildReviewReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RAW_CAP = 1_048_576;
    private static final int HISTORICAL_CEILING = 740_893;
    private static final int R90_CEILING = 967_329;
    private static final int HISTORICAL_FACTS_CHARACTERS = 52_316;
    private static final int HISTORICAL_FIRST_WIRE_BYTES = 153_716;
    private static final String ARCHIVE_SHA256 = "7a7a7148838dc23be934c81d8b7caa6416bf8ad4ffe8c7b4a71877cff55a72a2";
    private static final String HEADER = "native/vendor/nlohmann/json.hpp";
    private static final String RETAINED = "WTL-COMMAND-RETAINED-SOURCE-1\n";
    @TempDir Path temporary;

    @Test
    void originalDirectoryDiffArchiveRequiresMoreThanTheHistoricalPhysicalCeiling() throws Exception {
        try (var fixture = DirectoryDiffBuildReviewFixture.mirrorFixture()) {
            var material = stageOriginal();
            Path output = Files.createDirectory(temporary.resolve("archive"));
            Path tar = output.resolve("source.tar.gz");
            long uncompressed;
            try (var stream = Files.newOutputStream(tar)) {
                uncompressed = DeploymentSourceTar.write(material, "", stream, fixture.task);
            }
            var archive = new DeploymentSourceArchive(new Snapshot(UUID.randomUUID(), "b".repeat(64), output),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tar))),
                Files.size(tar), uncompressed,
                material.snapshot().id(), material.snapshot().sha256(), "");
            assertEquals(ARCHIVE_SHA256, archive.sha256());
            assertEquals(158_536, archive.byteCount());
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertFalse(captured.requiredUnavailable());
            try (var build = DirectoryDiffBuildReviewFixture.render(fixture, material, archive, captured)) {
                var full = new EncodingReview(fixture, RAW_CAP);
                assertEquals("APPROVE", approve(full, fixture, build).path("decision").asText());
                assertTrue(full.refused.isEmpty());
                assertEquals(1, full.sent.size());
                byte[] encoded = full.sent.getFirst();
                var input = JSON.readTree(encoded).path("input");
                var evidence = JSON.readTree(input.get(input.size() - 1).at("/content/0/text").asText()).path("buildReviewEvidence");
                assertEquals(ARCHIVE_SHA256, evidence.at("/projectArchive/archiveSha256").asText());
                var frame = retainedSource(input);
                assertFalse(frame.metadata().has("pendingRequired"));
                int headerJsonBytes = verifyArchive(frame, evidence, captured);
                assertTrue(JSON.writeValueAsBytes(captured.pagedBodies().get(HEADER)).length > HISTORICAL_CEILING);
                assertTrue(encoded.length > HISTORICAL_CEILING, "Measure the whole current native request, not a component size");
                assertEquals(build.buildBodySha256(), evidence.at("/buildBody/sha256").asText());
                assertEquals(build.buildBodyBytes(), evidence.at("/buildBody/utf8Bytes").asInt());
                assertEquals(368, build.bindingBytes());
                assertEquals("4f8b2109a5dfcc9eb65e62b265142142e445de17ea72cc100b645d3ebe03c6bc", build.bindingSha256());

                int reconstructedFacts = full.requests.getFirst().messages().getLast().text().length();
                int unobservedFacts = Math.max(0, HISTORICAL_FACTS_CHARACTERS - reconstructedFacts);
                var limited = new EncodingReview(fixture, HISTORICAL_CEILING);
                assertThrows(ApiException.class, () -> approve(limited, fixture, build));
                assertEquals(List.of(encoded.length), limited.refused);
                assertTrue(limited.sent.isEmpty(), "Complete source over budget must not send a missing-header fallback");
                fixture.model.replies.clear();
                assertTrue(full.requests.stream().allMatch(request -> request.maxRequestBytes() <= RAW_CAP));
                // Historical sizes remain reference measurements; the current complete request is measured independently.
                int completionBytes = encoded.length - HISTORICAL_FIRST_WIRE_BYTES;
                int factsDeficitMaxBytes = 6 * unobservedFacts;
                int r90MinimumSaving = 991_990 - R90_CEILING;
                assertTrue(838_020 - headerJsonBytes >= r90MinimumSaving, () -> "Lossless header wire bytes: " + headerJsonBytes);
                var r90 = new EncodingReview(fixture, R90_CEILING);
                if (encoded.length <= R90_CEILING) {
                    assertEquals("APPROVE", approve(r90, fixture, build).path("decision").asText());
                    assertTrue(r90.refused.isEmpty());
                    assertEquals(1, r90.sent.size());
                } else {
                    assertThrows(ApiException.class, () -> approve(r90, fixture, build));
                    assertTrue(r90.sent.isEmpty());
                    assertEquals(List.of(encoded.length), r90.refused);
                    fixture.model.replies.clear();
                }
                var covered = new EncodingReview(fixture, RAW_CAP);
                assertEquals("APPROVE", approve(covered, fixture, build).path("decision").asText());
                assertEquals(List.of(), covered.refused);
                assertEquals(1, covered.sent.size());
                assertEquals(encoded.length, covered.sent.getFirst().length);
                System.out.printf("R89_DIRECTORY_DIFF_REPLAY archive=%s bytes=%d binding=%s bindingBytes=%d "
                        + "buildBody=%s buildBodyBytes=%d headerJsonBytes=%d completeWireBytes=%d rawCap=%d historicalCeiling=%d "
                        + "historicalFirstWireBytes=%d historicalFactsCharacters=%d reconstructedFactsCharacters=%d "
                        + "factsDeficitMaxBytes=%d completionWireBytes=%d physicalWireBudget=%d cappedDispatches=%d%n",
                    archive.sha256(), archive.byteCount(), build.bindingSha256(), build.bindingBytes(), build.buildBodySha256(),
                    build.buildBodyBytes(), headerJsonBytes, encoded.length, RAW_CAP, HISTORICAL_CEILING, HISTORICAL_FIRST_WIRE_BYTES,
                    HISTORICAL_FACTS_CHARACTERS, reconstructedFacts, factsDeficitMaxBytes, completionBytes, RAW_CAP,
                    limited.sent.size());
            }
        }
    }

    private DeploymentSourceMaterial stageOriginal() throws Exception {
        Path original = Path.of("").toAbsolutePath();
        while (original != null && !Files.isRegularFile(original.resolve("test/multi-language/success-directory-diff").resolve(HEADER)))
            original = original.getParent();
        assertNotNull(original, "Directory Diff source must be in the workspace");
        original = original.resolve("test/multi-language/success-directory-diff");
        Path staged = Files.createDirectory(temporary.resolve("directory-diff")).toRealPath();
        var paths = new ArrayList<String>();
        try (var files = Files.walk(original)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = original.relativize(file);
                Path target = staged.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
                paths.add(relative.toString().replace('\\', '/'));
            }
        }
        Path declaration = staged.resolve("windowstolinux-application.properties");
        String source = Files.readString(declaration).replaceAll("(?m)^verification\\.arg\\.[0-9]+=.*\\R?", "")
            .replaceAll("(?m)^expectedOutput=.*\\R?", "");
        Files.writeString(declaration, source + """
            verification.arg.0=--format
            verification.arg.1=json
            verification.arg.2=snapshot
            verification.arg.3=--root
            verification.arg.4=samples/baseline
            verification.arg.5=--name
            verification.arg.6=live-baseline
            expectedOutput="status": "complete"
            """);
        return new DeploymentSourceMaterial("Directory Diff", new Snapshot(UUID.randomUUID(), "a".repeat(64), staged),
            paths, List.of());
    }

    private static int verifyArchive(Frame frame, JsonNode evidence, DeploymentSourceArchiveReview.Evidence captured) throws Exception {
        var byPath = new HashMap<String, String>();
        for (var member : captured.members()) {
            String original = member.text() != null ? member.text() : captured.pagedBodies().get(member.path());
            assertNotNull(original, member.path());
            assertNull(byPath.put(member.path(), original));
        }
        var seen = new HashSet<String>();
        var manifest = evidence.path("projectArchive");
        var blocks = frame.metadata().path("repeatedOriginalBlocks");
        String packed = blocks.path("pointer").asText();
        int displayBytes = 0;
        for (var member : manifest.path("members")) {
            String pointer = member.path("sourcePointer").asText();
            String path = member.path("path").asText();
            String display = frame.texts().get(pointer);
            assertNotNull(display, path);
            assertTrue(seen.add(path), path);
            String expected = byPath.get(path);
            assertNotNull(expected, path);
            if (pointer.equals(packed)) {
                assertEquals(HEADER, path);
                assertEquals(member.path("sha256").asText(), MirroredInstallerTestFixture.sha(reconstruct(display)));
                displayBytes = JSON.writeValueAsBytes(display).length;
                assertEquals(expected.length(), blocks.path("originalCharacters").asInt());
                assertEquals(display.length(), blocks.path("displayCharacters").asInt());
            } else assertEquals(expected, display, path);
            assertEquals(MirroredInstallerTestFixture.sha(expected), member.path("sha256").asText());
        }
        assertEquals(byPath.keySet(), seen);
        assertEquals(frame.metadata().path("snapshotSha256").asText(), evidence.path("snapshotSha256").asText());
        return displayBytes;
    }

    private static String reconstruct(String display) throws Exception {
        return gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentSourceFrameDecoder.expand(display);
    }

    static String decodeLeadingSpaces(String line) {
        if (!line.startsWith("@>")) return line;
        int separator = line.indexOf(' ');
        assertTrue(separator > 2 && separator < 6);
        int count = Integer.parseInt(line.substring(2, separator));
        assertTrue(count >= 5 && count <= 999);
        return " ".repeat(count) + line.substring(separator + 1);
    }

    private record Frame(JsonNode metadata, Map<String, String> texts) { }

    private static Frame retainedSource(JsonNode input) throws Exception {
        Frame result = null;
        for (var message : input) {
            String text = message.at("/content/0/text").asText();
            if (!text.startsWith(RETAINED)) continue;
            assertNull(result, "Expected only the current retained frame");
            int end = text.indexOf('\n', RETAINED.length());
            var metadata = JSON.readTree(text.substring(RETAINED.length(), end));
            int position = end + 1;
            var sources = new HashMap<String, String>();
            var repeated = metadata.path("repeatedOriginalBlocks");
            for (var range : metadata.path("ranges")) {
                String pointer = range.path("pointer").asText();
                int offset = range.path("offset").asInt();
                int length = pointer.equals(repeated.path("pointer").asText()) ? repeated.path("displayCharacters").asInt()
                    : range.path("next").asInt() - offset;
                if (pointer.startsWith("/buildReviewEvidence/projectArchive/members/")) {
                    assertEquals(0, offset);
                    assertNull(sources.put(pointer, text.substring(position, position + length)));
                }
                position += length;
            }
            assertEquals(text.length(), position);
            result = new Frame(metadata, sources);
        }
        assertNotNull(result, "Retained source frame missing");
        return result;
    }

    private static JsonNode approve(EncodingReview review, MirroredInstallerTestFixture fixture,
                                    DirectoryDiffBuildReviewFixture.Rendered build) throws Exception {
        fixture.model.nativeReply(new AiToolCall("fixture-verdict", "submit_result",
            "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Offline replay\"}"));
        return review.reviewer.askCommand(DeploymentAssistedScope.standardBuildReviewInstructions(), build.facts(),
            fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, build.intent(), build.request());
    }

    private static final class EncodingReview {
        private final List<AiRequest> requests = new ArrayList<>();
        private final List<byte[]> sent = new ArrayList<>();
        private final List<Integer> refused = new ArrayList<>();
        private final DeploymentAiJson reviewer;

        private EncodingReview(MirroredInstallerTestFixture fixture, int ceiling) {
            ModContext original = fixture.context;
            var loader = DeploymentDirectoryDiffBuildReviewReplayTest.class.getClassLoader();
            var ai = Proxy.newProxyInstance(loader, new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                if (method.getName().equals("call")) {
                    var request = (AiRequest) args[0];
                    requests.add(request);
                    int bound = Math.min(ceiling, request.maxRequestBytes());
                    var bounded = new AiRequest(request.purpose(), request.messages(), request.maxOutputTokens(), request.timeout(),
                        request.jsonObjectResponse(), request.tools(), bound, request.requiredToolName(), request.generationRecoveryAttempts(),
                        request.modelId(), request.reasoningOverride());
                    try {
                        sent.add(ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES)
                            .encode("gpt-6-luna", bounded, 65_536, ModelReasoningLevel.HIGH, Map.of()));
                    } catch (ModelRequestBudgetException failure) {
                        refused.add(failure.encodedBytes());
                        throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Offline server bound",
                            Map.of("maximumRequestBytes", Integer.toString(bound), "encodedRequestBytes",
                                Integer.toString(failure.encodedBytes())), Optional.empty()));
                    }
                }
                return invoke(method, original.ai(), args);
            });
            var context = (ModContext) Proxy.newProxyInstance(loader, new Class<?>[]{ModContext.class},
                (proxy, method, args) -> method.getName().equals("ai") ? ai : invoke(method, original, args));
            reviewer = new DeploymentAiJson(context);
        }

        private static Object invoke(java.lang.reflect.Method method, Object receiver, Object[] args) throws Throwable {
            try {
                return method.invoke(receiver, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private record Snapshot(UUID id, String sha256, Path directory) implements FileSnapshot {
        @Override public void close() { }
    }
}

package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelRequestBudgetException;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiContinuation;
import gold.debug.windowstolinux.appapi.ai.model.AiGenerationAttempt;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandSourceEvidence;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandProtocol;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.ai.review.MirroredInstallerTestFixture;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.LogAnalyzerBuildReviewFixture;
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
    private static final String LOG_ANALYZER_HEADER = "native/vendor/nlohmann/json.hpp";
    private static final String LOG_ANALYZER_HEADER_SHA256 = "aaf127c04cb31c406e5b04a63f1ae89369fccde6d8fa7cdda1ed4f32dfc5de63";
    private static final String LOG_ANALYZER_MARKER = "SPDX-FileCopyrightText: 2013 - 2025 Niels Lohmann";
    private static final int REVIEW_CEILING = 1_048_576;
    private static final int ORIGINAL_PAGE_CEILING = 223_943;
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
    void originalLogAnalyzerKeepsItsArchiveAndTracesEverySafeProjectTextPastTheOldAggregateBound() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            Path staged = Files.createDirectory(temporary.resolve("log-analyzer")).toRealPath();
            Path original = fixtureRoot("success-log-analyzer", LOG_ANALYZER_HEADER);
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
            String originalDeclaration = Files.readString(declaration).replaceAll("(?m)^verification\\.arg\\.[0-9]+=.*\\R?", "")
                .replaceAll("(?m)^expectedOutput=.*\\R?", "");
            Files.writeString(declaration, originalDeclaration + """
                verification.arg.0=--input
                verification.arg.1=samples/events.log
                verification.arg.2=--format
                verification.arg.3=json
                expectedOutput="matched": 6
                """);
            var material = new DeploymentSourceMaterial("Log Analyzer", new Snapshot(UUID.randomUUID(), "a".repeat(64), staged),
                paths, List.of());
            var archive = writeArchive(material, "", fixture.task);
            assertEquals("6330280598c909dd9a39202b49506c59898000fa14681c5e1e197e1f38053c75", archive.sha256());
            assertEquals(158_250, archive.byteCount());
            assertEquals(995_661, archive.uncompressedByteCount());
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertEquals(25, captured.memberCount());
            assertEquals(24, captured.members().size());
            assertEquals(23, captured.members().stream().filter(item -> item.text() != null).count());
            assertEquals("PARTIAL", captured.coverage());
            assertEquals(List.of("samples/events.log"),
                captured.omitted().stream().map(DeploymentSourceArchiveReview.Omitted::path).toList());
            assertEquals("SOURCE_POLICY", captured.omitted().getFirst().reason());
            assertFalse(captured.requiredUnavailable());
            String header = Files.readString(original.resolve(LOG_ANALYZER_HEADER));
            assertEquals(953_435, header.length());
            assertEquals(Map.of(LOG_ANALYZER_HEADER, header), captured.pagedBodies());
            try (var input = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive.path())))) {
                for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                    byte[] originalBytes = input.readAllBytes();
                    String entryName = entry.getName();
                    if (entryName.equals("samples/events.log")) continue;
                    var member = captured.members().stream().filter(item -> item.path().equals(entryName)).findFirst().orElseThrow();
                    assertEquals(digest(originalBytes), member.sha256());
                    if (entryName.equals(LOG_ANALYZER_HEADER)) {
                        assertNull(member.text());
                        assertEquals(953_436, member.bytes());
                        assertEquals(header.length(), member.characters());
                        assertEquals(LOG_ANALYZER_HEADER_SHA256, member.sha256());
                        assertArrayEquals(originalBytes, header.getBytes(StandardCharsets.UTF_8));
                    } else {
                        assertArrayEquals(originalBytes, member.text().getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            verifyLogAnalyzerBuildReview(fixture, archive, captured, header);
            try (var renderedFixture = LogAnalyzerBuildReviewFixture.mirrorFixture()) {
                verifyRenderedLogAnalyzerBuildReview(renderedFixture, material, archive, captured, header);
            }
        }
    }

    @Test
    void changedHeaderAndUnrelatedPathKeepCredentialContentProtected() throws Exception {
        Path root = fixtureRoot("success-log-analyzer", LOG_ANALYZER_HEADER);
        byte[] published = Files.readAllBytes(root.resolve(LOG_ANALYZER_HEADER));
        assertEquals(LOG_ANALYZER_HEADER_SHA256, digest(published));
        assertTrue(new String(published, StandardCharsets.UTF_8).lines().anyMatch(DeploymentSourceBrowser::protectedText));
        byte[] changed = published.clone();
        String fixtureToken = "// token=fixture-only-r72";
        byte[] tokenBytes = fixtureToken.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(tokenBytes, 0, changed, 0, tokenBytes.length);
        assertEquals(953_436, changed.length);
        assertNotEquals(LOG_ANALYZER_HEADER_SHA256, digest(changed));
        assertTrue(DeploymentSourceBrowser.protectedText(fixtureToken));

        try (var fixture = new MirroredInstallerTestFixture()) {
            var rejected = Map.of(LOG_ANALYZER_HEADER, changed, "native/notvendor/nlohmann/json.hpp", published);
            for (var entry : rejected.entrySet()) {
                String path = entry.getKey();
                Path source = Files.createTempDirectory(temporary, "header-").toRealPath();
                Path member = source.resolve(path);
                Files.createDirectories(member.getParent());
                Files.write(member, entry.getValue());
                var material = new DeploymentSourceMaterial("Header fixture", new Snapshot(UUID.randomUUID(), "a".repeat(64), source),
                    List.of(path), List.of());
                var archive = writeArchive(material, "", fixture.task);
                var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
                assertEquals(List.of(new DeploymentSourceArchiveReview.Omitted(path, 953_436, "PROTECTED_CONTENT")), captured.omitted());
                assertTrue(captured.requiredUnavailable());
                assertTrue(captured.members().isEmpty());
                assertTrue(captured.pagedBodies().isEmpty());
                assertFalse(JSON.writeValueAsString(captured).contains("fixture-only-r72"));
            }
        }
    }

    @Test
    void pagedLargeMemberUsesExactOriginalDigestAndUtf16RangesWithoutEnteringFactsOrApprovingMissingSource() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            Path source = Files.createDirectory(temporary.resolve("source")).toRealPath();
            String introduction = "x".repeat(7999) + "\uD83E\uDDEA\n";
            String row = "r65-original-paged-header-row\n";
            int remaining = 953_436 - introduction.getBytes(StandardCharsets.UTF_8).length;
            String original = introduction + row.repeat(remaining / row.length()) + "z".repeat(remaining % row.length());
            Files.writeString(source.resolve("000-header.hpp"), original);
            Files.writeString(source.resolve("main.cpp"), "int main() { return 0; }\n");
            var material = new DeploymentSourceMaterial("Large text", new Snapshot(UUID.randomUUID(), "a".repeat(64), source),
                List.of("000-header.hpp", "main.cpp"), List.of());
            var archive = writeArchive(material, "", fixture.task);
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertEquals("COMPLETE", captured.coverage());
            assertEquals(original, captured.pagedBodies().get("000-header.hpp"));
            assertNull(captured.members().getFirst().text());
            assertEquals(953_436, captured.members().getFirst().bytes());
            assertFalse(JSON.writeValueAsString(captured).contains(row.strip()));
            try (var build = buildReview(fixture, archive, captured)) {
                String facts = DeploymentAiJson.facts(build.facts(), 1048576);
                assertFalse(facts.contains(row.strip()));
                assertThrows(IOException.class,
                    () -> DeploymentCommandSourceEvidence.project(facts, build.intent(), build.request(), fixture.layout));
                var evidence = DeploymentCommandSourceEvidence.project(facts, build.intent(), build.request(), fixture.layout,
                    Optional.of(captured)).orElseThrow();
                var manifest = JSON.readTree(evidence.messages().getLast().text()).path("buildReviewEvidence");
                String snapshot = manifest.path("snapshotSha256").asText();
                var descriptor = manifest.at("/projectArchive/members/0");
                String pointer = descriptor.path("sourcePointer").asText();
                String sha = digest(original.getBytes(StandardCharsets.UTF_8));
                assertEquals(sha, descriptor.path("sha256").asText());
                assertEquals(original.length(), descriptor.path("characters").asInt());
                assertTrue(descriptor.path("initiallyPaged").asBoolean());
                var reader = evidence.reader(140);
                assertTrue(reader.read(reply(evidenceCall("stale", "0".repeat(64), pointer, 0, 8000))).getFirst().error());
                assertTrue(reader.read(reply(evidenceCall("split", snapshot, pointer, 8000, 8000))).getFirst().error());

                var rebuilt = new StringBuilder();
                int cursor = 0, pages = 0;
                while (cursor < original.length()) {
                    String readPointer = pages == 1 ? "/currentCommandEvidence/currentBuildArchiveSource/members/0/text" : pointer;
                    var result = reader.read(reply(evidenceCall("large-" + pages, snapshot, readPointer, cursor, 8000))).getFirst();
                    assertFalse(result.error(), result::text);
                    var parts = result.text().split("\n", 3);
                    var page = JSON.readTree(parts[1]).path("value");
                    assertEquals(sha, page.path("sha256").asText());
                    assertEquals(cursor, page.path("offset").asInt());
                    int next = page.path("next").asInt();
                    if (pages == 0) assertEquals(7999, next);
                    if (pages == 1) assertTrue(parts[2].startsWith("\uD83E\uDDEA"));
                    assertEquals(original.substring(cursor, next), parts[2]);
                    rebuilt.append(parts[2]);
                    cursor = next;
                    pages++;
                }
                assertEquals(sha, digest(rebuilt.toString().getBytes(StandardCharsets.UTF_8)));

                fixture.model.nativeReply(verdict("APPROVE"));
                assertThrows(DeploymentCommandReviewModel.Failure.class, () -> new DeploymentAiJson(fixture.context)
                    .askCommand("Review this original archive", build.facts(), fixture.model.configurationRevision,
                        fixture.task, DeploymentReviewDecision::malformed, build.intent(), build.request()));
                byte[] first = encode(fixture.model.requests.getLast());
                assertFalse(new String(first, StandardCharsets.UTF_8).contains(row.strip()));
                assertTrue(first.length < 223_943);
                var firstRequest = fixture.model.requests.getLast();
                var forced = new ArrayList<>(firstRequest.messages());
                forced.add(new AiMessage(gold.debug.windowstolinux.appapi.ai.model.AiRole.USER, original, List.of()));
                var historical = new AiRequest(firstRequest.purpose(), forced, firstRequest.maxOutputTokens(), firstRequest.timeout(),
                    firstRequest.jsonObjectResponse(), firstRequest.tools(), 223_943, firstRequest.requiredToolName(),
                    firstRequest.generationRecoveryAttempts());
                var full = assertThrows(ModelRequestBudgetException.class, () -> encode(historical));
                assertTrue(full.encodedBytes() > 1_048_576, () -> "Fully exposed native request bytes: " + full.encodedBytes());

                String nativeSnapshot = JSON.readTree(fixture.model.requests.getLast().messages().getLast().text())
                    .at("/buildReviewEvidence/snapshotSha256").asText();
                var firstPage = responses(evidenceCall("r65-read-part", nativeSnapshot, pointer, 7999, 8000));
                fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(firstPage), Optional.empty()));
                fixture.model.nativeReply(verdict("DENY"));
                var decision = new DeploymentAiJson(fixture.context).askCommand("Review this original archive", build.facts(),
                    fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, build.intent(), build.request());
                assertEquals("DENY", decision.path("decision").asText());
                byte[] continued = encode(fixture.model.requests.getLast());
                assertTrue(continued.length < 223_943);
                String wire = new String(continued, StandardCharsets.UTF_8);
                assertTrue(wire.contains(row.strip()));
                assertTrue(wire.contains("pendingRequired"));
                assertFalse(wire.contains("z".repeat(Math.max(32, remaining % row.length()))));
                System.out.printf("R65_PAGED_ARCHIVE bytes=953436 chars=%d pages=%d first=%d continued=%d forcedNativeFull=%d ceiling=223943 sha=%s%n",
                    original.length(), pages, first.length, continued.length, full.encodedBytes(), sha);
            }
        }
    }

    @Test
    void oversizedMemberPreservesSmallerTextAndFreshApprovalRechecksTheFrozenSnapshot() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            Path source = Files.createDirectory(temporary.resolve("source")).toRealPath();
            Path small = source.resolve("main.cpp");
            Files.writeString(small, "int main() { return 0; }\n");
            Files.write(source.resolve("000-large.dat"), new byte[1_048_577]);
            byte[] stableBytes = Files.readAllBytes(small);
            UUID origin = UUID.randomUUID();
            var frozen = new FileSnapshot() {
                @Override public UUID id() { return origin; }
                @Override public String sha256() {
                    try {
                        if (!java.util.Arrays.equals(stableBytes, Files.readAllBytes(small)))
                            throw new IllegalStateException("Original fixture bytes changed");
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    return "a".repeat(64);
                }
                @Override public Path directory() { return source; }
                @Override public void close() { }
            };
            var material = new DeploymentSourceMaterial("Large fixture", frozen, List.of("000-large.dat", "main.cpp"), List.of());
            var archive = writeArchive(material, "", fixture.task);
            var review = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertEquals("PARTIAL", review.coverage());
            assertTrue(review.requiredUnavailable());
            assertEquals(List.of(new DeploymentSourceArchiveReview.Omitted("000-large.dat", 1_048_577, "REVIEW_BYTE_LIMIT")), review.omitted());
            assertEquals("main.cpp", review.members().getFirst().path());
            DeploymentSourceArchiveReview.verifyBinding(material, archive, fixture.task);
            Files.writeString(small, "int main() { return 1; }\n");
            assertEquals(stableBytes.length, Files.size(small));
            assertThrows(IOException.class, () -> DeploymentSourceArchiveReview.verifyBinding(material, archive, fixture.task));
            assertThrows(IOException.class, () -> DeploymentSourceArchiveReview.capture(material, archive, fixture.task));
        }
    }

    private DeploymentSourceArchive writeArchive(DeploymentSourceMaterial material, TaskContext task) throws Exception {
        return writeArchive(material, "backend", task);
    }

    private DeploymentSourceArchive writeArchive(DeploymentSourceMaterial material, String relativeRoot, TaskContext task) throws Exception {
        Path output = Files.createTempDirectory(temporary, "archive-");
        Path file = output.resolve("source.tar.gz");
        long total;
        try (var stream = Files.newOutputStream(file)) {
            total = DeploymentSourceTar.write(material, relativeRoot, stream, task);
        }
        byte[] bytes = Files.readAllBytes(file);
        return new DeploymentSourceArchive(new Snapshot(UUID.randomUUID(), "b".repeat(64), output), digest(bytes),
            bytes.length, total, material.snapshot().id(), material.snapshot().sha256(), relativeRoot);
    }

    private static void verifyLogAnalyzerBuildReview(MirroredInstallerTestFixture fixture, DeploymentSourceArchive archive,
                                                     DeploymentSourceArchiveReview.Evidence captured, String header) throws Exception {
        try (var build = buildReview(fixture, archive, captured)) {
            String facts = DeploymentAiJson.facts(build.facts(), REVIEW_CEILING);
            assertFalse(facts.contains(LOG_ANALYZER_MARKER));
            assertFalse(JSON.readTree(facts).at("/currentCommandEvidence/currentBuildArchiveSource").has("pagedBodies"));
            var nativeReview = nativeReview(fixture, REVIEW_CEILING);
            fixture.model.nativeReply(verdict("APPROVE"));
            assertEquals("APPROVE", review(nativeReview.reviewer(), fixture, build).path("decision").asText());
            assertTrue(nativeReview.refusedSizes().isEmpty());
            assertEquals(1, nativeReview.sent().size());
            byte[] wire = nativeReview.sent().getFirst();
            assertTrue(wire.length < REVIEW_CEILING);
            int historicalFactsWire = encodedAtLogAnalyzerFactsFloor(fixture.model.requests.getLast());
            assertTrue(historicalFactsWire < REVIEW_CEILING);
            var input = JSON.readTree(wire).path("input");
            var manifest = JSON.readTree(input.get(input.size() - 1).at("/content/0/text").asText()).path("buildReviewEvidence");
            assertEquals(archive.sha256(), manifest.at("/projectArchive/archiveSha256").asText());
            String snapshot = manifest.path("snapshotSha256").asText();
            var frame = retainedSource(input, snapshot);
            var members = manifest.at("/projectArchive/members");
            assertEquals(24, members.size());
            assertEquals(members.size(), frame.texts().size());
            assertTrue(frame.header().path("initialSupplement").asBoolean());
            assertFalse(frame.header().has("pendingRequired"));

            var descriptor = frame.header().path("repeatedOriginalBlocks");
            String pointer = descriptor.path("pointer").asText();
            for (var item : members) {
                String memberPointer = item.path("sourcePointer").asText();
                String content = frame.texts().get(memberPointer);
                assertNotNull(content, memberPointer);
                if (item.path("path").asText().equals(LOG_ANALYZER_HEADER)) {
                    assertEquals(pointer, memberPointer);
                    assertEquals(LOG_ANALYZER_HEADER_SHA256, item.path("sha256").asText());
                    assertEquals(953_436, item.path("utf8Bytes").asInt());
                    assertEquals(header.length(), item.path("characters").asInt());
                    assertTrue(item.path("initiallyPaged").asBoolean());
                    verifyRepeatedOriginalBlocks(frame, descriptor, content, header);
                } else {
                    assertEquals(item.path("sha256").asText(), digest(content.getBytes(StandardCharsets.UTF_8)));
                    assertEquals(item.path("characters").asInt(), content.length());
                }
            }
            assertFalse(pointer.isEmpty());
            verifyBuildRuntimeRanges(frame, manifest, build.request());

            int pageSize = 8000;
            var evidence = DeploymentCommandSourceEvidence.project(facts, build.intent(), build.request(), fixture.layout,
                Optional.of(captured)).orElseThrow();
            var localManifest = JSON.readTree(evidence.messages().getLast().text()).path("buildReviewEvidence");
            verifyLogAnalyzerHeaderPages(evidence, localManifest.path("snapshotSha256").asText(), pointer, header, pageSize);

            var cappedReview = nativeReview(fixture, ORIGINAL_PAGE_CEILING);
            fixture.model.nativeReply(verdict("DENY"));
            assertEquals("DENY", review(cappedReview.reviewer(), fixture, build).path("decision").asText());
            assertEquals(List.of(wire.length), cappedReview.refusedSizes());
            assertEquals(1, cappedReview.sent().size());
            byte[] plainWire = cappedReview.sent().getFirst();
            assertTrue(plainWire.length < ORIGINAL_PAGE_CEILING);
            assertFalse(new String(plainWire, StandardCharsets.UTF_8).contains(LOG_ANALYZER_MARKER));
            var plain = retainedSource(JSON.readTree(plainWire).path("input"), snapshot);
            assertFalse(plain.header().has("repeatedOriginalBlocks"));
            assertEquals(frame.texts().size() - 1, plain.texts().size());
            assertFalse(plain.texts().containsKey(pointer));
            assertPendingHeader(plain, pointer, 0, header.length());

            var originalFirstLimit = nativeReview(fixture, 629_030);
            fixture.model.nativeReply(verdict("APPROVE"));
            var incomplete = assertThrows(DeploymentCommandReviewModel.Failure.class,
                () -> review(originalFirstLimit.reviewer(), fixture, build));
            assertEquals("REQUIRED_SOURCE_MISSING", DeploymentCommandReviewModel.failureDiagnostics(incomplete).get("reviewEvidenceBlock"));
            assertEquals(List.of(wire.length), originalFirstLimit.refusedSizes());
            assertEquals(1, originalFirstLimit.sent().size());

            var fallback = nativeReview(fixture, ORIGINAL_PAGE_CEILING);
            var continuation = verifyPartialHeaderCannotApprove(fixture, build, fallback, snapshot, pointer, header, pageSize);
            assertEquals(List.of(wire.length), fallback.refusedSizes());
            assertFalse(retainedSource(JSON.readTree(fallback.sent().getFirst()).path("input"), snapshot)
                .header().has("repeatedOriginalBlocks"));
            assertTrue(continuation.length < ORIGINAL_PAGE_CEILING);

            var rawAfterBlocks = nativeReview(fixture, REVIEW_CEILING);
            verifyPartialHeaderCannotApprove(fixture, build, rawAfterBlocks, snapshot, pointer, header, pageSize);
            assertTrue(rawAfterBlocks.refusedSizes().isEmpty());
            assertTrue(retainedSource(JSON.readTree(rawAfterBlocks.sent().getFirst()).path("input"), snapshot)
                .header().has("repeatedOriginalBlocks"));
            System.out.printf("R75_ORIGINAL_ARCHIVE sha=%s members=25 captured=24 packedInitial=%d historicalFactsEncoded=%d references=%d "
                    + "rawInitial=%d rawContinued=%d rawCeiling=%d historicalFirstCeiling=629030%n", archive.sha256(), wire.length,
                historicalFactsWire, descriptor.path("references").asInt(), plainWire.length, continuation.length, ORIGINAL_PAGE_CEILING);
        }
    }

    private static int encodedAtLogAnalyzerFactsFloor(AiRequest request) throws Exception {
        var messages = new ArrayList<>(request.messages());
        var facts = messages.getLast();
        int padding = 45_338 - facts.text().length(); // R58 build review evidenceCharacters.
        if (padding > 0) {
            var sizedFacts = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(facts.text());
            sizedFacts.put("fixturePadding", "x".repeat(padding));
            messages.set(messages.size() - 1, new AiMessage(facts.role(), JSON.writeValueAsString(sizedFacts), List.of()));
        }
        return encode(reviewWithMessages(request, messages)).length;
    }

    private static void verifyRenderedLogAnalyzerBuildReview(MirroredInstallerTestFixture fixture,
            DeploymentSourceMaterial material, DeploymentSourceArchive archive, DeploymentSourceArchiveReview.Evidence captured,
            String header) throws Exception {
        try (var build = LogAnalyzerBuildReviewFixture.render(fixture, material, archive, captured)) {
            var phases = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentCargoBuildInput
                .phases(new String(build.request().stdin(), StandardCharsets.UTF_8)).orElseThrow();
            var prefetch = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentCargoBuildInput.prefetch(phases);
            assertEquals(DeploymentMirrorRuntime.cargoRuntimeSource(), prefetch.runtime());
            var commandFacts = JSON.valueToTree(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence
                .facts(build.intent(), build.request(), fixture.layout, fixture.context, fixture.task));
            assertEquals(prefetch.runtime(), commandFacts.at("/mirrorTransport/runtimeSource").asText());
            assertEquals(digest(prefetch.runtime().getBytes(StandardCharsets.UTF_8)), commandFacts.at("/mirrorTransport/runtimeSha256").asText());
            assertTrue(commandFacts.at("/cargoBuildPrefetch/prefetchEntrySource").asText()
                .contains(DeploymentMirrorRuntime.cargoRuntimeSha256()));
            int r90WireLimit = 982_900;
            var nativeReview = nativeReview(fixture, r90WireLimit);
            fixture.model.nativeReply(verdict("APPROVE"));
            String instructions = DeploymentAssistedScope.standardBuildReviewInstructions();
            JsonNode decision;
            try {
                decision = nativeReview.reviewer().askCommand(instructions, build.facts(), fixture.model.configurationRevision,
                    fixture.task, DeploymentReviewDecision::malformed, build.intent(), build.request());
            } catch (DeploymentCommandReviewModel.Failure failure) {
                fail("Rendered Log Analyzer build did not deliver complete evidence, encoder refusals: " + nativeReview.refusedSizes(), failure);
                return;
            }
            assertEquals("APPROVE", decision.path("decision").asText());
            assertTrue(nativeReview.refusedSizes().isEmpty());
            assertEquals(1, nativeReview.sent().size());
            byte[] wire = nativeReview.sent().getFirst();
            assertTrue(wire.length <= r90WireLimit);
            int historicalFactsWire = encodedAtLogAnalyzerFactsFloor(fixture.model.requests.getLast());
            assertTrue(historicalFactsWire < REVIEW_CEILING);
            var input = JSON.readTree(wire).path("input");
            var manifest = JSON.readTree(input.get(input.size() - 1).at("/content/0/text").asText()).path("buildReviewEvidence");
            assertEquals(archive.sha256(), manifest.at("/projectArchive/archiveSha256").asText());
            var frame = retainedSource(input, manifest.path("snapshotSha256").asText());
            assertFalse(frame.header().has("pendingRequired"));
            assertEquals(captured.members().size(), frame.texts().size());
            var descriptor = frame.header().path("repeatedOriginalBlocks");
            verifyRepeatedOriginalBlocks(frame, descriptor, frame.texts().get(descriptor.path("pointer").asText()), header);
            int headerWireBytes = JSON.writeValueAsBytes(frame.texts().get(descriptor.path("pointer").asText())).length;
            assertTrue(838_020 - headerWireBytes >= 1_023_588 - r90WireLimit);
            verifyCargoPrefetchRanges(frame, manifest, build.request());
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(build.facts(), REVIEW_CEILING),
                build.intent(), build.request(), fixture.layout, Optional.of(captured)).orElseThrow();
            var aliasReader = evidence.reader(8);
            var messages = new ArrayList<>(evidence.messages());
            aliasReader.offerInitialSource(messages);
            var aliasSource = new StringBuilder();
            String aliasSnapshot = JSON.readTree(evidence.messages().getLast().text()).at("/buildReviewEvidence/snapshotSha256").asText();
            var calls = new ArrayList<AiToolCall>();
            for (int offset = 0; offset < prefetch.runtime().length(); offset += 8000)
                calls.add(evidenceCall("cargo-runtime-alias-" + offset, aliasSnapshot,
                    "/buildReviewEvidence/runtimeSelection", offset, 8000));
            assertTrue(calls.size() <= 8);
            var response = responses(calls);
            var pages = aliasReader.read(response);
            for (int index = 0; index < pages.size(); index++) {
                int offset = index * 8000;
                assertFalse(pages.get(index).error(), pages.get(index)::text);
                var parts = pages.get(index).text().split("\n", 3);
                assertEquals(prefetch.runtime().substring(offset, Math.min(offset + 8000, prefetch.runtime().length())), parts[2]);
                aliasSource.append(parts[2]);
            }
            aliasReader.continueReview(messages, response, pages);
            assertArrayEquals(prefetch.runtime().getBytes(StandardCharsets.UTF_8), aliasSource.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals(DeploymentMirrorRuntime.cargoRuntimeSha256(), digest(aliasSource.toString().getBytes(StandardCharsets.UTF_8)));
            aliasReader.read(responses(verdict("APPROVE")));
            assertEquals(Optional.of("REQUIRED_SOURCE_MISSING"), aliasReader.approvalEvidenceBlock(),
                "Raw runtime rereads cannot retain an absent complete archive from an earlier request");
            verifyRenderedBuildBody(input, manifest.path("buildBody"), build);
            assertEquals(392, build.bindingBytes());
            assertEquals("d16b3b4efd86b4c33c5b3018bca667d4cb644712091d51c39f3f8089af4fb7ea", build.bindingSha256());

            int firstHistoricalLimit = 629_030;
            var limited = nativeReview(fixture, firstHistoricalLimit);
            fixture.model.nativeReply(verdict("APPROVE"));
            var rejected = assertThrows(DeploymentCommandReviewModel.Failure.class, () -> limited.reviewer().askCommand(
                instructions, build.facts(), fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed,
                build.intent(), build.request()));
            assertEquals("REQUIRED_SOURCE_MISSING", DeploymentCommandReviewModel.failureDiagnostics(rejected).get("reviewEvidenceBlock"));
            assertEquals(List.of(wire.length), limited.refusedSizes());
            assertEquals(1, limited.sent().size());
            assertTrue(limited.sent().getFirst().length < firstHistoricalLimit);
            System.out.printf("R76_RENDERED_LOG_ANALYZER archive=%s bodySha=%s bodyBytes=%d bindingSha=%s bindingBytes=%d "
                    + "nativeBytes=%d historicalFactsEncoded=%d historicalFirstCeiling=%d fallbackBytes=%d%n", archive.sha256(),
                build.buildBodySha256(), build.buildBodyBytes(), build.bindingSha256(), build.bindingBytes(),
                wire.length, historicalFactsWire, firstHistoricalLimit, limited.sent().getFirst().length);
        }
    }

    private static void verifyRenderedBuildBody(JsonNode input, JsonNode descriptor, LogAnalyzerBuildReviewFixture.Rendered build)
        throws Exception {
        String pointer = descriptor.path("sourcePointer").asText();
        assertEquals(build.buildBodySha256(), descriptor.path("sha256").asText());
        var records = new ArrayList<String>();
        for (var message : input) {
            String text = message.at("/content/0/text").asText();
            if (!text.startsWith("WTL-TOOLCHAIN-REVIEW-SOURCE-1\n")) continue;
            var parts = text.split("\n", 3);
            if (pointer.equals(JSON.readTree(parts[1]).path("sourcePointer").asText())) records.add(parts[2]);
        }
        assertEquals(1, records.size());
        String source = records.getFirst();
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        assertEquals(build.buildBodyBytes(), bytes.length);
        assertEquals(build.buildBodySha256(), digest(bytes));
        assertTrue(source.contains("source.wtl-local-registry.local-registry"));
        assertTrue(source.contains("run cargo build --offline --locked --release"));
        assertTrue(source.contains("cmake") && source.contains("log-worker"));
    }

    private static void verifyCargoPrefetchRanges(RetainedSource frame, JsonNode manifest, CommandRequest request) throws Exception {
        assertTrue(manifest.path("cargoPrefetch").asBoolean());
        assertEquals("cli/Cargo.lock", manifest.path("lockMember").asText());
        var phases = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentCargoBuildInput
            .phases(new String(request.stdin(), StandardCharsets.UTF_8)).orElseThrow();
        var data = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentCargoBuildInput.prefetch(phases);
        var sources = new LinkedHashMap<String, String>();
        sources.put("/mirrorTransport/runtimeSource", data.runtime());
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var implementation = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle
            .cargoBuildPrefetchFacts(layout);
        manifest.path("cargoPrefetchImplementation").fields().forEachRemaining(entry -> {
            if (entry.getValue().has("sourcePointer"))
                sources.put(entry.getValue().path("sourcePointer").asText(), implementation.get(entry.getKey()).toString());
        });
        sources.put(manifest.at("/stagedMirrorPolicy/sourcePointer").asText(), data.policy());
        var delivered = new LinkedHashMap<String, Integer>();
        var restored = new LinkedHashMap<String, char[]>();
        sources.forEach((pointer, source) -> restored.put(pointer, new char[source.length()]));
        for (var range : frame.ranges()) {
            if (!sources.containsKey(range.pointer())) continue;
            assertEquals(sources.get(range.pointer()).substring(range.offset(), range.next()), range.body());
            range.body().getChars(0, range.body().length(), restored.get(range.pointer()), range.offset());
            delivered.merge(range.pointer(), range.next() - range.offset(), Integer::sum);
        }
        for (var source : sources.entrySet()) {
            assertEquals(source.getValue().length(), delivered.get(source.getKey()), source.getKey());
            byte[] original = source.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] current = new String(restored.get(source.getKey())).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(original, current, source.getKey());
            assertEquals(digest(original), digest(current), source.getKey());
        }
        var runtimeRange = manifest.path("selectedRuntimeRanges").get(0);
        assertEquals("/mirrorTransport/runtimeSource", runtimeRange.path("sourcePointer").asText());
        assertEquals(0, runtimeRange.path("offset").asInt());
        assertEquals(data.runtime().length(), runtimeRange.path("next").asInt());
        assertEquals(digest(data.runtime().getBytes(StandardCharsets.UTF_8)), DeploymentMirrorRuntime.cargoRuntimeSha256());
    }

    private static void verifyRepeatedOriginalBlocks(RetainedSource frame, JsonNode descriptor, String display, String header)
        throws Exception {
        String pointer = descriptor.path("pointer").asText();
        var range = frame.ranges().stream().filter(item -> item.pointer().equals(pointer)).findFirst().orElseThrow();
        assertEquals(0, range.offset());
        assertEquals(header.length(), range.next());
        assertEquals(header.length(), descriptor.path("originalCharacters").asInt());
        assertEquals(display.length(), descriptor.path("displayCharacters").asInt());
        assertTrue(display.length() < header.length());
        assertEquals(LOG_ANALYZER_HEADER_SHA256, descriptor.path("sha256").asText());

        var displayedLines = Pattern.compile("[^\n]*\n|[^\n]+\\z").matcher(display).results().map(MatchResult::group)
            .map(DeploymentDirectoryDiffBuildReviewReplayTest::decodeLeadingSpaces).toList();
        var originalLines = new ArrayList<String>();
        var anchors = new LinkedHashMap<Integer, List<String>>();
        int references = 0;
        for (int index = 0; index < displayedLines.size(); index++) {
            String line = displayedLines.get(index);
            if (!line.startsWith("@WTL_")) {
                originalLines.add(line);
                continue;
            }
            String[] marker = line.stripTrailing().split(" ");
            assertEquals(3, marker.length);
            int first = Integer.parseInt(marker[1]);
            int count = Integer.parseInt(marker[2]);
            assertTrue(first > 0 && count > 0);
            if (marker[0].equals("@WTL_BLOCK")) {
                assertEquals(originalLines.size() + 1, first);
                var literals = displayedLines.subList(index + 1, index + 1 + count);
                assertTrue(literals.stream().noneMatch(literal -> literal.startsWith("@WTL_")));
                assertNull(anchors.put(first, literals));
                originalLines.addAll(literals);
                index += count;
            } else {
                assertEquals("@WTL_COPY", marker[0]);
                var block = anchors.get(first);
                assertNotNull(block);
                assertEquals(count, block.size());
                originalLines.addAll(block);
                references++;
            }
        }
        assertTrue(references > 0);
        assertEquals(references, descriptor.path("references").asInt());
        assertEquals(originalLines.size(), descriptor.path("originalLines").asInt());
        byte[] rebuilt = String.join("", originalLines).getBytes(StandardCharsets.UTF_8);
        assertEquals(LOG_ANALYZER_HEADER_SHA256, digest(rebuilt));
        assertArrayEquals(header.getBytes(StandardCharsets.UTF_8), rebuilt);
    }

    private static void verifyBuildRuntimeRanges(RetainedSource frame, JsonNode manifest, CommandRequest request) throws Exception {
        var shellDescriptor = manifest.path("mirrorBootstrapShell");
        String stdin = new String(request.stdin(), StandardCharsets.UTF_8);
        int shellStart = stdin.indexOf("\nwtl_source_providers() {\n") + 1;
        assertTrue(shellStart > 0);
        String shell = stdin.substring(shellStart, shellStart + shellDescriptor.path("characters").asInt());
        assertEquals(shellDescriptor.path("sha256").asText(), digest(shell.getBytes(StandardCharsets.UTF_8)));
        var originals = Map.of(shellDescriptor.path("sourcePointer").asText(), shell,
            "/mirrorTransport/runtimeSource", DeploymentMirrorRuntime.runtimeSource(),
            manifest.at("/stagedMirrorPolicy/sourcePointer").asText(), DeploymentMirrorTasks.buildInput(stdin).orElseThrow().policy());
        var coverage = new LinkedHashMap<String, BitSet>();
        for (var range : frame.ranges()) {
            if (frame.texts().containsKey(range.pointer())) continue;
            String original = originals.get(range.pointer());
            assertNotNull(original, range.pointer());
            assertEquals(original.substring(range.offset(), range.next()), range.body());
            coverage.computeIfAbsent(range.pointer(), ignored -> new BitSet()).set(range.offset(), range.next());
        }
        var literal = manifest.path("bootstrapShellLiteral");
        if (literal.isObject()) {
            var source = literal.path("source");
            var target = literal.path("target");
            String sourcePointer = source.path("pointer").asText();
            int start = source.path("offset").asInt(), end = source.path("next").asInt();
            String encoded = JSON.writeValueAsString(originals.get(sourcePointer).substring(start, end));
            String targetPointer = target.path("pointer").asText();
            int offset = target.path("offset").asInt(), next = target.path("next").asInt();
            assertEquals(originals.get(targetPointer).substring(offset, next), encoded);
            assertEquals(target.path("sha256").asText(), digest(encoded.getBytes(StandardCharsets.UTF_8)));
            coverage.computeIfAbsent(targetPointer, ignored -> new BitSet()).set(offset, next);
        }
        for (var selected : manifest.path("selectedRuntimeRanges")) {
            var seen = coverage.get(selected.path("sourcePointer").asText());
            assertNotNull(seen);
            assertTrue(seen.nextClearBit(selected.path("offset").asInt()) >= selected.path("next").asInt(), selected::toString);
        }
    }

    private static byte[] verifyPartialHeaderCannotApprove(MirroredInstallerTestFixture fixture, BuildReview build,
            NativeReview nativeReview, String snapshot, String pointer, String header, int pageSize) throws Exception {
        var read = responses(evidenceCall("log-analyzer-header-first", snapshot, pointer, 0, pageSize));
        fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(read), Optional.empty()));
        fixture.model.nativeReply(verdict("APPROVE"));
        var rejected = assertThrows(DeploymentCommandReviewModel.Failure.class, () -> review(nativeReview.reviewer(), fixture, build));
        var diagnostics = DeploymentCommandReviewModel.failureDiagnostics(rejected);
        assertEquals("VERDICT", diagnostics.get("reviewStage"));
        assertEquals("EVIDENCE_INCOMPLETE_VERDICT", diagnostics.get("reviewCode"));
        assertEquals("REQUIRED_SOURCE_MISSING", diagnostics.get("reviewEvidenceBlock"));
        assertEquals(2, nativeReview.sent().size());
        byte[] continuation = nativeReview.sent().getLast();
        var confirmed = retainedSource(JSON.readTree(continuation).path("input"), snapshot);
        assertFalse(confirmed.header().has("repeatedOriginalBlocks"));
        assertEquals(header.substring(0, pageSize), confirmed.texts().get(pointer));
        assertPendingHeader(confirmed, pointer, pageSize, header.length());
        return continuation;
    }

    private static void verifyLogAnalyzerHeaderPages(DeploymentCommandSourceEvidence evidence, String snapshot,
                                                     String pointer, String header, int pageSize) throws Exception {
        var reader = evidence.reader(Math.ceilDiv(header.length(), pageSize));
        var reassembled = new StringBuilder(header.length());
        for (int offset = 0; offset < header.length(); offset += pageSize) {
            var call = evidenceCall("log-analyzer-header-" + offset, snapshot, pointer, offset, pageSize);
            var result = reader.read(reply(call)).getFirst();
            assertFalse(result.error(), result::text);
            var parts = result.text().split("\n", 3);
            var page = JSON.readTree(parts[1]).path("value");
            assertEquals(LOG_ANALYZER_HEADER_SHA256, page.path("sha256").asText());
            assertEquals(offset, page.path("offset").asInt(-1));
            int next = Math.min(offset + pageSize, header.length());
            assertEquals(next, page.path("next").asInt());
            assertEquals(header.substring(offset, next), parts[2]);
            reassembled.append(parts[2]);
        }
        assertEquals(LOG_ANALYZER_HEADER_SHA256, digest(reassembled.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertPendingHeader(RetainedSource frame, String pointer, int offset, int next) {
        var range = Map.of("pointer", pointer, "offset", offset, "next", next);
        assertEquals(JSON.valueToTree(List.of(range)), frame.header().path("pendingRequired"));
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
            var frame = retainedSource(JSON.readTree(wire).path("input"),
                projected.at("/buildReviewEvidence/snapshotSha256").asText());
            assertTrue(frame.header().path("pendingRequired").isMissingNode());
            var texts = frame.texts();
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

    private record SourceRange(String pointer, int offset, int next, String body) { }

    private record RetainedSource(JsonNode header, Map<String, String> texts, List<SourceRange> ranges) { }

    private static RetainedSource retainedSource(JsonNode input, String snapshot) throws Exception {
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
        int at = headerEnd + 1;
        var sources = new LinkedHashMap<String, String>();
        var ranges = new ArrayList<SourceRange>();
        var blocks = header.path("repeatedOriginalBlocks");
        for (var range : header.path("ranges")) {
            String pointer = range.path("pointer").asText();
            int offset = range.path("offset").asInt();
            int next = range.path("next").asInt();
            int displayLength = pointer.equals(blocks.path("pointer").asText()) ? blocks.path("displayCharacters").asInt() : next - offset;
            String body = frame.substring(at, at + displayLength);
            ranges.add(new SourceRange(pointer, offset, next, body));
            if (pointer.startsWith("/buildReviewEvidence/projectArchive/members/")) {
                assertEquals(0, offset);
                assertNull(sources.put(pointer, body));
            }
            at += displayLength;
        }
        assertEquals(frame.length(), at);
        return new RetainedSource(header, sources, ranges);
    }

    private record NativeReview(DeploymentAiJson reviewer, List<byte[]> sent, List<Integer> refusedSizes) { }

    private static NativeReview nativeReview(MirroredInstallerTestFixture fixture, int ceiling) {
        var sent = new ArrayList<byte[]>();
        var refusedSizes = new ArrayList<Integer>();
        var loader = DeploymentSourceArchiveReviewTest.class.getClassLoader();
        var original = fixture.context;
        var service = Proxy.newProxyInstance(loader, new Class<?>[]{AiService.class}, (proxy, method, args) -> {
            if (method.getName().equals("call")) {
                var request = (AiRequest) args[0];
                int bound = Math.min(ceiling, request.maxRequestBytes());
                var bounded = new AiRequest(request.purpose(), request.messages(), request.maxOutputTokens(), request.timeout(),
                    request.jsonObjectResponse(), request.tools(), bound, request.requiredToolName(), request.generationRecoveryAttempts(),
                    request.modelId(), request.reasoningOverride());
                try {
                    sent.add(encode(bounded));
                } catch (ModelRequestBudgetException failure) {
                    refusedSizes.add(failure.encodedBytes());
                    throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Local encoder bound",
                        Map.of("maximumRequestBytes", Integer.toString(bound), "encodedRequestBytes",
                            Integer.toString(failure.encodedBytes())), Optional.empty()));
                }
            }
            try {
                return method.invoke(original.ai(), args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        var context = (ModContext) Proxy.newProxyInstance(loader, new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("ai")) return service;
            try {
                return method.invoke(original, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        return new NativeReview(new DeploymentAiJson(context), sent, refusedSizes);
    }

    private static JsonNode review(DeploymentAiJson reviewer, MirroredInstallerTestFixture fixture, BuildReview build) throws Exception {
        return reviewer.askCommand("Review the original test input", build.facts(), fixture.model.configurationRevision,
            fixture.task, DeploymentReviewDecision::malformed, build.intent(), build.request());
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


    private record BuildReview(CommandRequest request, ActionIntent intent, Map<String, Object> facts) implements AutoCloseable {
        @Override public void close() throws Exception { request.close(); }
    }

    private static BuildReview buildReview(MirroredInstallerTestFixture fixture, DeploymentSourceArchive archive,
                                          DeploymentSourceArchiveReview.Evidence captured) throws Exception {
        String body = "set -eu\ntest \"$(sha256sum \"$archive\" | awk '{print $1}')\" = '" + archive.sha256()
            + "'\nprintf '%s\\n' OFFLINE_BUILD_REVIEW\n";
        String input = DeploymentMirrorTasks.withMaterials(DeploymentMirrorRuntime.wrapBuild(
            body, DeploymentMirrorTasks.policy(fixture.task), 120), fixture.task.id());
        var workspace = new DeploymentRemoteWorkspace("r65-archive", "a".repeat(64), fixture.layout);
        String collector = DeploymentBuildOutputProtocol.script(workspace, 120, 16, 512, 8192, "ordinary",
            MirroredInstallerTestFixture.sha(input), "-", List.of(List.of("BUILD_OK")));
        var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(collector, fixture.layout),
            input.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(135), 65536);
        var intent = RemoteActions.command(fixture.server, fixture.model.configurationRevision, request)
            .withSemantics(new ActionSemantics("deployment.build.run.v1",
                Set.of(new ActionTarget(fixture.server.id(), "deployment-build", workspace.candidateRoot()))));
        var facts = new LinkedHashMap<String, Object>(new DeploymentFixedCommandProtocol(fixture.context)
            .inspect(intent, request, fixture.layout, fixture.task));
        var bound = Map.of("archiveSha256", archive.sha256(), "sourceManifestSha256", archive.sourceManifestSha256(),
            "relativeRoot", archive.relativeRoot(), "fileCount", captured.memberCount());
        facts.put("currentCommandEvidence", Map.of("currentBuildArchive", Map.of("archive", bound),
            "currentBuildArchiveSource", captured));
        return new BuildReview(request, intent, facts);
    }

    private static AiToolCall verdict(String decision) {
        return new AiToolCall("r65-verdict", "submit_result", "{\"decision\":\"" + decision
            + "\",\"requiresUserConfirmation\":false,\"reason\":\"Offline fixture\"}");
    }

    private static AiResponse responses(AiToolCall call) {
        return responses(List.of(call));
    }

    private static AiResponse responses(List<AiToolCall> calls) {
        var base = reply(calls.getFirst());
        var attempt = new AiGenerationAttempt(UUID.randomUUID(), 1, base.modelId(), "OPENAI_RESPONSES",
            TaskResult.SUCCEEDED, "AI_GENERATION_COMPLETE", "NONE", 0L, base.usage(), 1, 65536);
        return new AiResponse(base.text(), base.modelId(), base.finishReason(), base.usage(), calls,
            Optional.of(new AiContinuation(UUID.randomUUID(), 64)), Optional.empty(), Optional.empty(), List.of(attempt));
    }

    private static Path fixtureRoot(String name, String requiredMember) {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path root = current.resolve("test/multi-language/" + name);
            if (Files.isRegularFile(root.resolve(requiredMember))) return root;
        }
        throw new IllegalStateException("Project archive fixture missing: " + name);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Snapshot(UUID id, String sha256, Path directory) implements FileSnapshot {
        @Override public void close() { }
    }
}

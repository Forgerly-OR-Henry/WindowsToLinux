package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelRequestBudgetException;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiveReview;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewAnswers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewBuilder;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.AssetLendingBuildReviewFixture;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R90 archive and Go binding, with a reconstructed public routing policy, task, and Responses request. */
class DeploymentR90FileTransferBudgetReplayTest {
    private static final String APP = "wtl-assisted-file-transfer-ukpgbgu6g-backend";
    private static final String ARCHIVE = "9d294372013e53015ab7543d8c8a9cc84bd61d65dd48fced98aa12a0035a3180";
    private static final String BINDING = "876c5dbc542b940f2c6ed34adccff76951e0ca9e07487c992b2f0ab9450ebae1";
    private static final String FRAME = "WTL-COMMAND-RETAINED-SOURCE-1\n";
    private static final String RUNTIME = "/mirrorTransport/runtimeSource";
    private static final String SHELL = "/buildReviewEvidence/mirrorBootstrapShell";
    private static final String BUILD_PACKAGE = "gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.";
    private static final int PHYSICAL_LIMIT = 1_048_576;
    private static final int OUTPUT_RESERVE = 65_536;
    private static final int ORIGINAL_FIXED_UTF8 = 74_848;
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path scratch;

    @Test
    void replaysExactFileTransferArchiveAtR90BuildCeilings() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.GO_MODULE, DownloadPoint.GO_CHECKSUM);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            Path root = fixtureRoot();
            List<String> names;
            try (var paths = Files.walk(root.resolve("backend"))) {
                names = paths.filter(Files::isRegularFile).map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/')).sorted().toList();
            }
            var material = new DeploymentSourceMaterial("File Transfer", new Snapshot(UUID.randomUUID(), "a".repeat(64), root),
                names, List.of());
            var archive = archive(material, fixture.task);
            var sources = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            assertEquals(13, sources.memberCount());
            assertEquals("PARTIAL", sources.coverage());
            assertEquals(0, sources.uninspectedMembers());
            assertFalse(sources.requiredUnavailable());
            try (var command = build(fixture, material, archive)) {
                var facts = new LinkedHashMap<>(new DeploymentFixedCommandProtocol(fixture.context)
                    .inspect(command.intent(), command.request(), fixture.layout, fixture.task));
                facts.put("currentCommandEvidence", Map.of("currentBuildArchive", archiveFacts(fixture, archive, names, command),
                    "currentBuildArchiveSource", sources));
                fixture.model.nativeReply(deny());
                var decision = new DeploymentAiJson(assisted.context).askCommand(
                    DeploymentFixedCommandApproval.commandReviewInstructions(true) + DeploymentAssistedScope.standardBuildReviewInstructions(),
                    facts, fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed,
                    command.intent(), command.request());
                assertEquals("DENY", decision.path("decision").asText());
                assertEquals(0, assisted.hosts.get());
                var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, PHYSICAL_LIMIT),
                    command.intent(), command.request(), fixture.layout, Optional.of(sources)).orElseThrow();
                assertCeilings(fixture.model.requests.getFirst(), evidence);
            }
        }
    }

    private static void assertCeilings(AiRequest template, DeploymentCommandSourceEvidence evidence) throws Exception {
        var fixed = new ArrayList<>(evidence.messages());
        fixed.addFirst(template.messages().getFirst());
        var manifest = JSON.readTree(fixed.getLast().text()).path("buildReviewEvidence");
        String snapshot = manifest.path("snapshotSha256").textValue();
        assertNotNull(snapshot);
        assertEquals(ARCHIVE, manifest.at("/projectArchive/archiveSha256").asText());
        assertTrue(manifest.path("selectedRuntimeRanges").findValuesAsText("section").contains("goDependencyEnvironment"));
        var paged = new ArrayList<JsonNode>();
        manifest.at("/projectArchive/members").forEach(member -> {
            if (member.path("initiallyPaged").asBoolean()) paged.add(member);
        });
        assertEquals(1, paged.size());
        var member = paged.getFirst();
        var initial = template.messages().stream().map(AiMessage::text).filter(body -> body.startsWith(FRAME))
            .findFirst().orElseThrow();
        int initialEnd = initial.indexOf('\n', FRAME.length());
        var initialHeader = JSON.readTree(initial.substring(FRAME.length(), initialEnd));
        var sentManifest = JSON.readTree(template.messages().getLast().text()).path("buildReviewEvidence");
        assertEquals(ARCHIVE, sentManifest.at("/projectArchive/archiveSha256").asText());
        assertEquals(sentManifest.path("snapshotSha256").asText(), initialHeader.path("snapshotSha256").asText());
        assertTrue(initialHeader.path("pendingRequired").isEmpty());
        assertMemberIdentity(initial, initialEnd + 1, initialHeader.path("ranges"), member);
        int initialWire = wire(template, template.messages(), 194_756);
        String memberPointer = member.path("sourcePointer").asText();
        int size = member.path("characters").asInt();
        var reads = new ArrayList<Cursor>();
        for (int offset = 0; offset < size; offset += 8_000)
            reads.add(new Cursor(memberPointer, offset, Math.min(8_000, size - offset)));
        var memberReads = List.copyOf(reads);
        // The raw trace redacts the archive member pointer. Reconstruct it from the one paged
        // member in the original archive. These two public cursors were requested in R90.
        reads.add(new Cursor(SHELL, 12_747, 1_117));
        reads.add(new Cursor(RUNTIME, 199_929, 105));
        assertTrue(reads.size() <= 8);
        int fixedWire = wire(template, fixed, PHYSICAL_LIMIT);
        int rawFixed = fixed.stream().mapToInt(message -> message.text().getBytes(StandardCharsets.UTF_8).length).sum();
        var required = replay(template, evidence, fixed, snapshot, member, memberReads, PHYSICAL_LIMIT);
        assertTrue(required.complete());
        if (required.wireBytes() <= 194_756)
            assertTrue(replay(template, evidence, fixed, snapshot, member, memberReads, 194_756).complete());
        var full = replay(template, evidence, fixed, snapshot, member, reads, PHYSICAL_LIMIT);
        assertTrue(full.complete());
        assertTrue(full.wireBytes() > fixedWire);
        var observed = List.of(new Observed(260_596, 257_995, 0), new Observed(194_756, 193_196, 1), new Observed(146_377, 145_927, 20));
        assertTrue(full.wireBytes() <= 194_756);
        for (var limit : observed) {
            var sent = replay(template, evidence, fixed, snapshot, member, reads, limit.cap());
            if (full.wireBytes() <= limit.cap()) assertTrue(sent.complete());
            System.out.printf("R90_FILE_TRANSFER_REPLAY cap=%d archive=%s fixedUtf8=%d r90FixedUtf8=%d fixedWire=%d initialWire=%d requiredWire=%d uncappedCompleteWire=%d "
                    + "sent=%d missing=%d complete=%s originalSent=%d originalMissing=%d%n",
                limit.cap(), ARCHIVE, rawFixed, ORIGINAL_FIXED_UTF8, fixedWire, initialWire, required.wireBytes(), full.wireBytes(), sent.wireBytes(), sent.missing(),
                sent.complete(), limit.sent(), limit.missing());
        }
        assertFalse(replay(template, evidence, fixed, snapshot, member, reads, fixedWire + 4_000).complete());
    }

    private static Replay replay(AiRequest template, DeploymentCommandSourceEvidence evidence, List<AiMessage> fixed,
                                 String snapshot, JsonNode member, List<Cursor> reads, int cap) throws Exception {
        var messages = new ArrayList<>(fixed);
        var reader = evidence.reader(32);
        reader.offerInitialSource(messages);
        var calls = new ArrayList<AiToolCall>();
        for (var page : reads) calls.add(new AiToolCall("reconstructed-" + calls.size(), "evidence", JSON.writeValueAsString(
            Map.of("snapshotSha256", snapshot, "pointer", page.pointer(), "offset", page.offset(), "limit", page.limit()))));
        var response = responses(calls);
        var results = reader.read(response);
        assertEquals(calls.size(), results.size());
        assertTrue(results.stream().noneMatch(AiToolResult::error));
        reader.continueReview(messages, response, results);
        int sent;
        try {
            sent = wire(template, messages, cap);
        } catch (ModelRequestBudgetException refused) {
            var bound = new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Local encoder bound", Map.of(
                "maximumRequestBytes", Integer.toString(cap), "encodedRequestBytes", Integer.toString(refused.encodedBytes())), Optional.empty());
            if (!reader.repackBeforeSend(messages, bound)) return Replay.unsent();
            try {
                sent = wire(template, messages, cap);
            } catch (ModelRequestBudgetException stillRefused) {
                assertTrue(stillRefused.encodedBytes() > cap);
                return Replay.unsent();
            }
        }
        var frame = messages.stream().map(AiMessage::text).filter(body -> body.startsWith(FRAME)).findFirst().orElseThrow();
        int headerEnd = frame.indexOf('\n', FRAME.length());
        var header = JSON.readTree(frame.substring(FRAME.length(), headerEnd));
        assertEquals(snapshot, header.path("snapshotSha256").asText());
        reader.read(responses(List.of(deny())));
        boolean complete = reader.approvalEvidenceBlock().isEmpty();
        if (complete) {
            assertEquals(0, header.path("pendingRequired").size());
            assertMemberIdentity(frame, headerEnd + 1, header.path("ranges"), member);
        }
        assertTrue(sent <= cap);
        return new Replay(sent, header.path("pendingRequired").size(), complete);
    }

    private static void assertMemberIdentity(String frame, int position, JsonNode ranges, JsonNode member) {
        var pieces = new java.util.TreeMap<Integer, String>();
        for (var range : ranges) {
            int offset = range.path("offset").asInt(), length = range.path("next").asInt() - offset;
            if (member.path("sourcePointer").equals(range.path("pointer")))
                assertNull(pieces.put(offset, frame.substring(position, position + length)));
            position += length;
        }
        assertEquals(frame.length(), position);
        var assembled = new StringBuilder();
        for (var piece : pieces.entrySet()) {
            assertEquals(assembled.length(), piece.getKey());
            assembled.append(piece.getValue());
        }
        assertEquals(member.path("characters").asInt(), assembled.length());
        assertEquals(member.path("sha256").asText(), DeploymentEvidencePages.sha256(assembled.toString()));
    }

    private static int wire(AiRequest template, List<AiMessage> messages, int cap) throws Exception {
        var request = new AiRequest(template.purpose(), messages, template.maxOutputTokens(), template.timeout(),
            template.jsonObjectResponse(), template.tools(), cap, template.requiredToolName(), template.generationRecoveryAttempts(),
            Optional.of("gpt-6-luna"), Optional.of(AiReasoningMode.HIGH));
        return ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES)
            .encode("gpt-6-luna", request, OUTPUT_RESERVE, ModelReasoningLevel.HIGH, Map.of()).length;
    }

    private static AiResponse responses(List<AiToolCall> calls) {
        var usage = new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var attempt = new AiGenerationAttempt(UUID.randomUUID(), 1, "fixture-approval", "OPENAI_RESPONSES", TaskResult.SUCCEEDED,
            "AI_GENERATION_COMPLETE", "NONE", 0L, usage, 1, OUTPUT_RESERVE);
        return new AiResponse("", "fixture-approval", "tool_calls", usage, calls, Optional.of(new AiContinuation(UUID.randomUUID(), 64)),
            Optional.empty(), Optional.empty(), List.of(attempt));
    }

    private static AiToolCall deny() {
        return new AiToolCall("offline-denial", "submit_result",
            "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Offline test decision\"}");
    }

    private record Cursor(String pointer, int offset, int limit) { }

    private record Observed(int cap, int sent, int missing) { }

    private record Replay(int wireBytes, int missing, boolean complete) {
        static Replay unsent() { return new Replay(0, -1, false); }
    }

    private record Snapshot(UUID id, String sha256, Path directory) implements FileSnapshot {
        @Override public void close() { }
    }

    private DeploymentSourceArchive archive(DeploymentSourceMaterial material, TaskContext task) throws Exception {
        var write = Class.forName("gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceTar")
            .getDeclaredMethod("write", DeploymentSourceMaterial.class, String.class, OutputStream.class, TaskContext.class);
        write.setAccessible(true);
        Path file = scratch.resolve("source.tar.gz");
        long contentBytes;
        try (var stream = Files.newOutputStream(file)) {
            contentBytes = (long) write.invoke(null, material, "backend", stream, task);
        }
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(ARCHIVE, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return new DeploymentSourceArchive(new Snapshot(UUID.randomUUID(), "b".repeat(64), scratch.toRealPath()),
            ARCHIVE, bytes.length, contentBytes, material.snapshot().id(), material.snapshot().sha256(), "backend");
    }

    @SuppressWarnings("unchecked")
    private static AssetLendingBuildReviewFixture.Captured build(MirroredInstallerTestFixture fixture,
            DeploymentSourceMaterial material, DeploymentSourceArchive archive) throws Exception {
        var assessment = new DeploymentStandardAnalyzer().analyzeComponent(material, Path.of("backend"), APP,
            DeploymentProjectKind.GO_SERVICE, fixture.task).value().orElseThrow();
        var limits = new DeploymentBuildLimits(1800, 1024, 2048, 4L * 1024 * 1024, 4L * 1024 * 1024 * 1024, false);
        var consent = new DeploymentReviewConsent(APP, fixture.server.id(), material.snapshot().sha256(), ARCHIVE, Instant.EPOCH);
        var values = Map.ofEntries(Map.entry("healthMode", "HTTP"), Map.entry("healthEndpoint", "http://127.0.0.1:46111/healthz"),
            Map.entry("expectedStatus", "200"), Map.entry("stability", "5"), Map.entry("port", "46111"), Map.entry("timeout", "10"),
            Map.entry("databaseMode", "NONE"), Map.entry("version", "1.24.0"), Map.entry("primary", "w2l-app"),
            Map.entry("secondary", "main.go"), Map.entry("exposure", "INTERNAL"), Map.entry("bindAddress", "127.0.0.1"),
            Map.entry("executionMode", "DAEMON"), Map.entry("configuration", "HOST=127.0.0.1\nPORT=46111"));
        var uploads = new DeploymentFileBinding("uploads",
            new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_WRITE, "data-v1", true),
            DeploymentStorageLocation.defaults(), DeploymentStorageLocation.StorageResourceType.FILE, "", "");
        var answers = new DeploymentStandardReviewAnswers(values, List.of(uploads), 1, limits, consent, false, true);
        var planner = new DeploymentStandardPlanner();
        var planned = new DeploymentStandardReviewBuilder(fixture.context.servers(), planner, fixture.layout)
            .plan(fixture.server, material, archive, assessment, answers, fixture.task);
        var plan = planned.value().orElseThrow(() -> new AssertionError(planned.problem().orElseThrow()));
        var rendered = planner.render(plan, fixture.task, fixture.layout).value().orElseThrow();
        var reviewed = plan.request();
        var requirement = DeploymentProjectToolchainRequirements.from(reviewed.facts(), reviewed.runtime()).stream()
            .filter(value -> value.ecosystem() == DeploymentToolchainType.GO).findFirst().orElseThrow();
        var selected = new DeploymentResolvedToolchains.Selection(requirement,
            DeploymentToolchainVersion.parse(DeploymentToolchainType.GO, "1.24.13").orElseThrow(),
            fixture.layout.toolchains() + "/versions/go-099c455725aea7869b2d91d013b489aada51556159192fe260ff124ddee824c0",
            DeploymentResolvedToolchains.OriginType.MANAGED, "https://go.dev/dl/go1.24.13.linux-amd64.tar.gz",
            "1fc94b57134d51669c72173ad5d49fd62afb0f1db9bf3f798fd98ee423f8d730");
        var tools = new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of(selected));
        assertEquals(BINDING, DeploymentToolchainBindingCodec.identity(tools));
        var script = Class.forName(BUILD_PACKAGE + "DeploymentStandardBuildRunner").getDeclaredMethod("script", String.class,
            ReviewedDeploymentRequest.class, DeploymentBuildEnvironment.class, DeploymentResolvedToolchains.class);
        script.setAccessible(true);
        String body = (String) script.invoke(null, rendered.buildScript(), reviewed,
            new DeploymentBuildEnvironment(APP, Map.of("HOST", "127.0.0.1", "PORT", "46111")), tools);
        assertTrue(body.contains("wtl_dependency GO_MODULE go mod download"));
        var policy = Class.forName(BUILD_PACKAGE + "BuildMatchPolicy").getDeclaredMethod("groups");
        policy.setAccessible(true);
        var groups = (List<List<String>>) policy.invoke(null);
        String binding = Base64.getEncoder().encodeToString(DeploymentToolchainBindingCodec.encode(tools).getBytes(StandardCharsets.UTF_8));
        var budget = new DeploymentBuildBudget(limits.timeoutSeconds());
        var workspace = new DeploymentRemoteWorkspace(APP, ARCHIVE, fixture.layout);
        return AssetLendingBuildReviewFixture.capture(fixture, rendered.reviewedPlanSha256(), budget,
            channel -> channel.build(workspace, limits, budget.remainingSeconds() - 15, "ordinary", body, binding, groups));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> archiveFacts(MirroredInstallerTestFixture fixture, DeploymentSourceArchive archive,
            List<String> names, AssetLendingBuildReviewFixture.Captured command) throws Exception {
        var observation = Map.<String, Object>of("originSnapshotId", archive.originSnapshotId().toString(),
            "sourceManifestSha256", archive.sourceManifestSha256(), "archiveSha256", archive.sha256(),
            "archiveSnapshotId", archive.snapshot().id().toString(), "localArchivePath", archive.path().toString(),
            "archiveBytes", archive.byteCount(), "relativeRoot", "backend", "fileCount", names.size(), "fileNamesTruncated", false,
            "fileNames", names.stream().map(name -> name.substring("backend/".length())).toList());
        var reviewed = new LinkedHashMap<>(observation);
        reviewed.put("application", APP);
        reviewed.put("server", fixture.server.id());
        reviewed.put("remoteLayoutSha256", fixture.layout.digest());
        var method = DeploymentAssistedScope.class.getDeclaredMethod("buildArchiveFacts", List.class, Map.class,
            gold.debug.windowstolinux.appapi.task.action.ActionIntent.class,
            gold.debug.windowstolinux.appapi.linux.command.CommandRequest.class,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(null, List.of(observation), Map.of(APP, reviewed),
            command.intent(), command.request(), fixture.layout);
    }

    private static Path fixtureRoot() throws Exception {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            Path source = root.resolve("test/multi-language/success-file-transfer");
            if (Files.isRegularFile(source.resolve("backend/go.mod"))) return source.toRealPath();
        }
        throw new AssertionError("Missing original File Transfer fixture");
    }
}

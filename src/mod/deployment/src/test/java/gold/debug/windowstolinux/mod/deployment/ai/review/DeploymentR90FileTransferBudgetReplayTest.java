package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiveReview;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
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
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewAnswers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewBuilder;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.AssetLendingBuildReviewFixture;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original R90 archive/binding and controlled R97 facts pressure, not an archived R97 wire/tool transcript. */
class DeploymentR90FileTransferBudgetReplayTest {
    private static final String APP = "wtl-assisted-file-transfer-ukpgbgu6g-backend";
    private static final String ARCHIVE = "9d294372013e53015ab7543d8c8a9cc84bd61d65dd48fced98aa12a0035a3180";
    private static final String BINDING = "876c5dbc542b940f2c6ed34adccff76951e0ca9e07487c992b2f0ab9450ebae1";
    private static final String FRAME = "WTL-COMMAND-RETAINED-SOURCE-1\n";
    private static final String RUNTIME = "/mirrorTransport/runtimeSource";
    private static final String SHELL = "/buildReviewEvidence/mirrorBootstrapShell";
    private static final String POLICY = "/buildReviewEvidence/stagedMirrorPolicy";
    private static final String HISTORY = "/currentCommandEvidence/sourceArchiveObservations";
    private static final String BUILD_PACKAGE = "gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.";
    private static final int PHYSICAL_LIMIT = 1_048_576;
    private static final int OUTPUT_RESERVE = 65_536;
    private static final int ORIGINAL_FIXED_UTF8 = 74_848;
    private static final int R97_FIXED_UTF8 = 76_715;
    private static final int R97_FIRST_CAP = 497_463;
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
                var boundArchive = archiveFacts(fixture, archive, names, command);
                // Same product shape and meaning, with public fixture identities. The original R97 replies were not saved.
                var history = Map.of("archives", List.of(boundArchive.get("archive")), "meaning",
                    "Archiver-produced frozen source/subtree associations captured before final confirmation under this original task/server. sourceToolchainRequirements preserve the deterministic selected component declarations/constraints/source files, independent of the initial draft URI/git field; they are not a resolved future installer result or installation proof. Source manifest and compressed archive hashes identify different objects; match the build envelope's expected archive SHA to its association and declared candidate. Listed companion files belong to that archived subtree; file lists and recent history (32 archives) are explicitly bounded, not a complete content/safety review. The deterministic build body is separately reviewed, not required to be a file or signature of the source manifest. It verifies actual compressed archive bytes before extraction; project hooks still require non-root candidate isolation, complete executable input and fresh independent host permission. These observations do not prove live remote state, safe content or authorization.");
                var current = Map.<String, Object>of("currentBuildArchive", boundArchive,
                    "currentBuildArchiveSource", sources, "sourceArchiveObservations", history);
                facts.put("currentCommandEvidence", current);
                fixture.model.nativeReply(deny());
                var decision = new DeploymentAiJson(assisted.context).askCommand(
                    DeploymentFixedCommandApproval.commandReviewInstructions(true) + DeploymentAssistedScope.standardBuildReviewInstructions(),
                    facts, fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed,
                    command.intent(), command.request());
                assertEquals("DENY", decision.path("decision").asText());
                assertEquals(0, assisted.hosts.get());
                String original = DeploymentAiJson.facts(facts, PHYSICAL_LIMIT);
                var evidence = DeploymentCommandSourceEvidence.project(original,
                    command.intent(), command.request(), fixture.layout, Optional.of(sources)).orElseThrow();
                var manifest = JSON.readTree(evidence.messages().getLast().text()).path("buildReviewEvidence");
                String policy = DeploymentMirrorTasks.policy(fixture.task);
                var expected = new LinkedHashMap<String, String>();
                expected.put(RUNTIME, DeploymentMirrorRuntime.runtimeSource());
                expected.put(SHELL, DeploymentMirrorRuntime.bootstrap(policy).shell());
                expected.put(POLICY, policy);
                expected.put(HISTORY, JSON.writeValueAsString(JSON.readTree(original).at(HISTORY)));
                for (var member : manifest.at("/projectArchive/members"))
                    expected.put(member.path("sourcePointer").asText(), Files.readString(root.resolve("backend").resolve(member.path("path").asText())));
                assertEquals(List.of(new DeploymentSourceArchiveReview.Omitted("go.sum", 3_926, "PROTECTED_CONTENT")), sources.omitted());
                assertEquals(JSON.readTree(JSON.writeValueAsBytes(sources.omitted())), manifest.at("/projectArchive/omitted"));
                assertEquals(13, manifest.at("/projectArchive/members").size() + sources.omitted().size());
                assertCeilings(fixture.model.requests.getFirst(), evidence, expected);
                assertNoHostGrantForIncompleteOrRejectedEvidence(fixture, assisted, command, current, expected);
                var sent = new ArrayList<byte[]>();
                var approved = DeploymentFixedCommandApproval.approveWithEvidence(
                    reviewContext(fixture, assisted.context, "COMPLETE", sent), assisted.host, command.intent(), command.request(),
                    fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false, (action, request, task) -> current);
                assertEquals(TaskResult.SUCCEEDED, approved.outcome());
                assertEquals(1, sent.size());
                String frame = retainedFrame(sent.getFirst());
                int end = frame.indexOf('\n', FRAME.length());
                assertCompleteSource(sent.getFirst(), JSON.readTree(frame.substring(FRAME.length(), end)).path("snapshotSha256").asText(), expected);
                assertEquals(1, assisted.hosts.get());
                assertEquals(java.util.Set.of(approved.value().orElseThrow().id()), assisted.grants);
                assertEquals(List.of(command.intent()), assisted.grantedIntents);
                System.out.printf("R99_FILE_TRANSFER_HOST_APPROVE wire=%d cap=%d grants=%d%n", sent.getFirst().length, R97_FIRST_CAP, assisted.hosts.get());
            }
        }
    }

    private static void assertCeilings(AiRequest template, DeploymentCommandSourceEvidence evidence,
                                       Map<String, String> expected) throws Exception {
        var fixed = new ArrayList<>(evidence.messages());
        fixed.addFirst(template.messages().getFirst());
        int unpaddedFixed = utf8(fixed);
        assertTrue(unpaddedFixed < R97_FIXED_UTF8);
        fixed.add(fixed.size() - 1, new AiMessage(AiRole.USER, "x".repeat(R97_FIXED_UTF8 - unpaddedFixed), List.of()));
        assertEquals(R97_FIXED_UTF8, utf8(fixed));
        var manifest = JSON.readTree(fixed.getLast().text()).path("buildReviewEvidence");
        String snapshot = manifest.path("snapshotSha256").textValue();
        assertNotNull(snapshot);
        assertEquals(ARCHIVE, manifest.at("/projectArchive/archiveSha256").asText());
        assertTrue(manifest.path("selectedRuntimeRanges").findValuesAsText("section").contains("goDependencyEnvironment"));
        assertFalse(manifest.has("bootstrapShellLiteral"));
        assertEquals(JSON.valueToTree(List.of(RUNTIME, SHELL, POLICY, HISTORY)), manifest.path("requiredBuildSources"));
        for (String pointer : List.of(RUNTIME, SHELL, POLICY, HISTORY)) {
            var descriptor = pointer.equals(RUNTIME) ? JSON.readTree(fixed.getLast().text()).at(RUNTIME)
                : manifest.path(pointer.equals(SHELL) ? "mirrorBootstrapShell" : pointer.equals(POLICY) ? "stagedMirrorPolicy" : "sourceArchiveHistory");
            assertEquals(pointer, descriptor.path("sourcePointer").asText());
            assertEquals(DeploymentEvidencePages.sha256(expected.get(pointer)), descriptor.path("sha256").asText());
        }
        var paged = new ArrayList<JsonNode>();
        manifest.at("/projectArchive/members").forEach(member -> {
            if (member.path("initiallyPaged").asBoolean()) paged.add(member);
        });
        assertEquals(1, paged.size());
        var member = paged.getFirst();
        var initialMessages = new ArrayList<>(fixed);
        var initialReader = evidence.reader(32);
        initialReader.offerInitialSource(initialMessages);
        byte[] initialWire = encode(template, initialMessages, R97_FIRST_CAP);
        assertCompleteSource(initialWire, snapshot, expected);
        initialReader.read(responses(List.of(approve())));
        assertTrue(initialReader.approvalEvidenceBlock().isEmpty());
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
        int rawFixed = utf8(fixed);
        var required = replay(template, evidence, fixed, snapshot, expected, memberReads, R97_FIRST_CAP);
        assertTrue(required.complete());
        var full = replay(template, evidence, fixed, snapshot, expected, reads, R97_FIRST_CAP);
        assertTrue(full.complete());
        assertTrue(full.wireBytes() > fixedWire);
        var observed = List.of(new Observed(260_596, 257_995, 0), new Observed(194_756, 193_196, 1), new Observed(146_377, 145_927, 20));
        assertTrue(full.wireBytes() > 194_756);
        for (var limit : observed) {
            var sent = replay(template, evidence, fixed, snapshot, expected, reads, limit.cap());
            if (full.wireBytes() <= limit.cap()) assertTrue(sent.complete());
            else assertFalse(sent.complete());
            System.out.printf("R99_FILE_TRANSFER_REPLAY cap=%d archive=%s fixedUtf8=%d unpaddedFixedUtf8=%d r90FixedUtf8=%d fixedWire=%d initialWire=%d requiredWire=%d uncappedCompleteWire=%d "
                    + "sent=%d missing=%d complete=%s originalSent=%d originalMissing=%d%n",
                limit.cap(), ARCHIVE, rawFixed, unpaddedFixed, ORIGINAL_FIXED_UTF8, fixedWire, initialWire.length, required.wireBytes(), full.wireBytes(), sent.wireBytes(), sent.missing(),
                sent.complete(), limit.sent(), limit.missing());
        }
        assertFalse(replay(template, evidence, fixed, snapshot, expected, reads, fixedWire + 4_000).complete());
    }

    private static Replay replay(AiRequest template, DeploymentCommandSourceEvidence evidence, List<AiMessage> fixed,
                                 String snapshot, Map<String, String> expected, List<Cursor> reads, int cap) throws Exception {
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
            assertCompleteSource(encode(template, messages, cap), snapshot, expected);
        }
        assertTrue(sent <= cap);
        return new Replay(sent, header.path("pendingRequired").size(), complete);
    }

    private static JsonNode assertCompleteSource(byte[] wire, String snapshot, Map<String, String> expected) throws Exception {
        String frame = retainedFrame(wire);
        int headerEnd = frame.indexOf('\n', FRAME.length());
        var header = JSON.readTree(frame.substring(FRAME.length(), headerEnd));
        assertEquals(snapshot, header.path("snapshotSha256").asText());
        var pieces = new LinkedHashMap<String, java.util.TreeMap<Integer, String>>();
        int position = headerEnd + 1;
        for (var range : header.path("ranges")) {
            int offset = range.path("offset").asInt(), length = range.path("next").asInt() - offset;
            String pointer = range.path("pointer").asText();
            assertTrue(expected.containsKey(pointer), pointer);
            String text = frame.substring(position, position + length);
            assertEquals(expected.get(pointer).substring(offset, offset + length), text, pointer);
            assertNull(pieces.computeIfAbsent(pointer, ignored -> new java.util.TreeMap<>()).put(offset, text));
            position += length;
        }
        assertEquals(frame.length(), position);
        for (var source : expected.entrySet()) {
            var assembled = new StringBuilder();
            for (var piece : pieces.getOrDefault(source.getKey(), new java.util.TreeMap<>()).entrySet()) {
                assertEquals(assembled.length(), piece.getKey(), source.getKey());
                assembled.append(piece.getValue());
            }
            assertEquals(source.getValue(), assembled.toString(), source.getKey());
            assertEquals(DeploymentEvidencePages.sha256(source.getValue()), DeploymentEvidencePages.sha256(assembled.toString()), source.getKey());
        }
        return header;
    }

    private static String retainedFrame(byte[] wire) throws Exception {
        for (var message : JSON.readTree(wire).path("input")) {
            String text = message.at("/content/0/text").asText();
            if (text.startsWith(FRAME)) return text;
        }
        throw new AssertionError("Native request has no current source frame");
    }

    private static int utf8(List<AiMessage> messages) {
        return messages.stream().mapToInt(message -> message.text().getBytes(StandardCharsets.UTF_8).length).sum();
    }

    private static void assertNoHostGrantForIncompleteOrRejectedEvidence(MirroredInstallerTestFixture fixture,
            DeploymentNativeHelperPackageImpactTest.Assisted assisted, AssetLendingBuildReviewFixture.Captured command,
            Map<String, Object> current, Map<String, String> expected) throws Exception {
        for (String failure : List.of("INCOMPLETE", "AGED_OUT", "SNAPSHOT", "POINTER")) {
            var sent = new ArrayList<byte[]>();
            var context = reviewContext(fixture, assisted.context, failure, sent);
            var denied = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approveWithEvidence(
                context, assisted.host, command.intent(), command.request(), fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, (action, request, task) -> current), failure);
            assertTrue(denied.problem().eventCode().contains("REVIEW"), denied.problem().toString());
            assertEquals(failure.equals("INCOMPLETE") ? 1 : 2, sent.size(), failure);
            assertEquals(0, assisted.hosts.get(), failure);
            assertTrue(assisted.grants.isEmpty(), failure);
            if (failure.equals("AGED_OUT")) {
                String firstFrame = retainedFrame(sent.getFirst());
                int end = firstFrame.indexOf('\n', FRAME.length());
                String snapshot = JSON.readTree(firstFrame.substring(FRAME.length(), end)).path("snapshotSha256").asText();
                assertCompleteSource(sent.getFirst(), snapshot, expected);
                String lastFrame = retainedFrame(sent.getLast());
                int lastEnd = lastFrame.indexOf('\n', FRAME.length());
                assertFalse(JSON.readTree(lastFrame.substring(FRAME.length(), lastEnd)).path("pendingRequired").isEmpty());
            }
            System.out.printf("R99_FILE_TRANSFER_HOST_BLOCK case=%s sent=%d firstWire=%d lastWire=%d event=%s grants=%d%n",
                failure, sent.size(), sent.getFirst().length, sent.getLast().length, denied.problem().eventCode(), assisted.hosts.get());
        }
    }

    private static ModContext reviewContext(MirroredInstallerTestFixture fixture, ModContext original,
                                            String failure, List<byte[]> sent) {
        var loader = DeploymentR90FileTransferBudgetReplayTest.class.getClassLoader();
        var service = Proxy.newProxyInstance(loader, new Class<?>[]{AiService.class}, (proxy, method, args) -> {
            if (method.getName().equals("call")) {
                var request = (AiRequest) args[0];
                int cap = failure.equals("INCOMPLETE") || failure.equals("AGED_OUT") && !sent.isEmpty() ? 194_756 : R97_FIRST_CAP;
                byte[] encoded;
                try {
                    encoded = encode(request, request.messages(), cap);
                } catch (ModelRequestBudgetException exceeded) {
                    throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Controlled native encoder bound",
                        Map.of("maximumRequestBytes", Integer.toString(cap), "encodedRequestBytes", Integer.toString(exceeded.encodedBytes())), Optional.empty()));
                }
                var manifest = JSON.readTree(request.messages().getLast().text()).path("buildReviewEvidence");
                String snapshot = manifest.path("snapshotSha256").asText();
                AiToolCall call;
                if (failure.equals("COMPLETE") || failure.equals("INCOMPLETE") || !sent.isEmpty()) call = approve();
                else call = new AiToolCall("controlled-" + failure, "evidence", JSON.writeValueAsString(Map.of(
                    "snapshotSha256", failure.equals("SNAPSHOT") ? "0".repeat(64) : snapshot,
                    "pointer", failure.equals("POINTER") ? HISTORY + "/missing" : HISTORY, "offset", 0, "limit", 100)));
                sent.add(encoded);
                fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(responses(List.of(call))), Optional.empty()));
            }
            try { return method.invoke(original.ai(), args); }
            catch (InvocationTargetException rejected) { throw rejected.getCause(); }
        });
        return (ModContext) Proxy.newProxyInstance(loader, new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("ai")) return service;
            try { return method.invoke(original, args); }
            catch (InvocationTargetException rejected) { throw rejected.getCause(); }
        });
    }

    private static int wire(AiRequest template, List<AiMessage> messages, int cap) throws Exception {
        return encode(template, messages, cap).length;
    }

    private static byte[] encode(AiRequest template, List<AiMessage> messages, int cap) throws Exception {
        var request = new AiRequest(template.purpose(), messages, template.maxOutputTokens(), template.timeout(),
            template.jsonObjectResponse(), template.tools(), cap, template.requiredToolName(), template.generationRecoveryAttempts(),
            Optional.of("gpt-6-luna"), Optional.of(AiReasoningMode.HIGH));
        return ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES)
            .encode("gpt-6-luna", request, OUTPUT_RESERVE, ModelReasoningLevel.HIGH, Map.of());
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

    private static AiToolCall approve() {
        return new AiToolCall("offline-approval", "submit_result",
            "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled independent verdict\"}");
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

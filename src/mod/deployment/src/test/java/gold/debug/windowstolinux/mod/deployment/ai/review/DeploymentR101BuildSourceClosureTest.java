package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentCargoBuildInput;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.*;
import java.io.OutputStream;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Public-input reconstruction, not the unrecorded historical model wire or a live model replay. */
@Timeout(120)
class DeploymentR101BuildSourceClosureTest {
    @FunctionalInterface interface Reply { List<AiToolCall> apply(JsonNode manifest) throws Exception; }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LOG_CAP = 890_659;
    private static final int DIRECTORY_FIRST_CAP = 1_027_578;
    private static final int DIRECTORY_AFTER_READ_CAP = 760_195;
    private static final int PHYSICAL_CAP = 1_048_576;
    @TempDir Path temporary;

    @Test
    void logCompleteSourceUsesActualNativeEncoderAndLoopbackAtOriginalFirstCeiling() throws Exception {
        try (var fixture = LogAnalyzerBuildReviewFixture.mirrorFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var material = stage("log-analyzer");
            var archive = archive(material, fixture.task);
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            try (var build = LogAnalyzerBuildReviewFixture.render(fixture, material, archive, captured);
                 var nativeReview = new NativeReview(fixture, assisted.context, new int[]{LOG_CAP}, 77_567, manifest -> List.of(verdict("APPROVE")))) {
                var result = approve(nativeReview.context, assisted, fixture, build.intent(), build.request(), build.facts());
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), () -> result.toString());
                assertEquals(1, assisted.hosts.get());
                assertEquals(1, nativeReview.received.size());
                verify(nativeReview.received.getFirst(), build.request(), fixture, captured);
                assertTrue(nativeReview.received.getFirst().length <= LOG_CAP);
                System.out.printf("R101_LOG_NATIVE_RECONSTRUCTION cap=%d bytes=%d loopback=%d grants=%d originalWireUnavailable=true%n",
                    LOG_CAP, nativeReview.received.getFirst().length, nativeReview.received.size(), assisted.hosts.get());
            }
        }
    }

    @Test
    void directoryRebasePreservesCompleteHeaderRuntimeShellAndPolicy() throws Exception {
        runDirectory(false, false);
    }

    @Test
    void directoryOriginalDecreasingCapsNeverDispatchAnIncompleteFallback() throws Exception {
        runDirectory(true, false);
    }

    @Test
    void explicitDenyAtACompleteFrameGrantsNothing() throws Exception {
        runDirectory(false, true);
    }

    private void runDirectory(boolean originalCaps, boolean deny) throws Exception {
        try (var fixture = DirectoryDiffBuildReviewFixture.mirrorFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var material = stage("directory-diff");
            var archive = archive(material, fixture.task);
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            int[] caps = originalCaps ? new int[]{DIRECTORY_FIRST_CAP, DIRECTORY_AFTER_READ_CAP} : new int[]{PHYSICAL_CAP, PHYSICAL_CAP};
            try (var build = DirectoryDiffBuildReviewFixture.render(fixture, material, archive, captured);
                 var nativeReview = new NativeReview(fixture, assisted.context, caps, 79_048, manifest -> {
                     if (deny) return List.of(verdict("DENY"));
                     if (!manifest.path("_readIssued").asBoolean()) return directoryReads(manifest);
                     return List.of(verdict("APPROVE"));
                 })) {
                OperationResult<?> result = null;
                ApiException budget = null;
                try { result = approve(nativeReview.context, assisted, fixture, build.intent(), build.request(), build.facts()); }
                catch (ApiException refused) { if (!originalCaps && !deny) throw refused; budget = refused; }
                for (byte[] bytes : nativeReview.received) verify(bytes, build.request(), fixture, captured);
                if (deny) {
                    assertNotNull(budget);
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", budget.problem().eventCode());
                    assertEquals(0, assisted.hosts.get());
                } else if (originalCaps && !nativeReview.refused.isEmpty()) {
                    assertNotNull(budget);
                    assertEquals("AI_REQUEST_BUDGET", budget.problem().eventCode());
                    assertEquals(0, assisted.hosts.get());
                    assertTrue(nativeReview.received.size() <= 1, "No incomplete second request reaches loopback");
                } else {
                    assertNotNull(result);
                    assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                    assertEquals(1, assisted.hosts.get());
                    assertEquals(2, nativeReview.received.size());
                    assertTrue(DeploymentSourceFrameDecoder.decode(nativeReview.received.getLast()).header().has("latestReads"));
                }
                System.out.printf("R101_DIRECTORY_NATIVE_RECONSTRUCTION originalCaps=%s deny=%s received=%s refused=%s grants=%d originalSeventhPointerUnknown=true originalWireUnavailable=true%n",
                    originalCaps, deny, nativeReview.received.stream().map(value -> Integer.toString(value.length)).toList(),
                    nativeReview.refused, assisted.hosts.get());
            }
        }
    }

    @Test
    void insufficientNativeBudgetIsLocalAndHasZeroLoopbackOrHostGrant() throws Exception {
        try (var fixture = LogAnalyzerBuildReviewFixture.mirrorFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var material = stage("log-analyzer");
            var archive = archive(material, fixture.task);
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            try (var build = LogAnalyzerBuildReviewFixture.render(fixture, material, archive, captured);
                 var nativeReview = new NativeReview(fixture, assisted.context, new int[]{50_000}, 77_567, manifest -> List.of(verdict("APPROVE")))) {
                var refusal = assertThrows(ApiException.class,
                    () -> approve(nativeReview.context, assisted, fixture, build.intent(), build.request(), build.facts()));
                assertEquals("AI_REQUEST_BUDGET", refusal.problem().eventCode());
                assertFalse(nativeReview.refused.isEmpty());
                assertTrue(nativeReview.received.isEmpty());
                assertEquals(0, assisted.hosts.get());
            }
        }
    }

    @Test
    void crossSnapshotReadCannotBeReplacedByPriorCompleteEvidence() throws Exception {
        try (var fixture = LogAnalyzerBuildReviewFixture.mirrorFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var material = stage("log-analyzer");
            var archive = archive(material, fixture.task);
            var captured = DeploymentSourceArchiveReview.capture(material, archive, fixture.task);
            try (var build = LogAnalyzerBuildReviewFixture.render(fixture, material, archive, captured);
                 var nativeReview = new NativeReview(fixture, assisted.context, new int[]{PHYSICAL_CAP}, 77_567, manifest ->
                     manifest.path("_readIssued").asBoolean() ? List.of(verdict("APPROVE")) : List.of(read("wrong-snapshot", "0".repeat(64),
                         "/buildReviewEvidence/stagedMirrorPolicy", 0, 100)))) {
                assertThrows(ApiException.class,
                    () -> approve(nativeReview.context, assisted, fixture, build.intent(), build.request(), build.facts()));
                assertEquals(0, assisted.hosts.get());
                assertTrue(nativeReview.received.size() >= 1);
            }
        }
    }

    private static List<AiToolCall> directoryReads(JsonNode manifest) throws Exception {
        String snapshot = manifest.path("snapshotSha256").asText();
        var calls = new ArrayList<AiToolCall>();
        for (int offset = 0; offset <= 32_000; offset += 8000)
            calls.add(read("original-known-shell-" + offset, snapshot, "/buildReviewEvidence/mirrorBootstrapShell", offset, offset == 32_000 ? 7000 : 8000));
        calls.add(read("original-known-policy", snapshot, "/buildReviewEvidence/stagedMirrorPolicy", 0, 6000));
        return calls;
    }

    private static AiToolCall read(String id, String snapshot, String pointer, int offset, int limit) throws Exception {
        return new AiToolCall(id, "evidence", JSON.writeValueAsString(Map.of("snapshotSha256", snapshot, "pointer", pointer, "offset", offset, "limit", limit)));
    }

    private static AiToolCall verdict(String decision) {
        return new AiToolCall("verdict-" + UUID.randomUUID(), "submit_result", "{\"decision\":\"" + decision + "\",\"reason\":\"Independent public-input fixture verdict\",\"requiresUserConfirmation\":false}");
    }

    static OperationResult<?> approve(ModContext context, DeploymentNativeHelperPackageImpactTest.Assisted assisted,
                                              MirroredInstallerTestFixture fixture, ActionIntent intent, CommandRequest request,
                                              Map<String, Object> facts) throws Exception {
        @SuppressWarnings("unchecked") var current = (Map<String, Object>) facts.get("currentCommandEvidence");
        return DeploymentFixedCommandApproval.approveWithEvidence(context, assisted.host, intent, request, fixture.task,
            fixture.model.configurationRevision, "AUTOMATIC", false, (action, command, task) -> current);
    }

    static void verify(byte[] wire, CommandRequest request, MirroredInstallerTestFixture fixture,
                               DeploymentSourceArchiveReview.Evidence captured) throws Exception {
        var frame = DeploymentSourceFrameDecoder.decode(wire);
        var manifest = frame.facts().path("buildReviewEvidence");
        assertEquals(captured.archiveSha256(), manifest.at("/projectArchive/archiveSha256").asText());
        var byPath = new HashMap<String, String>();
        for (var member : captured.members()) byPath.put(member.path(), member.text() != null ? member.text() : captured.pagedBodies().get(member.path()));
        for (var member : manifest.at("/projectArchive/members"))
            frame.whole(member.path("sourcePointer").asText(), byPath.get(member.path("path").asText()));
        String stdin = new String(request.stdin(), StandardCharsets.UTF_8);
        var cargo = DeploymentCargoBuildInput.phases(stdin);
        String policy, runtime, body;
        if (cargo.isPresent()) {
            var data = DeploymentCargoBuildInput.prefetch(cargo.orElseThrow());
            policy = data.policy(); runtime = data.runtime(); body = cargo.orElseThrow().build();
            for (var field : DeploymentManagedHelperBundle.cargoBuildPrefetchFacts(fixture.layout).entrySet()) {
                var descriptor = manifest.path("cargoPrefetchImplementation").path(field.getKey());
                if (descriptor.has("sourcePointer")) frame.whole(descriptor.path("sourcePointer").asText(), field.getValue().toString());
            }
        } else {
            var data = DeploymentMirrorTasks.buildInput(stdin).orElseThrow();
            policy = data.policy(); runtime = DeploymentMirrorRuntime.runtimeSource(); body = data.original();
            var descriptor = manifest.path("mirrorBootstrapShell");
            int start = stdin.indexOf("\nwtl_source_providers() {\n") + 1;
            assertTrue(start > 0);
            String shell = stdin.substring(start, start + descriptor.path("characters").asInt());
            String restored = frame.whole(descriptor.path("sourcePointer").asText(), shell);
            var reference = manifest.path("bootstrapShellLiteral");
            if (!reference.isMissingNode()) {
                var source = reference.path("source");
                String literal = JSON.writeValueAsString(restored.substring(source.path("offset").asInt(), source.path("next").asInt()));
                var target = reference.path("target");
                assertEquals(target.path("sha256").asText(), DeploymentSourceFrameDecoder.sha(literal));
                frame.parts().computeIfAbsent(target.path("pointer").asText(), unused -> new TreeMap<>()).put(target.path("offset").asInt(), literal);
            }
        }
        frame.whole("/mirrorTransport/runtimeSource", runtime);
        frame.whole(manifest.at("/stagedMirrorPolicy/sourcePointer").asText(), policy);
        frame.whole(manifest.at("/buildBody/sourcePointer").asText(), body);
        assertEquals(manifest.at("/buildBody/sha256").asText(), DeploymentSourceFrameDecoder.sha(body));
    }

    private record Snapshot(UUID id, String sha256, Path directory) implements FileSnapshot { public void close() { } }

    private DeploymentSourceMaterial stage(String kind) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("test/multi-language/success-" + kind))) root = root.getParent();
        assertNotNull(root);
        Path original = root.resolve("test/multi-language/success-" + kind);
        Path destination = Files.createTempDirectory(temporary, kind + "-").toRealPath();
        var names = new ArrayList<String>();
        try (var paths = Files.walk(original)) {
            for (Path source : paths.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = original.relativize(source), target = destination.resolve(relative);
                Files.createDirectories(target.getParent()); Files.copy(source, target);
                names.add(relative.toString().replace('\\', '/'));
            }
        }
        Path declaration = destination.resolve("windowstolinux-application.properties");
        String text = Files.readString(declaration).replaceAll("(?m)^verification\\.arg\\.[0-9]+=.*\\R?", "").replaceAll("(?m)^expectedOutput=.*\\R?", "");
        text += kind.equals("log-analyzer") ? "verification.arg.0=--input\nverification.arg.1=samples/events.log\nverification.arg.2=--format\nverification.arg.3=json\nexpectedOutput=\"matched\": 6\n"
            : "verification.arg.0=--format\nverification.arg.1=json\nverification.arg.2=snapshot\nverification.arg.3=--root\nverification.arg.4=samples/baseline\nverification.arg.5=--name\nverification.arg.6=live-baseline\nexpectedOutput=\"status\": \"complete\"\n";
        Files.writeString(declaration, text);
        return new DeploymentSourceMaterial(kind, new Snapshot(UUID.randomUUID(), "a".repeat(64), destination), names, List.of());
    }

    private DeploymentSourceArchive archive(DeploymentSourceMaterial material, TaskContext task) throws Exception {
        Path output = Files.createTempDirectory(temporary, "archive-"); Path tar = output.resolve("source.tar.gz");
        var method = Class.forName("gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceTar")
            .getDeclaredMethod("write", DeploymentSourceMaterial.class, String.class, OutputStream.class, TaskContext.class);
        method.setAccessible(true); long total;
        try (var stream = Files.newOutputStream(tar)) { total = (long) method.invoke(null, material, "", stream, task); }
        byte[] bytes = Files.readAllBytes(tar);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new DeploymentSourceArchive(new Snapshot(UUID.randomUUID(), "b".repeat(64), output), sha, bytes.length, total,
            material.snapshot().id(), material.snapshot().sha256(), "");
    }

    static final class NativeReview implements AutoCloseable {
        final List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
        final List<String> refused = new ArrayList<>();
        final List<AiRequest> encodedRequests = new ArrayList<>();
        final ModContext context;
        final HttpServer loopback;
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        final List<String> handlerEvents = Collections.synchronizedList(new ArrayList<>());
        int calls;

        NativeReview(MirroredInstallerTestFixture fixture, ModContext original, int[] caps, int fixedFloor,
                     Reply replies) throws Exception {
            loopback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            loopback.setExecutor(executor);
            loopback.createContext("/public-native", exchange -> {
                try (exchange) {
                    String lengthText = exchange.getRequestHeaders().getFirst("Content-Length");
                    handlerEvents.add("ENTER method=" + exchange.getRequestMethod() + " contentLength=" + lengthText);
                    int length = Integer.parseInt(lengthText);
                    assertTrue(length > 0 && length <= PHYSICAL_CAP);
                    byte[] body = exchange.getRequestBody().readNBytes(length);
                    handlerEvents.add("BODY bytes=" + body.length);
                    assertEquals(length, body.length);
                    received.add(body);
                    byte[] acknowledgment = {79, 75};
                    exchange.sendResponseHeaders(200, acknowledgment.length);
                    exchange.getResponseBody().write(acknowledgment);
                    handlerEvents.add("RESPONSE200");
                } catch (Throwable failure) {
                    handlerEvents.add("ERROR " + failure);
                    failure.printStackTrace(System.out);
                }
            });
            loopback.start();
            var loader = getClass().getClassLoader();
            var ai = (AiService) Proxy.newProxyInstance(loader, new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                if (!method.getName().equals("call")) return invoke(method, original.ai(), args);
                var request = (AiRequest) args[0];
                int cap = caps[Math.min(calls, caps.length - 1)];
                var messages = new ArrayList<>(request.messages());
                int fixed = messages.stream().filter(message -> !message.text().startsWith("WTL-COMMAND-RETAINED-SOURCE-1\n"))
                    .mapToInt(message -> message.text().getBytes(StandardCharsets.UTF_8).length).sum();
                if (fixed < fixedFloor) messages.add(messages.size() - 1, new AiMessage(AiRole.USER, "x".repeat(fixedFloor - fixed), List.of()));
                var bounded = new AiRequest(request.purpose(), messages, request.maxOutputTokens(), request.timeout(), request.jsonObjectResponse(),
                    request.tools(), cap, request.requiredToolName(), request.generationRecoveryAttempts(), Optional.of("gpt-6-luna"), Optional.of(AiReasoningMode.HIGH));
                byte[] wire;
                try { wire = ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES).encode("gpt-6-luna", bounded, 65_536, ModelReasoningLevel.HIGH, Map.of()); }
                catch (ModelRequestBudgetException failure) {
                    refused.add("cap=" + cap + ",bytes=" + failure.encodedBytes());
                    throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Local production encoder bound",
                        Map.of("maximumRequestBytes", Integer.toString(cap), "encodedRequestBytes", Integer.toString(failure.encodedBytes())), Optional.empty()));
                }
                encodedRequests.add(bounded);
                receiveEncodedBody(wire, cap);
                var facts = JSON.readTree(request.messages().getLast().text());
                String manifestName = facts.has("buildReviewEvidence") ? "buildReviewEvidence"
                    : facts.has("toolchainReviewEvidence") ? "toolchainReviewEvidence" : "installerReviewEvidence";
                var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) facts.path(manifestName);
                manifest.put("_readIssued", calls++ > 0);
                var tools = replies.apply(manifest);
                var usage = new AiUsage(Optional.of(10L), Optional.of(2L), Optional.empty(), Optional.empty(), Optional.empty());
                var attempt = new AiGenerationAttempt(UUID.randomUUID(), 1, "public-loopback-fixture", "OPENAI_RESPONSES", TaskResult.SUCCEEDED,
                    "AI_GENERATION_COMPLETE", "NONE", 0L, usage, wire.length, 65_536);
                var response = new AiResponse("", "public-loopback-fixture", "tool_calls", usage, tools, Optional.of(new AiContinuation(UUID.randomUUID(), 64)),
                    Optional.empty(), Optional.empty(), List.of(attempt));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(response), Optional.empty());
            });
            context = (ModContext) Proxy.newProxyInstance(loader, new Class<?>[]{ModContext.class}, (proxy, method, args) ->
                method.getName().equals("ai") ? ai : invoke(method, original, args));
        }

        void receiveEncodedBody(byte[] wire, int cap) throws Exception {
                int before = received.size();
                System.out.printf("R101_HTTP_NATIVE_ENCODED bytes=%d cap=%d%n", wire.length, cap);
                var responseBody = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + loopback.getAddress().getPort() + "/public-native"))
                    .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(wire)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, responseBody.statusCode());
                assertArrayEquals(new byte[]{79, 75}, responseBody.body());
                assertEquals(before + 1, received.size());
                byte[] body = received.getLast();
                assertArrayEquals(wire, body);
                System.out.printf("R101_NATIVE_BODY_LOOPBACK transport=HTTP_1_1 bytes=%d cap=%d%n", body.length, cap);
        }

        private static Object invoke(Method method, Object receiver, Object[] args) throws Throwable {
            try { return method.invoke(receiver, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }

        public void close() throws Exception {
            loopback.stop(0); executor.shutdownNow(); client.shutdownNow();
            assertTrue(executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(client.awaitTermination(Duration.ofSeconds(2)));
            System.out.println("R101_HTTP_HANDLER " + handlerEvents);
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.recovery.DeploymentStandardTaskRecovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.recovery.DeploymentStandardPreparationDiagnosticRecovery;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps unknown health outside diagnosis and re-publication, then reconciles real receipts. / 未知健康不进入诊断与重发，随后以真实回执核对。 */
class DeploymentUnknownHealthPublishTest extends DeploymentPublishAcceptanceFixture {
    // Full local single/graph preparation and publication share this initial wait.
    private static final long FIRST_PUBLICATION_WAIT_SECONDS = 120;
    private static final int FINAL_RECONCILIATION_WAIT_SECONDS = 60;

    private final AtomicInteger graphDiagnosticSkips = new AtomicInteger();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        var original = super.interactionPrompts();
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(
            PlatformInteractionService.Prompts.class.getClassLoader(), new Class<?>[]{PlatformInteractionService.Prompts.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("text") && arguments.length == 5
                    && DeploymentStandardPreparationDiagnosticRecovery.GRAPH_SELECTION_TITLE.equals(arguments[0])
                    && DeploymentStandardPreparationDiagnosticRecovery.GRAPH_SELECTION_PROMPT.equals(arguments[1])
                    && Boolean.FALSE.equals(arguments[2])) {
                    assertNotNull(arguments[3]);
                    assertNotNull(arguments[4]);
                    var task = (TaskSnapshot) arguments[3];
                    assertEquals(OWNER, task.ownerId());
                    assertEquals(VERSION, task.ownerVersion());
                    assertEquals(DeploymentRefFlow.TASK_TYPE, task.type());
                    assertEquals(TaskState.UNKNOWN, task.state());
                    graphDiagnosticSkips.incrementAndGet();
                    return new char[0];
                }
                try { return method.invoke(original, arguments); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
    }

    private static Map<String, Object> scalarProjection(gold.debug.windowstolinux.appapi.task.run.TaskHandle<?> handle,
                                                       DeploymentPublishStatePeer peer) {
        var completion = handle.completion().toCompletableFuture();
        var result = completion.isDone() && !completion.isCompletedExceptionally() && !completion.isCancelled()
            ? completion.getNow(null) : null;
        var projection = new LinkedHashMap<String, Object>();
        projection.put("state", handle.snapshot().state().name());
        projection.put("completionDone", completion.isDone());
        projection.put("completionExceptional", completion.isCompletedExceptionally());
        projection.put("outcome", result == null ? "ABSENT" : result.outcome().name());
        projection.put("code", result == null ? "ABSENT" : result.problem().map(problem -> problem.code().name()).orElse("ABSENT"));
        projection.put("event", result == null ? "ABSENT" : result.problem().map(problem ->
            Set.of("CONTROLLED_HEALTH_UNKNOWN", "DEPLOYMENT_HEALTH_UNCONFIRMED", "DEPLOYMENT_HEALTH_RECEIPT_INVALID",
                "DEPLOYMENT_HEALTH_FAILED").contains(problem.eventCode()) ? problem.eventCode() : "UNLISTED").orElse("ABSENT"));
        projection.put("commands", peer.builds.commands.size());
        projection.put("mockFailureClass", peer.builds.lastFailure == null ? "NONE" : peer.builds.lastFailure.getClass().getName());
        return projection;
    }

    private static final String COMPATIBILITY_PROBE = """
        set -eu
        while IFS='=' read -r key value; do
          [ "$key" = ID ] || continue
          value="$(builtin printf '%s' "$value" | /usr/bin/tr -d '\\042\\047')"
          case "$value" in ''|*[!a-z0-9_-]*) exit 64;; esac
          builtin printf '%s\\n' "$value"
          exit 0
        done < /etc/os-release
        exit 69
        """;

    /** Only the current fixed distribution query is added; other stdin bodies remain rejected by the peer. */
    private static RemoteSession withFixedCompatibilityProbe(RemoteSession original) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("execute") && arguments[0] instanceof CommandRequest request
                    && request.script().equals("/bin/bash --noprofile --norc -s")
                    && java.util.Arrays.equals(request.stdin(), COMPATIBILITY_PROBE.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    assertNotNull(arguments[1]);
                    assertEquals(java.time.Duration.ofSeconds(10), request.timeout());
                    assertEquals(1024, request.outputLimitBytes());
                    return success(new CommandResult(Optional.of(0), "ubuntu\n", "", false));
                }
                try { return method.invoke(original, arguments); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unknownHealthRetainsOriginalResourcesWithoutAdviceOrReplay(int count) throws Exception {
        source(count);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var catalog = MirrorCatalog.load();
            var mirrorRows = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
            for (var point : DownloadPoint.values()) {
                var official = catalog.providersFor(point).stream().filter(provider -> provider.official()).toList();
                assertEquals(1, official.size());
                mirrorRows.put(point, List.of(new MirrorSettings.Row(official.getFirst().id(), "", false, false, "")));
            }
            assertEquals(TaskResult.SUCCEEDED, new DeploymentMirrorTasks(original).save(new MirrorSettings(1, mirrorRows, Map.of()))
                .completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentPublishStatePeer();
            peer.builds.unknownHealth = true;
            var language = new AtomicInteger();
            var diagnosis = new AtomicInteger();
            var publicationRequests = new AtomicInteger();
            AiService models = new AiService() {
                @Override
                public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                    gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                    throw new AssertionError("Voice is outside this fixture");
                }

                @Override
                public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse> call(
                    gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest request, String expectedRevision,
                    gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                    throw new AssertionError("Voice is outside this fixture");
                }

                @Override
                public gold.debug.windowstolinux.appapi.ai.voice.VoiceSession openVoice(
                    gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions options, gold.debug.windowstolinux.appapi.ai.voice.VoiceListener listener,
                    gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                    throw new AssertionError("Voice is outside this fixture");
                }

                public String configurationRevision(AiPurpose purpose, TaskContext task) {
                    return "a".repeat(64);
                }

                public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                    return call(request, "a".repeat(64), task);
                }

                public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task)
                    throws ApiException {
                    try {
                        return controlledResponse(request, expected, task);
                    } catch (RuntimeException | Error escaped) {
                        System.out.println("UNKNOWN_FIXTURE_MODEL_FAILURE count=" + count + " class=" + escaped.getClass().getName());
                        throw escaped;
                    }
                }

                private OperationResult<AiResponse> controlledResponse(AiRequest request, String expected, TaskContext task) {
                    assertEquals("a".repeat(64), expected);
                    String response;
                    if (request.purpose() == AiPurpose.APPROVAL)
                        response = "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Exact fixed action\"}";
                    else {
                        assertEquals(AiPurpose.LANGUAGE, request.purpose());
                        language.incrementAndGet();
                        try {
                            var facts = new ObjectMapper().readTree(request.messages().getLast().text());
                            boolean publication = facts.path("state").path("availableTools").isArray();
                            if (publication) publicationRequests.incrementAndGet();
                            System.out.println("UNKNOWN_FIXTURE_NATIVE " + new ObjectMapper().writeValueAsString(Map.of(
                                "count", count, "publicationRequests", publicationRequests.get(),
                                "stateAvailableToolsArray", publication, "topLevelToolsPresent", facts.has("tools"),
                                "submitResultOffered", request.tools().stream().anyMatch(tool -> tool.name().equals("submit_result")))));
                            if (facts.has("projectTypes"))
                                response = DeploymentAssistedGoModelFixture.sourceScan(facts, count);
                            else if (publication)
                                response = DeploymentAssistedGoModelFixture.publicationTool(request, facts);
                            else if (Set.of("BUILD", "ROLLBACK_VERIFIED").contains(facts.path("phase").asText())) {
                                diagnosis.incrementAndGet();
                                throw new AssertionError("Unknown health must never authorize diagnosis");
                            } else {
                                DeploymentJsonFields.exact(facts, Set.of("sourceRevision", "facts", "fields", "observations",
                                    "evidenceReferences", "feedback", "remaining"));
                                assertTrue(facts.path("sourceRevision").isTextual()
                                    && facts.path("sourceRevision").textValue().matches("[0-9a-f]{64}")
                                    && facts.path("facts").isObject() && facts.path("fields").isArray()
                                    && facts.path("observations").isArray() && facts.path("evidenceReferences").isArray()
                                    && facts.path("feedback").isObject(), "Only the current source-analysis request can receive ADVISE");
                                DeploymentJsonFields.number(facts, "remaining", 1, 12);
                                assertEquals(List.of("submit_result"), request.tools().stream().map(tool -> tool.name()).toList());
                                response = "{\"action\":\"ADVISE\",\"sourceRevision\":\""
                                    + facts.path("sourceRevision").asText()
                                    + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Retain human inputs\",\"unresolved\":[]}}";
                            }
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                    try {
                        return success(DeploymentAssistedGoModelFixture.nativeResponse(request, "controlled-purpose", response));
                    } catch (Exception invalidFixtureResponse) {
                        throw new AssertionError(invalidFixtureResponse);
                    }
                }
            };
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                    return withFixedCompatibilityProbe(peer.session(task));
                }

                public RemoteSession connect(RemoteTarget expected, TaskContext task) throws ApiException {
                    assertEquals(new ServerId("server"), expected.server());
                    assertEquals(task.id(), expected.taskId());
                    assertEquals("127.0.0.1", expected.host());
                    assertEquals(22, expected.port());
                    assertEquals("root", expected.username());
                    var server = original.servers().get(expected.server(), task);
                    assertEquals(server.ref(), expected.server());
                    assertEquals(server.host(), expected.host());
                    assertEquals(server.port(), expected.port());
                    assertEquals(server.username(), expected.username());
                    var session = peer.session(task);
                    try {
                        if (expected.fingerprint().isPresent()
                            && !expected.fingerprint().orElseThrow().equals(session.facts().fingerprint()))
                            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                                "DEPLOYMENT_TEST_WIRE_CHANGED",
                                "The controlled transport rejects a different actual wire before any command",
                                Map.of(), Optional.of(task.id())));
                        return withFixedCompatibilityProbe(session);
                    } catch (ApiException | RuntimeException | Error failure) {
                        try {
                            session.close();
                        } catch (ApiException closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                        throw failure;
                    }
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    return List.of();
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("tasks"))
                        return tasks;
                    if (method.getName().equals("linux"))
                        return linux;
                    if (method.getName().equals("ai"))
                        return models;
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var flow = new DeploymentRefFlow(bound, new DeploymentStandardPublishRecovery(bound));
            try (var registration = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, new DeploymentStandardTaskRecovery(bound, flow, new DeploymentStandardAssembly()))) {
                var handle = new DeploymentStandardEntryFixture(bound, flow).submit(new ServerId("server"),
                    new DeploymentStandardInputs.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(count),
                    true, "MANUAL_REVIEW");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(FIRST_PUBLICATION_WAIT_SECONDS);
                while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED
                    && System.nanoTime() < until)
                    Thread.sleep(20);
                System.out.println("UNKNOWN_FIXTURE_STATE " + new ObjectMapper().writeValueAsString(scalarProjection(handle, peer)));
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(),
                    () -> scalarProjection(handle, peer).toString());
                assertTrue(peer.builds.healthChecks > 0, () -> "Controlled health was not reached; " + scalarProjection(handle, peer));
                assertFalse(peer.builds.present.isEmpty());
                assertFalse(peer.maintenance.isEmpty());
                assertEquals(0, diagnosis.get());
                assertEquals(0, corrections);
                assertTrue(scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                var builds = Map.copyOf(peer.builds.builds);
                var creates = Map.copyOf(peer.builds.creates);
                var uploads = Map.copyOf(peer.builds.uploads);
                var publications = List.copyOf(peer.builds.sentPublications);
                int calls = language.get();
                assertEquals(TaskState.UNKNOWN,
                    reconcileReady(runtime, handle).state());
                assertEquals(builds, peer.builds.builds);
                assertEquals(creates, peer.builds.creates);
                assertEquals(uploads, peer.builds.uploads);
                assertEquals(publications, peer.builds.sentPublications);
                assertEquals(calls, language.get());
                assertEquals(0, diagnosis.get());
                assertEquals(0, corrections);
                peer.builds.unknownHealth = false;
                var ended = reconcileReady(runtime, handle, FINAL_RECONCILIATION_WAIT_SECONDS);
                System.out.println("UNKNOWN_FIXTURE_STATE " + new ObjectMapper().writeValueAsString(scalarProjection(handle, peer)));
                assertEquals(TaskState.ENDED, ended.state(), () -> scalarProjection(handle, peer).toString());
                System.out.println("UNKNOWN_FIXTURE_GRAPH_SELECTION count=" + count + " accepted=" + graphDiagnosticSkips.get());
                assertEquals(Optional.of(count == 1 ? TaskResult.SUCCEEDED : TaskResult.FAILED), ended.outcome());
                assertEquals(builds, peer.builds.builds);
                assertEquals(creates, peer.builds.creates);
                assertEquals(uploads, peer.builds.uploads);
                assertEquals(publications, peer.builds.sentPublications);
                assertEquals(calls, language.get());
                assertEquals(0, diagnosis.get());
                assertEquals(0, corrections);
                assertEquals(peer.builds.current.keySet(), peer.running);
                assertEquals(count == 1 ? 1 : 0, peer.running.size());
                if (count == 2) {
                    assertEquals(1, publications.size(), "Unknown first-component health cannot publish the second component during recovery");
                    assertTrue(peer.builds.current.isEmpty());
                    assertEquals(1, peer.rollbacks, "Only the actually published original component is rolled back");
                }
                try (var access = scope(runtime, true); var connection = access.openConnection(); var statement = connection.createStatement()) {
                    try (var rows = statement.executeQuery("SELECT outcome,business_committed,cleanup_complete FROM deployment_execution")) {
                        assertTrue(rows.next());
                        assertEquals(count == 1 ? "SUCCEEDED" : "FAILED", rows.getString(1));
                        assertEquals(count == 1, rows.getBoolean(2));
                        assertTrue(rows.getBoolean(3));
                        assertFalse(rows.next());
                    }
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_unit WHERE current_release_sha256 IS NOT NULL")) {
                        assertTrue(rows.next());
                        assertEquals(count == 1 ? 1 : 0, rows.getInt(1));
                    }
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_release WHERE state='ACTIVE'")) {
                        assertTrue(rows.next());
                        assertEquals(count == 1 ? 1 : 0, rows.getInt(1));
                    }
                }
                assertTrue(peer.builds.present.isEmpty());
                assertTrue(peer.maintenance.isEmpty());
                assertTrue(peer.snapshots.isEmpty());
                assertEquals(peer.builds.sessionsOpened, peer.builds.sessionsClosed);
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }
}

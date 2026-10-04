package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication.DeploymentStandardPublishRecovery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps unknown health outside diagnosis and re-publication, then reconciles real receipts. / 未知健康不进入诊断与重发，随后以真实回执核对。 */
class DeploymentUnknownHealthPublishTest extends DeploymentPublishAcceptanceFixture {
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
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentPublishStatePeer();
            peer.builds.unknownHealth = true;
            var language = new AtomicInteger();
            var diagnosis = new AtomicInteger();
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
                    assertEquals("a".repeat(64), expected);
                    String response;
                    if (request.purpose() == AiPurpose.APPROVAL)
                        response = "{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Exact fixed action\"}";
                    else {
                        assertEquals(AiPurpose.LANGUAGE, request.purpose());
                        language.incrementAndGet();
                        try {
                            var facts = new ObjectMapper().readTree(request.messages().getLast().text());
                            if (Set.of("BUILD", "ROLLBACK_VERIFIED").contains(facts.path("phase").asText())) {
                                diagnosis.incrementAndGet();
                                throw new AssertionError("Unknown health must never authorize diagnosis");
                            }
                            response = "{\"action\":\"ADVISE\",\"sourceRevision\":\""
                                + facts.path("sourceRevision").asText()
                                + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Retain human inputs\",\"unresolved\":[]}}";
                        } catch (java.io.IOException failure) {
                            throw new AssertionError(failure);
                        }
                    }
                    return success(new AiResponse(response, "controlled-purpose", "stop",
                        new AiUsage(Optional.empty(), Optional.empty())));
                }
            };
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                    return peer.session(task);
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
            try (var registration = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, new DeploymentStaticTaskRecovery(bound, flow))) {
                var handle = new DeploymentStaticWorkflow(bound, flow).submit(new ServerId("server"),
                    new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(count),
                    true, "MANUAL_REVIEW");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED
                    && System.nanoTime() < until)
                    Thread.sleep(20);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
                assertTrue(peer.builds.healthChecks > 0);
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
                var ended = reconcileReady(runtime, handle);
                assertEquals(TaskState.ENDED, ended.state(), () -> "Actual retained original task=" + ended
                    + "; remote commands=" + peer.builds.commands + "; current=" + peer.builds.current
                    + "; snapshots=" + peer.snapshots + "; maintenance=" + peer.maintenance);
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

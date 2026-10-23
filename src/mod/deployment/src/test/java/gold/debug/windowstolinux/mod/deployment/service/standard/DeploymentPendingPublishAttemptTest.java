package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.*;
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
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.recovery.DeploymentStandardTaskRecovery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Retains formally consumed original attempts until exact child/root confirmation, without replay. / 正式已消费原尝试留存至精确子根确认，不重放。 */
class DeploymentPendingPublishAttemptTest extends DeploymentPublishAcceptanceFixture {
    @ParameterizedTest
    @CsvSource({"1,false", "2,false", "2,true"})
    void consumedFormalAttemptDoesNotAdmitAnotherIntentOrBuildWhilePointerIsPending(int count, boolean graphRoot) throws Exception {
        source(count);
        enable();
        allowCorrection = true;
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentPublishStatePeer();
            peer.builds.healthFailuresRemaining = 1;
            var language = new AtomicInteger();
            var diagnosis = new AtomicInteger();
            var installed = new AtomicInteger();
            approval = intent -> {
                if (!intent.type().equals("deployment.assisted.build-review") || installed.getAndIncrement() != 0)
                    return;
                try (var connection = runtime.persistence().database().openConnection(); var statement = connection.createStatement()) {
                    String key = graphRoot ? "deployment-publication-graph" : count == 1 ? "deployment-publication-remote" : "deployment-publication-unit-00";
                    statement.execute("ALTER TABLE platform_task_resource ADD CONSTRAINT publication_attempt_fault CHECK(resource_id NOT LIKE '" + OWNER + "/checkpoint/" + key + "' OR LOCATE('\\\"attemptState\\\":\\\"CONFIRMED\\\"',recovery_json)=0)");
                } catch (Exception failure) {
                    throw new AssertionError("Exact post-health checkpoint fault could not be installed", failure);
                }
            };
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

                public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task) throws ApiException {
                    assertEquals("a".repeat(64), expected);
                    String response;
                    if (request.purpose() == AiPurpose.APPROVAL)
                        response = "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Exact fixed action\"}";
                    else {
                        assertEquals(AiPurpose.LANGUAGE, request.purpose());
                        language.incrementAndGet();
                        try {
                            var facts = new ObjectMapper().readTree(request.messages().getLast().text());
                            if (facts.has("projectTypes"))
                                response = DeploymentAssistedGoModelFixture.sourceScan(facts, count);
                            else if (facts.has("tools"))
                                response = DeploymentAssistedGoModelFixture.publicationTool(facts);
                            else if (Set.of("BUILD", "ROLLBACK_VERIFIED").contains(facts.path("phase").asText())) {
                                assertEquals("ROLLBACK_VERIFIED", facts.path("phase").asText());
                                assertTrue(facts.path("fields").isEmpty());
                                diagnosis.incrementAndGet();
                                response = "{\"decision\":\"RETRY\",\"reason\":\"Original human correction only\",\"suggestions\":{}}";
                            } else
                                response = "{\"action\":\"ADVISE\",\"sourceRevision\":\"" + facts.path("sourceRevision").asText() + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Retain human inputs\",\"unresolved\":[]}}";
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                    return success(new AiResponse(response, "controlled-purpose", "stop", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
                }
            };
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected, TaskContext task) throws ApiException {
                    assertEquals(new ServerId("server"), expected.server());
                    assertEquals(task.id(), expected.taskId());
                    var server = original.servers().get(expected.server(), task);
                    assertEquals(server.host(), expected.host());
                    assertEquals(server.port(), expected.port());
                    assertEquals(server.username(), expected.username());
                    var session = peer.session(task);
                    if (expected.fingerprint().isPresent()
                        && !expected.fingerprint().orElseThrow().equals(session.facts().fingerprint())) {
                        session.close();
                        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                            gold.debug.windowstolinux.appapi.task.result.ProblemCode.ACCESS_DENIED,
                            "DEPLOYMENT_TEST_WIRE_CHANGED", "Original controlled wire differs", Map.of(), Optional.of(task.id())));
                    }
                    return session;
                }

                public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
                    return peer.session(task);
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    return List.of();
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("tasks")) return tasks;
                if (method.getName().equals("linux")) return linux;
                if (method.getName().equals("ai")) return models;
                try {
                    return method.invoke(original, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var flow = new DeploymentRefFlow(bound, new DeploymentStandardPublishRecovery(bound));
            try (var registration = tasks.registerRecovery(DeploymentRefFlow.TASK_TYPE, new DeploymentStandardTaskRecovery(bound, flow, new DeploymentStandardAssembly()))) {
                var handle = new DeploymentStandardEntryFixture(bound, flow).submit(new ServerId("server"), new DeploymentStandardInputs.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(count), true, "MANUAL_REVIEW");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED && System.nanoTime() < until)
                    Thread.sleep(20);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), () -> handle.snapshot() + " result="
                    + handle.completion().toCompletableFuture().getNow(null));
                assertEquals(1, installed.get());
                assertEquals(1, diagnosis.get());
                assertEquals(1, corrections);
                var json = new ObjectMapper();
                JsonNode request;
                try (var access = scope(runtime, true); var connection = access.openConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT platform_task_id,request_payload FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertEquals(handle.id().toString(), rows.getString(1));
                    request = json.readTree(rows.getBytes(2));
                    assertFalse(rows.next());
                }
                assertEquals(1, request.path("attemptReviews").size());
                assertTrue(request.path("attemptIntent").isNull());
                var review = request.path("attemptReviews").get(0);
                assertEquals(handle.id().toString(), review.path("previousOperation").asText());
                String nextOperation = review.path("nextOperation").asText();
                assertNotEquals(handle.id(), UUID.fromString(nextOperation));
                assertEquals(1, review.path("retry").asInt());
                String checkpoint = graphRoot ? "deployment-publication-graph" : count == 1 ? "deployment-publication-remote" : "deployment-publication-unit-00";
                try (var connection = runtime.persistence().database().openConnection(); var statement = connection.prepareStatement("SELECT recovery_json FROM platform_task_resource WHERE task_id=? AND resource_id=? AND released_at IS NULL")) {
                    statement.setString(1, handle.id().toString());
                    statement.setString(2, OWNER + "/checkpoint/" + checkpoint);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        var pointer = json.readTree(json.readTree(rows.getString(1)).path("fields").path("payload").asText());
                        assertEquals("PENDING", pointer.path("attemptState").asText());
                        assertEquals(nextOperation, pointer.path("attemptNextOperation").asText());
                        assertEquals(handle.id().toString(), pointer.path("attemptPreviousOperation").asText());
                        assertEquals("SESSION_CLOSED", pointer.path("phase").asText());
                        assertFalse(rows.next());
                    }
                }
                String retained = request.toString();
                int calls = language.get();
                var builds = Map.copyOf(peer.builds.builds);
                var creates = Map.copyOf(peer.builds.creates);
                var uploads = Map.copyOf(peer.builds.uploads);
                var publications = List.copyOf(peer.builds.sentPublications);
                assertTrue(peer.builds.present.isEmpty());
                assertTrue(peer.maintenance.isEmpty());
                assertTrue(peer.builds.current.isEmpty());
                assertEquals(TaskState.UNKNOWN, reconcileReady(runtime, handle).state());
                try (var access = scope(runtime, true); var connection = access.openConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT request_payload FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertEquals(retained, json.readTree(rows.getBytes(1)).toString());
                }
                assertEquals(builds, peer.builds.builds);
                assertEquals(creates, peer.builds.creates);
                assertEquals(uploads, peer.builds.uploads);
                assertEquals(publications, peer.builds.sentPublications);
                assertEquals(calls, language.get());
                try (var connection = runtime.persistence().database().openConnection(); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT publication_attempt_fault");
                }
                var ended = reconcileReady(runtime, handle);
                assertEquals(TaskState.ENDED, ended.state());
                assertEquals(Optional.of(TaskResult.FAILED), ended.outcome());
                assertEquals(builds, peer.builds.builds);
                assertEquals(creates, peer.builds.creates);
                assertEquals(uploads, peer.builds.uploads);
                assertEquals(publications, peer.builds.sentPublications);
                assertEquals(calls, language.get());
                assertEquals(1, diagnosis.get());
                assertEquals(1, corrections);
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(peer.builds.sessionsOpened, peer.builds.sessionsClosed);
            }
        }
    }
}

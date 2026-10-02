package gold.debug.windowstolinux.mod.deployment.service.standard;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication.DeploymentStandardPublicationRecovery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Retains formally consumed original attempts until exact child/root confirmation, without replay. / 正式已消费原尝试留存至精确子根确认，不重放。 */
class DeploymentPendingPublicationAttemptTest extends DeploymentPublicationAcceptanceFixture {
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
            var peer = new DeploymentPublicationStatePeer();
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
                        response = "{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Exact fixed action\"}";
                    else {
                        assertEquals(AiPurpose.LANGUAGE, request.purpose());
                        language.incrementAndGet();
                        try {
                            var facts = new ObjectMapper().readTree(request.messages().getLast().text());
                            if (Set.of("BUILD", "ROLLBACK_VERIFIED").contains(facts.path("phase").asText())) {
                                assertEquals("ROLLBACK_VERIFIED", facts.path("phase").asText());
                                assertTrue(facts.path("fields").isEmpty());
                                diagnosis.incrementAndGet();
                                response = "{\"decision\":\"RETRY\",\"reason\":\"Original human correction only\",\"suggestions\":{}}";
                            } else
                                response = "{\"action\":\"ADVISE\",\"sourceRevision\":\"" + facts.path("sourceRevision").asText() + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Retain human inputs\",\"unresolved\":[]}}";
                        } catch (java.io.IOException failure) {
                            throw new AssertionError(failure);
                        }
                    }
                    return success(new AiResponse(response, "controlled-purpose", "stop", new AiUsage(Optional.empty(), Optional.empty())));
                }
            };
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
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
            var flow = new DeploymentReferenceFlow(bound, new DeploymentStandardPublicationRecovery(bound));
            try (var registration = tasks.registerRecovery(DeploymentReferenceFlow.TASK_TYPE, new DeploymentStaticTaskRecovery(bound, flow))) {
                var handle = new DeploymentStaticWorkflow(bound, flow).submit(new ServerRef("server"), new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(), reviewer(count), true, "MANUAL_REVIEW");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED && System.nanoTime() < until)
                    Thread.sleep(20);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
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
                assertEquals(Optional.of(Outcome.FAILED), ended.outcome());
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

package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentExecutionStoreTest extends DeploymentExecutionFixture {
    /** Preserves the original operation while using the shared formal publication request format. / 使用共享正式发布请求格式并保留原操作。
     * @param operation original execution engine / 原执行引擎
     * @throws Exception if actual journal read rejects the request / 实际账本读取拒绝请求时 */
    @ParameterizedTest
    @ValueSource(strings = {"deployment-agent", "deployment-restore"})
    void formalEnginesRetainPublicationTemplateFraming(String operation) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var journal = journal(runtime);
                var id = UUID.randomUUID();
                journal.begin(task, identity(task, id), operation, List.of(intent()), Optional.empty(), OBSERVED);
                var record = journal.find(task, id).orElseThrow();
                assertEquals(DeploymentExecutionCodec.VERSION, DeploymentExecutionCodec.version(record));
                assertTrue(record.requestJson().contains("\"publicationTemplate\":null"));
                assertTrue(record.requestJson().contains(operation));
                return success();
            });
        }
    }

    @Test
    void formalResultAndActualPublicationCommitTogetherAndSurviveRestartWithPrivateJsonLibraries() throws Exception {
        enable();
        UUID id = UUID.randomUUID();
        var graph = graph("application");
        var records = publications(graph, 1, List.of());
        var original = new AtomicReference<DeploymentExecutionRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var journal = journal(runtime);
                var reference = prepare(runtime, task, id);
                journal.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(3));
                var committed = journal.find(task, id).orElseThrow();
                assertTrue(committed.businessCommitted());
                assertFalse(committed.cleanupComplete());
                assertEquals("COMMITTED", committed.phase());
                assertEquals(Optional.of(TaskResult.SUCCEEDED), committed.outcome());
                assertTrue(committed.resultJson().orElseThrow().contains(records.getFirst().configuration().sha256()));
                assertThrows(SQLException.class,
                    () -> journal.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(4)));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                    ResourceRefResult.REFERENCED, task);
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                original.set(journal.find(task, id).orElseThrow());
                journal.begin(task, identity(task, id), "deploy", List.of(intent()), Optional.empty(), OBSERVED);
                assertEquals(original.get(), journal.find(task, id).orElseThrow());
                verify(runtime, task, graph, records);
                return success();
            });
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var record = journal(runtime).find(task, id).orElseThrow();
                assertEquals(original.get(), record);
                assertTrue(record.cleanupComplete());
                assertEquals("FINISHED", record.phase());
                assertThrows(SQLException.class,
                    () -> journal(runtime).finishCleanup(task, id, OBSERVED.plusSeconds(5)));
                verify(runtime, task, graph, records);
                return success();
            });
            valid(runtime);
        }
        var description = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(workspace().resolve("src/mod/deployment/target/wtlmod-development.json").toFile());
        assertTrue(description.toString().contains("jackson-databind-2.18.1.jar"));
        assertTrue(description.toString().contains("jackson-core-2.18.1.jar"));
        assertTrue(description.toString().contains("jackson-annotations-2.18.1.jar"));
    }

    @Test
    void lostExecutionCreationAcknowledgementIsRecoveredFromTheOriginalPlatformTask() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = journal(runtime);
                assertTrue(journal.findForTask(task).isEmpty());
                var fault = new DeploymentExecutionStore(commitFault(context.db()));
                var failure = assertThrows(ApiException.class, () -> fault.begin(task,
                    identity(task, UUID.randomUUID()), "deploy", List.of(intent()), Optional.empty(), OBSERVED));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                var original = journal.findForTask(task).orElseThrow();
                assertEquals(task.id(), original.identity().taskId());
                assertEquals("PREPARED", original.phase());
                assertEquals(Map.of(intent(), Optional.empty()), journal.references(original));
                journal.recordUncommittedOutcome(task, original.identity().executionId(), TaskResult.CANCELLED,
                    problem(TaskResult.CANCELLED, "CANCELLED_BEFORE_RESERVATION"), OBSERVED.plusSeconds(1));
                journal.finishCleanup(task, original.identity().executionId(), OBSERVED.plusSeconds(2));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void resultWriteFailureRollsBackEveryBusinessRecordAndRetainsTheOriginalExecutionBoundary() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var journal = journal(runtime);
                var reference = prepare(runtime, task, id);
                var before = journal.find(task, id).orElseThrow();
                sql(runtime, task,
                    "ALTER TABLE deployment_execution ADD CONSTRAINT fixture_commit_rejection CHECK(business_committed=FALSE)");
                assertThrows(SQLException.class, () -> journal.commitApplication(task, id, graph("application"),
                    publications(graph("application"), 1, List.of()), OBSERVED.plusSeconds(3)));
                assertEquals(before, journal.find(task, id).orElseThrow());
                for (String table : List.of("deployment_unit", "managed_application", "managed_application_component",
                    "deployment_unit_configuration_snapshot", "deployment_unit_release_binding",
                    "deployment_release"))
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM " + table));
                sql(runtime, task, "ALTER TABLE deployment_execution DROP CONSTRAINT fixture_commit_rejection");
                journal.recordUncommittedOutcome(task, id, TaskResult.FAILED,
                    problem(TaskResult.FAILED, "PUBLICATION_ROLLED_BACK"), OBSERVED.plusSeconds(3));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                    ResourceRefResult.NOT_REFERENCED, task);
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                assertFalse(journal.find(task, id).orElseThrow().businessCommitted());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void lostCommitAcknowledgementReadsTheFormalOriginalResultWithoutReplayingPublication() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var reference = prepare(runtime, task, id);
                var graph = graph("application");
                var records = publications(graph, 1, List.of());
                var fault = new DeploymentExecutionStore(
                    commitFault(runtime.modContexts().get(OWNER).context().db()));
                var failure = assertThrows(ApiException.class,
                    () -> fault.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(3)));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                var journal = journal(runtime);
                var original = journal.find(task, id).orElseThrow();
                assertTrue(original.businessCommitted());
                assertFalse(original.cleanupComplete());
                var link = DeploymentExecutionCodec.release(original).orElseThrow();
                var history = new DeploymentReleaseStore(runtime.modContexts().get(OWNER).context().db());
                assertEquals(DeploymentReleaseSnapshot.capture(graph, records),
                    history.snapshot(history.find(task, "application", link.id()).orElseThrow()));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                verify(runtime, task, graph, records);
                assertThrows(SQLException.class, () -> journal.recordUncommittedOutcome(task, id, TaskResult.FAILED,
                    problem(TaskResult.FAILED, "NOT_ALLOWED"), OBSERVED.plusSeconds(4)));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                    ResourceRefResult.REFERENCED, task);
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                assertEquals(original.resultJson(), journal.find(task, id).orElseThrow().resultJson());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void originalResourceKeysRecoverLostReservationUuidsAndUnknownCannotFinishOrRestartEffects() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var journal = journal(runtime);
                var context = runtime.modContexts().get(OWNER).context();
                var secret = context.secrets().create("deployment.configuration", new byte[]{42}, task);
                var secretIntent = DeploymentRefIntent.secret(secret, "secret:database:1");
                var intents = List.of(intent(), secretIntent);
                journal.begin(task, identity(task, id), "deploy", intents, Optional.empty(), OBSERVED);
                assertThrows(SQLException.class, () -> journal.startEffects(task, id, OBSERVED.plusSeconds(1)));
                context.servers().reserve(new ServerId("server"), intent().businessKey(), task);
                context.secrets().reserve(secret, secretIntent.businessKey(), task);
                journal.recordUncommittedOutcome(task, id, TaskResult.UNKNOWN,
                    problem(TaskResult.UNKNOWN, "RESERVATION_ACKNOWLEDGEMENT_LOST"), OBSERVED.plusSeconds(1));
                var retained = journal.find(task, id).orElseThrow();
                assertTrue(journal.references(retained).values().stream().allMatch(Optional::isEmpty));
                var serverRef = context.servers().findReference(new ServerId("server"), intent().businessKey(), task)
                    .orElseThrow();
                var secretRef = context.secrets().findReference(secret, secretIntent.businessKey(), task).orElseThrow();
                journal.recordReference(task, id, intent(), serverRef.id(), OBSERVED.plusSeconds(2));
                journal.recordReference(task, id, secretIntent, secretRef.id(), OBSERVED.plusSeconds(2));
                assertThrows(SQLException.class, () -> journal.finishCleanup(task, id, OBSERVED.plusSeconds(3)));
                assertThrows(SQLException.class, () -> journal.startEffects(task, id, OBSERVED.plusSeconds(3)));
                journal.recordUncommittedOutcome(task, id, TaskResult.CANCELLED,
                    problem(TaskResult.CANCELLED, "CANCELLED_BEFORE_EFFECTS"), OBSERVED.plusSeconds(3));
                context.servers().resolve(serverRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                context.secrets().resolve(secretRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                assertFalse(journal.find(task, id).orElseThrow().businessCommitted());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void everyPublishedSecretMustBelongToTheOriginalReservedIntent(boolean included) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var journal = journal(runtime);
                var context = runtime.modContexts().get(OWNER).context();
                var secret = context.secrets().create("deployment.configuration", new byte[]{42}, task);
                var secretIntent = DeploymentRefKey.secretRevision(new ServerId("server"), secret,
                    new DeploymentSecretRef("password", 1));
                journal.begin(task, identity(task, id), "deploy",
                    included ? List.of(intent(), secretIntent) : List.of(intent()), Optional.empty(), OBSERVED);
                var server = context.servers().reserve(new ServerId("server"), intent().businessKey(), task);
                var reservation = context.secrets().reserve(secret, secretIntent.businessKey(), task);
                journal.recordReference(task, id, intent(), server.id(), OBSERVED.plusSeconds(1));
                if (included)
                    journal.recordReference(task, id, secretIntent, reservation.id(), OBSERVED.plusSeconds(1));
                var reference = new DeploymentSecretRef("password", 1);
                new DeploymentSecretStore(context.db()).saveRevision(task,
                    new DeploymentSecretRevision(reference, secret.id(), OBSERVED, Optional.of(secret.purpose())));
                journal.startEffects(task, id, OBSERVED.plusSeconds(2));
                var graph = graph("application");
                var records = publications(graph, 1, List.of(reference));
                if (included) {
                    journal.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(3));
                    verify(runtime, task, graph, records);
                    var release = DeploymentExecutionCodec.release(journal.find(task, id).orElseThrow()).orElseThrow();
                    var history = new DeploymentReleaseStore(context.db());
                    var snapshot = history.snapshot(history.find(task, "application", release.id()).orElseThrow());
                    assertTrue(snapshot.bindings().stream().allMatch(b -> b.secrets().equals(List.of(reference))));
                    context.servers().resolve(server.id(), ResourceRefResult.REFERENCED, task);
                    context.secrets().resolve(reservation.id(), ResourceRefResult.REFERENCED, task);
                } else {
                    assertThrows(SQLException.class,
                        () -> journal.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(3)));
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_unit"));
                    journal.recordUncommittedOutcome(task, id, TaskResult.FAILED,
                        problem(TaskResult.FAILED, "MISSING_REFERENCE_FACT"), OBSERVED.plusSeconds(3));
                    sql(runtime, task, "DELETE FROM secret_revision");
                    context.servers().resolve(server.id(), ResourceRefResult.NOT_REFERENCED, task);
                    context.secrets().resolve(reservation.id(), ResourceRefResult.NOT_REFERENCED, task);
                }
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void publishedSecretMustMatchItsOwnOriginalKeyAndCredentialPair() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = journal(runtime);
                var first = context.secrets().create("deployment.configuration", new byte[]{1}, task);
                var second = context.secrets().create("deployment.configuration", new byte[]{2}, task);
                var published = new DeploymentSecretRef("password", 1);
                var other = new DeploymentSecretRef("other", 1);
                var firstIntent = DeploymentRefKey.secretRevision(new ServerId("server"), first, published);
                var secondIntent = DeploymentRefKey.secretRevision(new ServerId("server"), second, other);
                UUID id = UUID.randomUUID();
                journal.begin(task, identity(task, id), "deploy", List.of(intent(), firstIntent, secondIntent),
                    Optional.empty(), OBSERVED);
                var serverRef = context.servers().reserve(new ServerId("server"), intent().businessKey(), task);
                var firstRef = context.secrets().reserve(first, firstIntent.businessKey(), task);
                var secondRef = context.secrets().reserve(second, secondIntent.businessKey(), task);
                journal.recordReference(task, id, intent(), serverRef.id(), OBSERVED.plusSeconds(1));
                journal.recordReference(task, id, firstIntent, firstRef.id(), OBSERVED.plusSeconds(1));
                journal.recordReference(task, id, secondIntent, secondRef.id(), OBSERVED.plusSeconds(1));
                new DeploymentSecretStore(context.db()).saveRevision(task,
                    new DeploymentSecretRevision(published, second.id(), OBSERVED, Optional.of(second.purpose())));
                journal.startEffects(task, id, OBSERVED.plusSeconds(2));
                var graph = graph("application");
                assertThrows(SQLException.class, () -> journal.commitApplication(task, id, graph,
                    publications(graph, 1, List.of(published)), OBSERVED.plusSeconds(3)));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_unit"));
                journal.recordUncommittedOutcome(task, id, TaskResult.FAILED,
                    problem(TaskResult.FAILED, "EXACT_SECRET_PAIR_CONFLICT"), OBSERVED.plusSeconds(3));
                sql(runtime, task, "DELETE FROM secret_revision WHERE secret_id='password' AND secret_revision=1");
                context.servers().resolve(serverRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                context.secrets().resolve(firstRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                context.secrets().resolve(secondRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void immutableIdentityOriginalTaskAndConcurrentReservationEvidenceCannotBeOverwritten() throws Exception {
        enable();
        UUID id = UUID.randomUUID();
        var original = new AtomicReference<DeploymentExecutionRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var journal = journal(runtime);
                var identity = identity(task, id);
                journal.begin(task, identity, "deploy", List.of(intent()), Optional.empty(), OBSERVED);
                assertThrows(SQLException.class,
                    () -> journal.begin(task, identity, "changed", List.of(intent()), Optional.empty(), OBSERVED));
                assertThrows(SQLException.class, () -> journal.begin(task, identity(task, UUID.randomUUID()), "deploy",
                    List.of(intent()), Optional.empty(), OBSERVED));
                assertThrows(SQLException.class,
                    () -> journal.recordReference(task, id, intent(), UUID.randomUUID(), OBSERVED.minusMillis(1)));
                var ready = new CountDownLatch(2);
                var go = new CountDownLatch(1);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var attempts = new ArrayList<Future<Boolean>>();
                    for (int i = 0; i < 2; i++)
                        attempts.add(executor.submit(() -> {
                            ready.countDown();
                            assertTrue(go.await(8, TimeUnit.SECONDS));
                            try {
                                journal.recordReference(task, id, intent(), UUID.randomUUID(), OBSERVED.plusSeconds(1));
                                return true;
                            } catch (SQLException conflict) {
                                return false;
                            }
                        }));
                    assertTrue(ready.await(8, TimeUnit.SECONDS));
                    go.countDown();
                    int successes = 0;
                    for (var attempt : attempts)
                        if (attempt.get(8, TimeUnit.SECONDS))
                            successes++;
                    assertEquals(1, successes);
                }
                original.set(journal.find(task, id).orElseThrow());
                journal.begin(task, identity, "deploy", List.of(intent()), Optional.empty(), OBSERVED);
                assertEquals(original.get(), journal.find(task, id).orElseThrow());
                return success();
            });
            run(runtime, task -> {
                var journal = journal(runtime);
                assertEquals(original.get(), journal.find(task, id).orElseThrow());
                assertThrows(SQLException.class, () -> journal.startEffects(task, id, OBSERVED.plusSeconds(2)));
                assertThrows(SQLException.class, () -> journal.begin(task, original.get().identity(), "deploy",
                    List.of(intent()), Optional.empty(), OBSERVED));
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 5, 6, 7, 8, 9, 10, 12, 999})
    void unknownDocumentVersionsRemainReadableAndUnchangedButCannotReconcileOrMutate(int version) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var journal = journal(runtime);
                journal.begin(task, identity(task, id), "deploy", List.of(intent()), Optional.empty(), OBSERVED);
                String future = "{\"formatVersion\":" + version + ",\"futureData\":{\"retained\":true}}";
                replace(runtime, task, id, "recovery_payload", future.getBytes(StandardCharsets.UTF_8));
                var record = journal.find(task, id).orElseThrow();
                assertEquals(future, record.recoveryJson());
                assertEquals("0A000", assertThrows(SQLException.class, () -> journal.references(record)).getSQLState());
                assertEquals("0A000", assertThrows(SQLException.class,
                    () -> journal.recordReference(task, id, intent(), UUID.randomUUID(), OBSERVED.plusSeconds(1)))
                    .getSQLState());
                assertEquals(record, journal.find(task, id).orElseThrow());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void obsoleteRequestsCannotReplayEffectsOrReleaseOriginalReservations() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var journal = journal(runtime);
                var context = runtime.modContexts().get(OWNER).context();
                UUID id = UUID.randomUUID();
                var identity = identity(task, id);
                var originalIntent = DeploymentRefIntent.server(new ServerId("server"), "old:" + id);
                journal.begin(task, identity, "deployment-publication", List.of(originalIntent), Optional.empty(), OBSERVED);
                var reservation = context.servers().reserve(new ServerId("server"), originalIntent.businessKey(), task);
                journal.recordReference(task, id, originalIntent, reservation.id(), OBSERVED.plusSeconds(1));
                var original = journal.find(task, id).orElseThrow();
                for (int version : List.of(4, 5, 6, 7, 8, 9, 10, 12)) {
                    var request = DeploymentJsonDocument.parse(original.requestJson());
                    request.put("formatVersion", version);
                    replace(runtime, task, id, "request_payload", request.toString().getBytes(StandardCharsets.UTF_8));
                    var retained = journal.find(task, id).orElseThrow();
                    assertEquals(task.id(), retained.identity().taskId());
                    assertEquals(original.recoveryJson(), retained.recoveryJson());
                    assertEquals("0A000", assertThrows(SQLException.class, () -> journal.references(retained)).getSQLState());
                    assertThrows(SQLException.class, () -> journal.startEffects(task, id, OBSERVED.plusSeconds(2)));
                    assertThrows(SQLException.class, () -> journal.finishCleanup(task, id, OBSERVED.plusSeconds(2)));
                    assertEquals(retained, journal.find(task, id).orElseThrow());
                    assertEquals(reservation, context.servers().findReference(new ServerId("server"), originalIntent.businessKey(), task).orElseThrow());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                }
                return success();
            });
        }
    }

    @Test
    void completeCurrentRequestRejectsEveryMissingFieldAndUnregisteredOperationIdentity() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var journal = journal(runtime);
                journal.begin(task, identity(task, id), "deployment-publication", List.of(intent()), Optional.empty(), OBSERVED);
                var original = journal.find(task, id).orElseThrow();
                var request = DeploymentJsonDocument.parse(original.requestJson());
                assertEquals(task.id().toString(), request.path("attemptOperation").textValue());
                var fields = new ArrayList<String>();
                request.fieldNames().forEachRemaining(fields::add);
                for (String field : fields) {
                    var missing = request.deepCopy();
                    missing.remove(field);
                    assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(requestRecord(original, missing.toString())), field);
                }
                assertTrue(request.path("references").get(0).path("purpose").isNull());
                var missingPurpose = request.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) missingPurpose.path("references").get(0)).remove("purpose");
                assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(requestRecord(original, missingPurpose.toString())));
                var wrongPurpose = request.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) wrongPurpose.path("references").get(0)).put("purpose", "unregistered");
                assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(requestRecord(original, wrongPurpose.toString())));
                var forged = request.deepCopy().put("attemptOperation", UUID.randomUUID().toString());
                assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(requestRecord(original, forged.toString())));
                for (String version : List.of("\"11\"", "11.0", "null", "true")) {
                    String invalid = request.toString().replace("\"formatVersion\":11", "\"formatVersion\":" + version);
                    assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(requestRecord(original, invalid)));
                }
                assertEquals(original, journal.find(task, id).orElseThrow());
                return success();
            });
        }
    }

    private static DeploymentExecutionRecord requestRecord(DeploymentExecutionRecord original, String request) {
        return new DeploymentExecutionRecord(original.identity(), original.phase(), original.outcome(), request,
            original.recoveryJson(), original.resultJson(), original.businessCommitted(), original.cleanupComplete(),
            original.createdAt(), original.updatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "trailing", "missing", "identity", "phase", "oversize", "utf8"})
    void malformedRecoveryEvidenceIsRejectedWithoutRepair(String fault) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var journal = journal(runtime);
                journal.begin(task, identity(task, id), "deploy", List.of(intent()), Optional.empty(), OBSERVED);
                String json = journal.find(task, id).orElseThrow().recoveryJson();
                byte[] bytes = switch (fault) {
                    case "duplicate" -> json.replace("\"formatVersion\":" + DeploymentExecutionCodec.VERSION,
                            "\"formatVersion\":" + DeploymentExecutionCodec.VERSION + ",\"formatVersion\":"
                                + DeploymentExecutionCodec.VERSION)
                        .getBytes(StandardCharsets.UTF_8);
                    case "trailing" -> (json + " {}").getBytes(StandardCharsets.UTF_8);
                    case "missing" -> "{}".getBytes(StandardCharsets.UTF_8);
                    case "identity" ->
                        json.replace(id.toString(), UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8);
                    case "phase" -> json.replace("PREPARED", "FINISHED").getBytes(StandardCharsets.UTF_8);
                    case "oversize" -> new byte[1_048_577];
                    case "utf8" -> new byte[]{(byte) 0xc3, 0x28};
                    default -> throw new AssertionError(fault);
                };
                replace(runtime, task, id, "recovery_payload", bytes);
                assertThrows(SQLException.class, () -> journal.find(task, id));
                try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
                     var query = connection.prepareStatement(
                         "SELECT recovery_payload FROM deployment_execution WHERE execution_id=?")) {
                    query.setObject(1, id);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertArrayEquals(bytes, rows.getBytes(1));
                    }
                }
                return success();
            });
            try (var access = scope(runtime, true)) {
                var report = new DeploymentSchema().validate(access);
                assertFalse(report.valid());
                assertTrue(report.violations().contains("Stored deployment execution evidence is invalid"));
            }
        }
    }

    void replace(PlatformRuntime runtime, TaskContext task, UUID id, String column, byte[] value) throws Exception {
        assertTrue(Set.of("request_payload", "recovery_payload", "result_payload").contains(column));
        try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
             var statement = connection
                 .prepareStatement("UPDATE deployment_execution SET " + column + "=? WHERE execution_id=?")) {
            statement.setBytes(1, value);
            statement.setObject(2, id);
            assertEquals(1, statement.executeUpdate());
        }
    }
}

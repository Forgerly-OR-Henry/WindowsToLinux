package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentExecutionRepositoryTest extends DeploymentGraphFixture {
    DeploymentExecutionIdentity identity(TaskContext task, UUID id) {
        return new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerRef("server"),
                DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "a".repeat(64));
    }

    DeploymentReferenceIntent intent() {
        return DeploymentReferenceIntent.server(new ServerRef("server"), "application:application");
    }

    DeploymentExecutionRepository journal(PlatformRuntime runtime) {
        return new DeploymentExecutionRepository(runtime.modContexts().get(OWNER).context().db());
    }

    ServerReference prepare(PlatformRuntime runtime, TaskContext task, UUID id) throws Exception {
        var journal = journal(runtime);
        journal.begin(task, identity(task, id), "deploy", List.of(intent()), OBSERVED);
        var reference = runtime.modContexts().get(OWNER).context().servers().reserve(new ServerRef("server"),
                intent().businessKey(), task);
        journal.recordReference(task, id, intent(), reference.id(), OBSERVED.plusSeconds(1));
        journal.startEffects(task, id, OBSERVED.plusSeconds(2));
        return reference;
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
                assertEquals(Optional.of(Outcome.SUCCEEDED), committed.outcome());
                assertTrue(committed.resultJson().orElseThrow().contains(records.getFirst().configuration().sha256()));
                assertThrows(SQLException.class,
                        () -> journal.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(4)));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                        ReferenceResolution.REFERENCED, task);
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                original.set(journal.find(task, id).orElseThrow());
                journal.begin(task, identity(task, id), "deploy", List.of(intent()), OBSERVED);
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
                var fault = new DeploymentExecutionRepository(commitFault(context.db()));
                var failure = assertThrows(ApiException.class, () -> fault.begin(task,
                        identity(task, UUID.randomUUID()), "deploy", List.of(intent()), OBSERVED));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                var original = journal.findForTask(task).orElseThrow();
                assertEquals(task.id(), original.identity().taskId());
                assertEquals("PREPARED", original.phase());
                assertEquals(Map.of(intent(), Optional.empty()), journal.references(original));
                journal.recordUncommittedOutcome(task, original.identity().executionId(), Outcome.CANCELLED,
                        "CANCELLED_BEFORE_RESERVATION", OBSERVED.plusSeconds(1));
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
                        "deployment_unit_configuration_snapshot", "deployment_unit_release_binding"))
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM " + table));
                sql(runtime, task, "ALTER TABLE deployment_execution DROP CONSTRAINT fixture_commit_rejection");
                journal.recordUncommittedOutcome(task, id, Outcome.FAILED, "PUBLICATION_ROLLED_BACK",
                        OBSERVED.plusSeconds(3));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                        ReferenceResolution.NOT_REFERENCED, task);
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
                var fault = new DeploymentExecutionRepository(
                        commitFault(runtime.modContexts().get(OWNER).context().db()));
                var failure = assertThrows(ApiException.class,
                        () -> fault.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(3)));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                var journal = journal(runtime);
                var original = journal.find(task, id).orElseThrow();
                assertTrue(original.businessCommitted());
                assertFalse(original.cleanupComplete());
                verify(runtime, task, graph, records);
                assertThrows(SQLException.class, () -> journal.recordUncommittedOutcome(task, id, Outcome.FAILED,
                        "NOT_ALLOWED", OBSERVED.plusSeconds(4)));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                        ReferenceResolution.REFERENCED, task);
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
                var secretIntent = DeploymentReferenceIntent.secret(secret, "secret:database:1");
                var intents = List.of(intent(), secretIntent);
                journal.begin(task, identity(task, id), "deploy", intents, OBSERVED);
                assertThrows(SQLException.class, () -> journal.startEffects(task, id, OBSERVED.plusSeconds(1)));
                context.servers().reserve(new ServerRef("server"), intent().businessKey(), task);
                context.secrets().reserve(secret, secretIntent.businessKey(), task);
                journal.recordUncommittedOutcome(task, id, Outcome.UNKNOWN, "RESERVATION_ACKNOWLEDGEMENT_LOST",
                        OBSERVED.plusSeconds(1));
                var retained = journal.find(task, id).orElseThrow();
                assertTrue(journal.references(retained).values().stream().allMatch(Optional::isEmpty));
                var serverRef = context.servers().findReference(new ServerRef("server"), intent().businessKey(), task)
                        .orElseThrow();
                var secretRef = context.secrets().findReference(secret, secretIntent.businessKey(), task).orElseThrow();
                journal.recordReference(task, id, intent(), serverRef.id(), OBSERVED.plusSeconds(2));
                journal.recordReference(task, id, secretIntent, secretRef.id(), OBSERVED.plusSeconds(2));
                assertThrows(SQLException.class, () -> journal.finishCleanup(task, id, OBSERVED.plusSeconds(3)));
                assertThrows(SQLException.class, () -> journal.startEffects(task, id, OBSERVED.plusSeconds(3)));
                journal.recordUncommittedOutcome(task, id, Outcome.CANCELLED, "CANCELLED_BEFORE_EFFECTS",
                        OBSERVED.plusSeconds(3));
                context.servers().resolve(serverRef.id(), ReferenceResolution.NOT_REFERENCED, task);
                context.secrets().resolve(secretRef.id(), ReferenceResolution.NOT_REFERENCED, task);
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
                var secretIntent = DeploymentReferenceIntent.secret(secret, "secret:password:1");
                journal.begin(task, identity(task, id), "deploy",
                        included ? List.of(intent(), secretIntent) : List.of(intent()), OBSERVED);
                var server = context.servers().reserve(new ServerRef("server"), intent().businessKey(), task);
                var reservation = context.secrets().reserve(secret, secretIntent.businessKey(), task);
                journal.recordReference(task, id, intent(), server.id(), OBSERVED.plusSeconds(1));
                if (included)
                    journal.recordReference(task, id, secretIntent, reservation.id(), OBSERVED.plusSeconds(1));
                var reference = new DeploymentSecretReference("password", 1);
                new DeploymentSecretRepository(context.db()).saveRevision(task,
                        new DeploymentSecretRevision(reference, secret.id(), OBSERVED));
                journal.startEffects(task, id, OBSERVED.plusSeconds(2));
                var graph = graph("application");
                var records = publications(graph, 1, List.of(reference));
                if (included) {
                    journal.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(3));
                    verify(runtime, task, graph, records);
                    context.servers().resolve(server.id(), ReferenceResolution.REFERENCED, task);
                    context.secrets().resolve(reservation.id(), ReferenceResolution.REFERENCED, task);
                } else {
                    assertThrows(SQLException.class,
                            () -> journal.commitApplication(task, id, graph, records, OBSERVED.plusSeconds(3)));
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_unit"));
                    journal.recordUncommittedOutcome(task, id, Outcome.FAILED, "MISSING_REFERENCE_FACT",
                            OBSERVED.plusSeconds(3));
                    sql(runtime, task, "DELETE FROM secret_revision");
                    context.servers().resolve(server.id(), ReferenceResolution.NOT_REFERENCED, task);
                    context.secrets().resolve(reservation.id(), ReferenceResolution.NOT_REFERENCED, task);
                }
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
                journal.begin(task, identity, "deploy", List.of(intent()), OBSERVED);
                assertThrows(SQLException.class,
                        () -> journal.begin(task, identity, "changed", List.of(intent()), OBSERVED));
                assertThrows(SQLException.class, () -> journal.begin(task, identity(task, UUID.randomUUID()), "deploy",
                        List.of(intent()), OBSERVED));
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
                journal.begin(task, identity, "deploy", List.of(intent()), OBSERVED);
                assertEquals(original.get(), journal.find(task, id).orElseThrow());
                return success();
            });
            run(runtime, task -> {
                var journal = journal(runtime);
                assertEquals(original.get(), journal.find(task, id).orElseThrow());
                assertThrows(SQLException.class, () -> journal.startEffects(task, id, OBSERVED.plusSeconds(2)));
                assertThrows(SQLException.class,
                        () -> journal.begin(task, original.get().identity(), "deploy", List.of(intent()), OBSERVED));
                return success();
            });
        }
    }

    @Test
    void unknownDocumentVersionsRemainReadableAndUnchangedButCannotReconcileOrMutate() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var journal = journal(runtime);
                journal.begin(task, identity(task, id), "deploy", List.of(intent()), OBSERVED);
                String future = "{\"formatVersion\":2,\"futureData\":{\"retained\":true}}";
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
                journal.begin(task, identity(task, id), "deploy", List.of(intent()), OBSERVED);
                String json = journal.find(task, id).orElseThrow().recoveryJson();
                byte[] bytes = switch (fault) {
                    case "duplicate" -> json.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")
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

package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord.DeploymentReleaseState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentReleaseRepositoryTest extends DeploymentExecutionFixture {
    DeploymentReleaseRepository releases(PlatformRuntime runtime) {
        return new DeploymentReleaseRepository(runtime.modContexts().get(OWNER).context().db());
    }

    DeploymentReleaseRecord commit(PlatformRuntime runtime, TaskContext task, DeploymentApplicationGraph graph,
            int revision) throws Exception {
        UUID id = UUID.randomUUID();
        var reference = prepare(runtime, task, id);
        var journal = journal(runtime);
        journal.commitApplication(task, id, graph, publications(graph, revision, List.of()), OBSERVED.plusSeconds(10));
        var link = DeploymentExecutionCodec.release(journal.find(task, id).orElseThrow()).orElseThrow();
        runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(), ReferenceResolution.REFERENCED,
                task);
        journal.finishCleanup(task, id, OBSERVED.plusSeconds(11));
        return releases(runtime).find(task, graph.applicationId(), link.id()).orElseThrow();
    }

    DeploymentApplicationGraph changedGraph() {
        return new DeploymentApplicationGraph("application", "worker", new DeploymentHealthCheck.Tcp(9090, 30, 3),
                List.of(component("worker", "server", List.of()), component("web", "server", List.of())));
    }

    @Test
    void completeOldGraphAndBindingsRemainHistoricalAfterReplacementAndRestart() throws Exception {
        enable();
        var original = new AtomicReference<DeploymentReleaseRecord>();
        var replacement = new AtomicReference<DeploymentReleaseRecord>();
        var firstGraph = graph("application");
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                original.set(commit(runtime, task, firstGraph, 1));
                return success();
            });
            run(runtime, task -> {
                replacement.set(commit(runtime, task, changedGraph(), 2));
                var retired = releases(runtime).find(task, "application", original.get().identity().id()).orElseThrow();
                assertEquals(DeploymentReleaseState.RETIRED, retired.state());
                assertEquals(original.get().runtimeJson(), retired.runtimeJson());
                assertEquals(original.get().healthJson(), retired.healthJson());
                assertEquals(original.get().createdAt(), retired.createdAt());
                assertEquals(original.get().executionId(), retired.executionId());
                assertEquals(DeploymentReleaseSnapshot.capture(firstGraph, publications(firstGraph, 1, List.of())),
                        releases(runtime).snapshot(retired));
                assertNotEquals(original.get().identity().sha256(), replacement.get().identity().sha256());
                assertEquals(2, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                return success();
            });
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var old = releases(runtime).find(task, "application", original.get().identity().id()).orElseThrow();
                assertEquals(firstGraph, releases(runtime).snapshot(old).graph());
                var active = releases(runtime).find(task, "application", replacement.get().identity().id())
                        .orElseThrow();
                assertEquals(replacement.get(), active);
                assertEquals(changedGraph(), releases(runtime).snapshot(active).graph());
                assertTrue(releases(runtime).find(task, "other", active.identity().id()).isEmpty());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void identicalContentReusesItsOriginalIdentityExecutionAndTimeEvenAfterRetirement() throws Exception {
        enable();
        var original = new AtomicReference<DeploymentReleaseRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                original.set(commit(runtime, task, graph("application"), 1));
                return success();
            });
            run(runtime, task -> {
                assertEquals(original.get(), commit(runtime, task, graph("application"), 1));
                return success();
            });
            run(runtime, task -> {
                commit(runtime, task, changedGraph(), 2);
                return success();
            });
            run(runtime, task -> {
                assertEquals(original.get(), commit(runtime, task, graph("application"), 1));
                assertEquals(2, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                assertEquals(4, count(runtime, task,
                        "SELECT COUNT(*) FROM deployment_execution WHERE business_committed=TRUE"));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release WHERE state='ACTIVE'"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void lateFormalResultFailureRollsBackNewHistoryRetirementAndPublicationTogether() throws Exception {
        enable();
        var original = new AtomicReference<DeploymentReleaseRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                original.set(commit(runtime, task, graph("application"), 1));
                return success();
            });
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var reference = prepare(runtime, task, id);
                var journal = journal(runtime);
                var before = journal.find(task, id).orElseThrow();
                sql(runtime, task,
                        "ALTER TABLE deployment_execution ADD CONSTRAINT fixture_history_failure CHECK(execution_id<>'"
                                + id + "' OR business_committed=FALSE)");
                assertThrows(SQLException.class, () -> journal.commitApplication(task, id, changedGraph(),
                        publications(changedGraph(), 2, List.of()), OBSERVED.plusSeconds(10)));
                assertEquals(before, journal.find(task, id).orElseThrow());
                assertEquals(original.get(),
                        releases(runtime).find(task, "application", original.get().identity().id()).orElseThrow());
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                assertEquals(2, count(runtime, task, "SELECT COUNT(*) FROM deployment_unit_configuration_snapshot"));
                verify(runtime, task, graph("application"), publications(graph("application"), 1, List.of()));
                sql(runtime, task, "ALTER TABLE deployment_execution DROP CONSTRAINT fixture_history_failure");
                journal.recordUncommittedOutcome(task, id, Outcome.FAILED,
                        problem(Outcome.FAILED, "CONFIRMED_ROLLBACK"), OBSERVED.plusSeconds(10));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                        ReferenceResolution.REFERENCED, task);
                assertEquals(gold.debug.windowstolinux.appapi.server.ReferenceState.ACTIVE,
                        runtime.modContexts().get(OWNER).context().servers()
                                .findReference(reference.server(), reference.businessKey(), task).orElseThrow()
                                .state());
                journal.finishCleanup(task, id, OBSERVED.plusSeconds(11));
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime_payload", "health_payload"})
    void unknownReleaseFormatsRemainRawAndPreventRetirementWithoutPartialPublication(String column) throws Exception {
        enable();
        var original = new AtomicReference<DeploymentReleaseRecord>();
        String future = "{\"formatVersion\":999,\"future\":true}";
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                original.set(commit(runtime, task, graph("application"), 1));
                replace(runtime, task, original.get().identity().id(), column, future.getBytes(StandardCharsets.UTF_8));
                return success();
            });
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var reference = prepare(runtime, task, id);
                var record = releases(runtime).find(task, "application", original.get().identity().id()).orElseThrow();
                assertEquals(future, column.equals("runtime_payload") ? record.runtimeJson() : record.healthJson());
                assertEquals("0A000",
                        assertThrows(SQLException.class, () -> releases(runtime).snapshot(record)).getSQLState());
                assertEquals("0A000",
                        assertThrows(SQLException.class,
                                () -> journal(runtime).commitApplication(task, id, changedGraph(),
                                        publications(changedGraph(), 2, List.of()), OBSERVED.plusSeconds(10)))
                                .getSQLState());
                assertEquals(record, releases(runtime).find(task, "application", record.identity().id()).orElseThrow());
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                verify(runtime, task, graph("application"), publications(graph("application"), 1, List.of()));
                journal(runtime).recordUncommittedOutcome(task, id, Outcome.FAILED,
                        problem(Outcome.FAILED, "UNSUPPORTED_HISTORY"), OBSERVED.plusSeconds(10));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                        ReferenceResolution.REFERENCED, task);
                assertEquals(gold.debug.windowstolinux.appapi.server.ReferenceState.ACTIVE,
                        runtime.modContexts().get(OWNER).context().servers()
                                .findReference(reference.server(), reference.businessKey(), task).orElseThrow()
                                .state());
                journal(runtime).finishCleanup(task, id, OBSERVED.plusSeconds(11));
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "FAILED", "UNKNOWN"})
    void unresolvedHistoricalIdentityCannotBePromotedByANewSuccessfulExecution(String state) throws Exception {
        enable();
        var original = new AtomicReference<DeploymentReleaseRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                original.set(commit(runtime, task, graph("application"), 1));
                sql(runtime, task, "UPDATE deployment_release SET state='" + state + "'");
                return success();
            });
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var reference = prepare(runtime, task, id);
                assertThrows(SQLException.class,
                        () -> journal(runtime).commitApplication(task, id, graph("application"),
                                publications(graph("application"), 1, List.of()), OBSERVED.plusSeconds(10)));
                assertEquals(DeploymentReleaseState.valueOf(state), releases(runtime)
                        .find(task, "application", original.get().identity().id()).orElseThrow().state());
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                journal(runtime).recordUncommittedOutcome(task, id, Outcome.FAILED,
                        problem(Outcome.FAILED, "UNRESOLVED_ORIGINAL_RELEASE"), OBSERVED.plusSeconds(10));
                runtime.modContexts().get(OWNER).context().servers().resolve(reference.id(),
                        ReferenceResolution.REFERENCED, task);
                assertEquals(gold.debug.windowstolinux.appapi.server.ReferenceState.ACTIVE,
                        runtime.modContexts().get(OWNER).context().servers()
                                .findReference(reference.server(), reference.businessKey(), task).orElseThrow()
                                .state());
                journal(runtime).finishCleanup(task, id, OBSERVED.plusSeconds(11));
                sql(runtime, task, "UPDATE deployment_release SET state='ACTIVE'");
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"digest", "duplicate", "trailing", "utf8", "oversize", "binding", "missing-link",
            "result-units", "active-graph", "no-active"})
    void corruptHistoryOrCrossRecordEvidenceIsRejectedWithoutRepair(String fault) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var record = commit(runtime, task, graph("application"), 1);
                switch (fault) {
                    case "digest" ->
                        sql(runtime, task, "UPDATE deployment_release SET release_sha256='" + "c".repeat(64) + "'");
                    case "duplicate" -> replace(runtime, task, record.identity().id(), "runtime_payload",
                            record.runtimeJson()
                                    .replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")
                                    .getBytes(StandardCharsets.UTF_8));
                    case "trailing" -> replace(runtime, task, record.identity().id(), "health_payload",
                            (record.healthJson() + " {}").getBytes(StandardCharsets.UTF_8));
                    case "utf8" -> replace(runtime, task, record.identity().id(), "runtime_payload",
                            new byte[]{(byte) 0xc3, 0x28});
                    case "oversize" -> replace(runtime, task, record.identity().id(), "runtime_payload",
                            new byte[DeploymentReleaseCodec.LIMIT + 1]);
                    case "binding" -> {
                        var secret = runtime.modContexts().get(OWNER).context().secrets()
                                .create("deployment.configuration", new byte[]{42}, task);
                        new DeploymentSecretRepository(runtime.modContexts().get(OWNER).context().db()).saveRevision(
                                task, new DeploymentSecretRevision(new DeploymentSecretReference("added", 1),
                                        secret.id(), OBSERVED));
                        sql(runtime, task, "INSERT INTO deployment_unit_release_secret_reference VALUES('web','"
                                + "a".repeat(64) + "','added',1)");
                    }
                    case "missing-link" -> sql(runtime, task, "DELETE FROM deployment_release");
                    case "result-units" -> {
                        var execution = journal(runtime).find(task, record.executionId()).orElseThrow();
                        try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
                                var update = connection.prepareStatement(
                                        "UPDATE deployment_execution SET result_payload=? WHERE execution_id=?")) {
                            update.setBytes(1,
                                    execution.resultJson().orElseThrow()
                                            .replace("\"unitId\":\"web\"", "\"unitId\":\"other\"")
                                            .getBytes(StandardCharsets.UTF_8));
                            update.setObject(2, record.executionId());
                            update.executeUpdate();
                        }
                    }
                    case "active-graph" -> sql(runtime, task, "DELETE FROM managed_application_dependency");
                    case "no-active" -> sql(runtime, task, "UPDATE deployment_release SET state='RETIRED'");
                    default -> throw new AssertionError(fault);
                }
                return success();
            });
            try (var scope = scope(runtime, true)) {
                var report = new DeploymentSchema().validate(scope);
                assertFalse(report.valid());
                assertTrue(report.violations().contains("Stored deployment release history is invalid"),
                        report.toString());
            }
        }
    }

    @Test
    void contentIdentityCoversHealthAndDependencyChangesButNotPublicationTime() throws Exception {
        var graph = graph("application");
        var records = publications(graph, 1, List.of());
        var original = DeploymentReleaseSnapshot.capture(graph, records);
        var onlyHealth = new DeploymentApplicationGraph(graph.applicationId(), "worker", graph.healthCheck(),
                graph.components());
        var onlyDependencies = new DeploymentApplicationGraph(graph.applicationId(), graph.healthComponentId(),
                graph.healthCheck(),
                List.of(component("worker", "server", List.of()), component("web", "server", List.of())));
        String digest = DeploymentReleaseCodec.digest(DeploymentReleaseCodec.runtime(original),
                DeploymentReleaseCodec.health(original));
        for (var changed : List.of(onlyHealth, onlyDependencies)) {
            var snapshot = DeploymentReleaseSnapshot.capture(changed, records);
            assertNotEquals(digest, DeploymentReleaseCodec.digest(DeploymentReleaseCodec.runtime(snapshot),
                    DeploymentReleaseCodec.health(snapshot)));
        }
        var later = records.stream()
                .map(r -> new DeploymentUnitCommit(r.unit(), r.runtime(), new DeploymentCurrentRelease(r.unit().id(),
                        r.release().sha256(), r.release().publishedAt().plusSeconds(20)), r.configuration(),
                        r.secrets()))
                .toList();
        assertEquals(original, DeploymentReleaseSnapshot.capture(graph, later));
    }

    void replace(PlatformRuntime runtime, TaskContext task, UUID id, String column, byte[] bytes) throws Exception {
        assertTrue(Set.of("runtime_payload", "health_payload").contains(column));
        try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
                var update = connection
                        .prepareStatement("UPDATE deployment_release SET " + column + "=? WHERE release_id=?")) {
            update.setBytes(1, bytes);
            update.setObject(2, id);
            assertEquals(1, update.executeUpdate());
        }
    }
}

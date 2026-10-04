package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import org.junit.jupiter.api.Test;

class DeploymentConfigStoreTest extends DeploymentRuntimeFixture {
    DeploymentConfigSnapshot snapshot(long revision, String input) {
        return DeploymentConfigSnapshot.create("demo", revision, "v1",
            Instant.parse("2026-09-27T00:00:00.123456Z"), DeploymentConfigInput.parse(input));
    }

    void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var observed = new AtomicReference<Throwable>();
        var handle = context.tasks().submit(
            new TaskRequest("configuration_acceptance", "Configuration acceptance", List.of(), true), task -> {
                try {
                    return work.run(task);
                } catch (Exception | AssertionError failure) {
                    observed.set(failure);
                    throw failure;
                }
            });
        var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        if (observed.get() != null)
            throw new AssertionError("Original fixture work failed", observed.get());
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
    }

    OperationResult<Void> success() {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    @Test
    void immutableTypedConfigurationAndExactBindingSurviveRealRestart() throws Exception {
        enable();
        var accepted = snapshot(1, "PORT=8080;BUILD:FLAG=true;LABEL=production");
        try (var runtime = open()) {
            start(runtime, true);
            var repository = new DeploymentConfigStore(runtime.modContexts().get(OWNER).context().db());
            run(runtime, task -> {
                repository.saveAndBindRelease(task, accepted, "a".repeat(64));
                assertEquals(2, repository.nextRevision(task, "demo"));
                assertEquals(1, repository.nextRevision(task, "another-unit"));
                var loaded = repository.findRelease(task, "demo", "a".repeat(64)).orElseThrow();
                assertEquals(accepted.sha256(), loaded.sha256());
                assertEquals(accepted.createdAt().toEpochMilli(), loaded.createdAt().toEpochMilli());
                repository.save(task, DeploymentConfigSnapshot.create("demo", 1, "v1", accepted.createdAt(),
                    accepted.entries().reversed()));
                assertThrows(SQLException.class, () -> repository.save(task, snapshot(1, "PORT=9000")));
                assertThrows(SQLException.class,
                    () -> repository.saveAndBindRelease(task, snapshot(2, "PORT=9000"), "a".repeat(64)));
                assertTrue(repository.find(task, "demo", 2).isEmpty());
                assertThrows(SQLException.class, () -> repository.save(task, DeploymentConfigSnapshot
                    .create("demo", 1, "v1", accepted.createdAt().plusMillis(1), accepted.entries())));
                return success();
            });
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var repository = new DeploymentConfigStore(runtime.modContexts().get(OWNER).context().db());
                assertEquals(accepted.sha256(),
                    repository.findRelease(task, "demo", "a".repeat(64)).orElseThrow().sha256());
                assertEquals(2, repository.nextRevision(task, "demo"));
                assertTrue(repository.findRelease(task, "demo", "absent").isEmpty());
                return success();
            });
        }
    }

    @Test
    void fillingConfigurationDoesNotOverwritePreviouslyBoundSecretState() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.executeUpdate(
                        "INSERT INTO deployment_unit_release_binding(deployment_unit_id,release_sha256,is_secret_binding_complete) VALUES('demo','release',1)");
                }
                var repository = new DeploymentConfigStore(context.db());
                repository.saveAndBindRelease(task, snapshot(1, "PORT=8080"), "release");
                repository.saveAndBindRelease(task, snapshot(1, "PORT=8080"), "release");
                try (var connection = context.db().openConnection(task);
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM deployment_unit_release_binding WHERE is_secret_binding_complete=1 AND configuration_revision=1")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
                return success();
            });
        }
    }

    @Test
    void entryFailureRollsBackSnapshotAndBindingTogether() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute(
                        "ALTER TABLE deployment_unit_configuration_entry ADD CONSTRAINT fixture_value CHECK(value_text<>'9000')");
                }
                var repository = new DeploymentConfigStore(context.db());
                assertThrows(SQLException.class,
                    () -> repository.saveAndBindRelease(task, snapshot(1, "PORT=9000"), "release"));
                assertTrue(repository.find(task, "demo", 1).isEmpty());
                assertTrue(repository.findRelease(task, "demo", "release").isEmpty());
                repository.saveAndBindRelease(task, snapshot(1, "PORT=8080"), "release");
                return success();
            });
        }
    }

    @Test
    void corruptedTypesAndContentAreRejectedByReadsAndSchemaValidation() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                var repository = new DeploymentConfigStore(context.db());
                repository.save(task, snapshot(1, "FEATURE=true"));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    for (String damage : List.of("value_text='not-boolean'", "value_text='false'",
                        "value_text='true',configuration_scope='UNKNOWN'")) {
                        statement.executeUpdate("UPDATE deployment_unit_configuration_entry SET " + damage);
                        assertThrows(SQLException.class, () -> repository.find(task, "demo", 1));
                        try (var access = scope(runtime, true)) {
                            var report = new DeploymentSchema().validate(access);
                            assertFalse(report.valid());
                            assertTrue(report.violations()
                                .contains("Stored deployment configuration snapshots are invalid"));
                        }
                    }
                }
                return success();
            });
        }
    }

    @Test
    void lostCommitAcknowledgementIsUnknownAndExactDataCanBeReconciledWithoutOverwrite() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failOnce = new AtomicBoolean(true);
            var adapted = new ModDatabase() {
                public SchemaIdentity identity() throws ApiException {
                    return context.db().identity();
                }

                public Connection openConnection(TaskContext task) throws ApiException, SQLException {
                    var original = context.db().openConnection(task);
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            try {
                                var result = method.invoke(original, args);
                                if (method.getName().equals("commit") && failOnce.getAndSet(false))
                                    throw new SQLException("fixture lost acknowledgement");
                                return result;
                            } catch (InvocationTargetException wrapped) {
                                throw wrapped.getCause();
                            }
                        });
                }
            };
            run(runtime, task -> {
                var original = snapshot(1, "PORT=8080");
                var repository = new DeploymentConfigStore(adapted);
                var failure = assertThrows(ApiException.class,
                    () -> repository.saveAndBindRelease(task, original, "release"));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                assertFalse(failure.problem().message().contains("fixture"));
                assertEquals(original.sha256(), repository.findRelease(task, "demo", "release").orElseThrow().sha256());
                repository.saveAndBindRelease(task, original, "release");
                assertThrows(SQLException.class,
                    () -> repository.saveAndBindRelease(task, snapshot(1, "PORT=9000"), "release"));
                return success();
            });
        }
    }

    @Test
    void h2RevisionBoundsAndForeignOwnerCannotAdmitConfigurationWrites() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                var repository = new DeploymentConfigStore(context.db());
                assertThrows(SQLException.class,
                    () -> repository.save(task, snapshot(Integer.MAX_VALUE + 1L, "PORT=8080")));
                assertTrue(repository.find(task, "demo", Integer.MAX_VALUE + 1L).isEmpty());
                repository.save(task, snapshot(Integer.MAX_VALUE, "PORT=8080"));
                assertEquals(Integer.MAX_VALUE,
                    repository.find(task, "demo", Integer.MAX_VALUE).orElseThrow().revision());
                var foreign = new ModDatabase() {
                    public SchemaIdentity identity() {
                        return new SchemaIdentity("example.other", "1", UUID.randomUUID());
                    }

                    public Connection openConnection(TaskContext supplied) {
                        throw new AssertionError("Foreign generation must be rejected before JDBC");
                    }
                };
                assertThrows(IllegalArgumentException.class,
                    () -> new DeploymentConfigStore(foreign).save(task, snapshot(1, "PORT=80")));
                return success();
            });
        }
    }

    @Test
    void failedRollbackIsUnknownUntilOriginalDataHasBeenInspected() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute(
                        "ALTER TABLE deployment_unit_configuration_entry ADD CONSTRAINT fixture_reject CHECK(value_text<>'9000')");
                }
                var adapted = new ModDatabase() {
                    public SchemaIdentity identity() throws ApiException {
                        return context.db().identity();
                    }

                    public Connection openConnection(TaskContext originalTask) throws ApiException, SQLException {
                        var original = context.db().openConnection(originalTask);
                        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                if (method.getName().equals("rollback"))
                                    throw new SQLException("fixture rollback acknowledgement unavailable");
                                try {
                                    return method.invoke(original, args);
                                } catch (InvocationTargetException wrapped) {
                                    throw wrapped.getCause();
                                }
                            });
                    }
                };
                var failure = assertThrows(ApiException.class, () -> new DeploymentConfigStore(adapted)
                    .saveAndBindRelease(task, snapshot(1, "PORT=9000"), "release"));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_ROLLBACK_UNKNOWN", failure.problem().eventCode());
                var repository = new DeploymentConfigStore(context.db());
                assertTrue(repository.find(task, "demo", 1).isEmpty());
                assertTrue(repository.findRelease(task, "demo", "release").isEmpty());
                return success();
            });
        }
    }

    @Test
    void committedDataIsNotReportedAsRolledBackWhenConnectionCloseAcknowledgementFails() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                var adapted = new ModDatabase() {
                    public SchemaIdentity identity() throws ApiException {
                        return context.db().identity();
                    }

                    public Connection openConnection(TaskContext originalTask) throws ApiException, SQLException {
                        var original = context.db().openConnection(originalTask);
                        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                try {
                                    var result = method.invoke(original, args);
                                    if (method.getName().equals("close"))
                                        throw new SQLException("fixture close acknowledgement unavailable");
                                    return result;
                                } catch (InvocationTargetException wrapped) {
                                    throw wrapped.getCause();
                                }
                            });
                    }
                };
                var accepted = snapshot(1, "PORT=8080");
                var failure = assertThrows(ApiException.class, () -> new DeploymentConfigStore(adapted)
                    .saveAndBindRelease(task, accepted, "release"));
                assertEquals(ProblemCode.UNKNOWN, failure.problem().code());
                assertEquals("DEPLOYMENT_CONNECTION_RELEASE_UNKNOWN", failure.problem().eventCode());
                var repository = new DeploymentConfigStore(context.db());
                assertEquals(accepted.sha256(), repository.findRelease(task, "demo", "release").orElseThrow().sha256());
                return success();
            });
        }
    }

    @Test
    void concurrentDifferentRevisionsCannotRewriteTheSameRelease() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var repository = new DeploymentConfigStore(context.db());
            var barrier = new CyclicBarrier(2);
            var handles = new ArrayList<TaskHandle<Boolean>>();
            for (int i = 1; i <= 2; i++) {
                var input = snapshot(i, "PORT=" + (8080 + i));
                handles.add(context.tasks()
                    .submit(new TaskRequest("configuration_race", "Configuration race", List.of(), true), task -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        boolean saved;
                        try {
                            repository.saveAndBindRelease(task, input, "release");
                            saved = true;
                        } catch (SQLException conflict) {
                            saved = false;
                        }
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(saved), Optional.empty());
                    }));
            }
            int winners = 0;
            for (var handle : handles) {
                var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                if (result.value().orElseThrow())
                    winners++;
            }
            assertEquals(1, winners);
            run(runtime, task -> {
                var winner = repository.findRelease(task, "demo", "release").orElseThrow();
                assertTrue(repository.find(task, "demo", winner.revision() == 1 ? 2 : 1).isEmpty());
                return success();
            });
        }
    }
}

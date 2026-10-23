package gold.debug.windowstolinux.mod.deployment.db.setting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

class DeploymentPreferenceStoreTest extends DeploymentRuntimeFixture {
    private void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var observed = new AtomicReference<Throwable>();
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("preference_acceptance", "Actual deployment preferences", List.of(), true), task -> {
            try {
                return work.run(task);
            } catch (Exception | AssertionError failure) {
                observed.set(failure);
                throw failure;
            }
        });
        try {
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
        } catch (java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("Original preference task did not conclude: " + handle.snapshot(), observed.get() == null ? failure : observed.get());
        }
        if (observed.get() != null) throw new AssertionError("Original preference work failed", observed.get());
    }

    private OperationResult<Void> success() {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    @Test
    void initializesOnlyMissingDeploymentPreferencesAndRejectsInvalidValues() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var repository = new DeploymentPreferenceStore(context.db());
            run(runtime, task -> {
                assertEquals(new DeploymentPreferenceStore.Selection("ASSISTED", "AUTOMATIC", true), repository.read(task));
                repository.save(task, new DeploymentPreferenceStore.Selection("STATIC", "FULL_CONTROL", true));
                assertEquals(new DeploymentPreferenceStore.Selection("STATIC", "FULL_CONTROL", true), repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("DELETE FROM preference WHERE preference_key='deploy_approval'");
                }
                assertEquals(new DeploymentPreferenceStore.Selection("STATIC", "AUTOMATIC", true), repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("DELETE FROM preference WHERE preference_key='deploy_remember_authorization'");
                }
                assertEquals(new DeploymentPreferenceStore.Selection("STATIC", "AUTOMATIC", true), repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("UPDATE preference SET preference_value='INVALID' WHERE preference_key='deploy_mode'");
                }
                assertThrows(IllegalArgumentException.class, () -> repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT preference_value FROM preference WHERE preference_key='deploy_mode'")) {
                    assertTrue(rows.next());
                    assertEquals("INVALID", rows.getString(1));
                }
                assertThrows(IllegalArgumentException.class, () -> new DeploymentPreferenceStore.Selection("STATIC", "INVALID", true));
                repository.save(task, new DeploymentPreferenceStore.Selection("STATIC", "AUTOMATIC", true));
                return success();
            });
        }
    }

    @Test
    void allThreeActualSelectionValuesSurviveTheOriginalRuntimeRestart() throws Exception {
        enable();
        var selection = new DeploymentPreferenceStore.Selection("AGENT", "FULL_CONTROL", true);
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                new DeploymentPreferenceStore(runtime.modContexts().get(OWNER).context().db()).save(task, selection);
                return success();
            });
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                assertEquals(selection, new DeploymentPreferenceStore(runtime.modContexts().get(OWNER).context().db()).read(task));
                return success();
            });
        }
    }

    @Test
    void rejectedThirdPreferenceWriteRollsBackTheWholeOriginalTriple() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var repository = new DeploymentPreferenceStore(context.db());
            run(runtime, task -> {
                var original = repository.read(task);
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE preference ADD CONSTRAINT rejected_authorization CHECK(preference_key<>'deploy_remember_authorization' OR preference_value<>'false')");
                }
                assertThrows(java.sql.SQLException.class, () -> repository.save(task, new DeploymentPreferenceStore.Selection("STATIC", "FULL_CONTROL", false)));
                assertEquals(original, repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE preference DROP CONSTRAINT rejected_authorization");
                }
                return success();
            });
        }
    }

    @Test
    void allCurrentPoliciesPersistAndMalformedBooleanIsNotCoercedOrRepaired() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var repository = new DeploymentPreferenceStore(context.db());
            run(runtime, task -> {
                for (var approval : List.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL")) {
                    for (boolean rememberAuthorization : List.of(false, true)) {
                        var selection = new DeploymentPreferenceStore.Selection("STATIC", approval, rememberAuthorization);
                        repository.save(task, selection);
                        assertEquals(selection, repository.read(task));
                    }
                }
                for (String invalid : List.of("GENERAL", "TRUE", "1", "")) {
                    try (var connection = context.db().openConnection(task); var statement = connection.prepareStatement(
                        "UPDATE preference SET preference_value=? WHERE preference_key='deploy_remember_authorization'")) {
                        statement.setString(1, invalid);
                        assertEquals(1, statement.executeUpdate());
                    }
                    assertThrows(java.sql.SQLException.class, () -> repository.read(task));
                    try (var connection = context.db().openConnection(task); var statement = connection.createStatement();
                         var rows = statement.executeQuery("SELECT preference_value FROM preference WHERE preference_key='deploy_remember_authorization'")) {
                        assertTrue(rows.next());
                        assertEquals(invalid, rows.getString(1));
                    }
                }
                repository.save(task, new DeploymentPreferenceStore.Selection("STATIC", "AUTOMATIC", true));
                return success();
            });
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.db;

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

class DeploymentPreferencesTest extends DeploymentRuntimeFixture {
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
            var repository = new DeploymentPreferences(context.db());
            run(runtime, task -> {
                assertEquals(new DeploymentPreferences.Selection("ASSISTED", "AUTOMATIC"), repository.read(task));
                repository.save(task, new DeploymentPreferences.Selection("STATIC", "FULL_CONTROL"));
                assertEquals(new DeploymentPreferences.Selection("STATIC", "FULL_CONTROL"), repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("DELETE FROM preference WHERE preference_key='deploy_approval'");
                }
                assertEquals(new DeploymentPreferences.Selection("STATIC", "AUTOMATIC"), repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("UPDATE preference SET preference_value='INVALID' WHERE preference_key='deploy_mode'");
                }
                assertThrows(IllegalArgumentException.class, () -> repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT preference_value FROM preference WHERE preference_key='deploy_mode'")) {
                    assertTrue(rows.next());
                    assertEquals("INVALID", rows.getString(1));
                }
                assertThrows(IllegalArgumentException.class, () -> new DeploymentPreferences.Selection("STATIC", "INVALID"));
                repository.save(task, new DeploymentPreferences.Selection("STATIC", "AUTOMATIC"));
                return success();
            });
        }
    }

    @Test
    void bothActualSelectionValuesSurviveTheOriginalRuntimeRestart() throws Exception {
        enable();
        var selection = new DeploymentPreferences.Selection("AGENT", "FULL_CONTROL");
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                new DeploymentPreferences(runtime.modContexts().get(OWNER).context().db()).save(task, selection);
                return success();
            });
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                assertEquals(selection, new DeploymentPreferences(runtime.modContexts().get(OWNER).context().db()).read(task));
                return success();
            });
        }
    }

    @Test
    void rejectedSecondPreferenceWriteRollsBackTheWholeOriginalPair() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var repository = new DeploymentPreferences(context.db());
            run(runtime, task -> {
                var original = repository.read(task);
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE preference ADD CONSTRAINT rejected_approval CHECK(preference_key<>'deploy_approval' OR preference_value<>'FULL_CONTROL')");
                }
                assertThrows(java.sql.SQLException.class, () -> repository.save(task, new DeploymentPreferences.Selection("STATIC", "FULL_CONTROL")));
                assertEquals(original, repository.read(task));
                try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE preference DROP CONSTRAINT rejected_approval");
                }
                return success();
            });
        }
    }
}

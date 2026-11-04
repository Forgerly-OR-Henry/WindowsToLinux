package gold.debug.windowstolinux.mod.deployment.db.setting;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;

class DeploymentMirrorStoreTest extends DeploymentRuntimeFixture {
    private void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var observed = new AtomicReference<Throwable>();
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("mirror_preference_test", "Mirror preferences", List.of(), true), task -> {
            try { return work.run(task); }
            catch (Exception | AssertionError e) { observed.set(e); throw e; }
        });
        var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        if (observed.get() != null) throw new AssertionError(observed.get());
        assertEquals(TaskResult.SUCCEEDED, result.outcome());
    }
    private OperationResult<Void> success() { return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty()); }
    @Test void mirrorJsonSurvivesRestartWithoutChangingDeploymentPreferences() throws Exception {
        enable();
        var selection = new MirrorSettings(1, Map.of(DownloadPoint.MAVEN_REPOSITORY,
            List.of(new MirrorSettings.Row("huawei", "", false, false, ""), MirrorSettings.Row.auto(true))), Map.of("server", "IR"));
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            run(runtime, task -> {
                var store = new DeploymentMirrorStore(context.db());
                assertEquals(MirrorSettings.defaults(), store.read(task));
                new DeploymentPreferenceStore(context.db()).save(task,
                    new DeploymentPreferenceStore.Selection("STATIC", "MANUAL_REVIEW", false));
                store.save(task, selection);
                assertEquals(selection, store.read(task));
                assertEquals(new DeploymentPreferenceStore.Selection("STATIC", "MANUAL_REVIEW", false),
                    new DeploymentPreferenceStore(context.db()).read(task));
                return success();
            });
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var database = runtime.modContexts().get(OWNER).context().db();
                var store = new DeploymentMirrorStore(database);
                assertEquals(selection, store.read(task));
                try (var connection = database.openConnection(task); var statement = connection.prepareStatement(
                    "UPDATE preference SET preference_value='{}' WHERE preference_key=?")) {
                    statement.setString(1, DeploymentMirrorStore.KEY); statement.executeUpdate();
                }
                assertThrows(java.sql.SQLException.class, () -> store.read(task));
                return success();
            });
        }
    }
    @Test void consentCompareAndSavePreservesNewerUiPreferences() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var store = new DeploymentMirrorStore(runtime.modContexts().get(OWNER).context().db());
                var expected = MirrorSettings.defaults();
                var rows = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
                expected.points().forEach((point, ignored) -> rows.put(point, List.of(MirrorSettings.Row.auto(true))));
                var consented = new MirrorSettings(1, rows, Map.of());
                assertTrue(store.saveConsent(task, expected, consented)); // Missing row is defaults, inserted once.
                var newerUi = new MirrorSettings(1, Map.of(DownloadPoint.MAVEN_REPOSITORY,
                    List.of(new MirrorSettings.Row("huawei", "", false, false, ""))), Map.of("server", "IR"));
                store.save(task, newerUi);
                assertFalse(store.saveConsent(task, consented, consented)); // Stale confirmation must not overwrite UI.
                assertEquals(newerUi, store.read(task));
                assertFalse(store.saveConsent(task, expected, consented));
                assertEquals(newerUi, store.read(task));
                return success();
            });
        }
    }
}

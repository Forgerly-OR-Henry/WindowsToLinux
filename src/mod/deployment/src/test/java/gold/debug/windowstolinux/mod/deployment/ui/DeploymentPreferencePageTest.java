package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Checks actual CLASS page edits before any deployment is started. / 在任何部署开始前检查实际CLASS页面编辑。 */
class DeploymentPreferencePageTest extends DeploymentRuntimeFixture {
    @Test
    void actualPageEditsPersistWithoutStartingDeploymentAndKnownWriteFailureRestoresConfirmedControls() throws Exception {
        enable();
        var mounted = new AtomicReference<JComponent>();
        var pages = registry(mounted);
        var staticKey = new ModPageRegistry.PageKey(OWNER, "static-deployment");
        var agentKey = new ModPageRegistry.PageKey(OWNER, "agent-deployment");
        JFrame window = new JFrame("Actual CLASS deployment preferences");
        try (var runtime = open()) {
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            pages.openView(staticKey);
            show(window, mounted.get());
            var approval = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var assisted = (JCheckBox) named(mounted.get(), "deployment.preference.assisted");
            await(() -> approval.isEnabled());
            edt(() -> {
                assertTrue(assisted.isSelected());
                assertEquals("AUTOMATIC", approval.getSelectedItem());
                assisted.doClick();
            });
            await(() -> approval.isEnabled());
            edt(() -> approval.setSelectedItem("FULL_CONTROL"));
            await(() -> approval.isEnabled());
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", false));
            edt(() -> {
                assertFalse(assisted.isSelected());
                assertEquals("FULL_CONTROL", approval.getSelectedItem());
            });
            pages.closeView(staticKey);
            pages.openView(staticKey);
            show(window, mounted.get());
            var reopened = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            await(() -> reopened.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", reopened.getSelectedItem());
                assertFalse(((JCheckBox) named(mounted.get(), "deployment.preference.assisted")).isSelected());
            });
            assertPair(runtime, "STATIC", "FULL_CONTROL");
            sql(runtime, "ALTER TABLE preference ADD CONSTRAINT reject_page_approval CHECK(preference_key<>'deploy_approval' OR preference_value<>'MANUAL_REVIEW')");
            edt(() -> reopened.setSelectedItem("MANUAL_REVIEW"));
            await(() -> reopened.isEnabled());
            edt(() -> assertEquals("FULL_CONTROL", reopened.getSelectedItem()));
            assertPair(runtime, "STATIC", "FULL_CONTROL");
            sql(runtime, "ALTER TABLE preference DROP CONSTRAINT reject_page_approval");
            pages.closeView(staticKey);
            pages.openView(agentKey);
            show(window, mounted.get());
            var agent = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            await(() -> agent.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", agent.getSelectedItem());
                agent.setSelectedItem("MANUAL_REVIEW");
            });
            await(() -> agent.isEnabled());
            pages.closeView(agentKey);
            pages.openView(agentKey);
            show(window, mounted.get());
            var agentReopened = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            await(() -> agentReopened.isEnabled());
            edt(() -> assertEquals("MANUAL_REVIEW", agentReopened.getSelectedItem()));
            assertPair(runtime, "AGENT", "MANUAL_REVIEW");
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_type IN ('deployment-publication','deployment-agent')"));
            pages.closeView(agentKey);
            await(() -> scalarUnchecked(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'") == 0);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            edt(window::dispose);
        }
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
            && (thread.getName().equals("deployment-page-view") || thread.getName().equals("deployment-agent-page"))));
    }

    private ModPageRegistry registry(AtomicReference<JComponent> mounted) {
        return new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                mounted.set(component);
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
                mounted.set(null);
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
    }

    private void show(JFrame window, JComponent component) throws Exception {
        edt(() -> {
            window.setContentPane(component);
            window.pack();
            window.setVisible(true);
        });
    }

    private Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private void await(BooleanSupplier predicate) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var result = new boolean[1];
        do {
            edt(() -> result[0] = predicate.getAsBoolean());
            if (result[0]) return;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        fail("Actual page/task did not reach its expected state");
    }

    private long scalarUnchecked(PlatformRuntime runtime, String query) {
        try {
            return scalar(runtime, query);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private void assertPair(PlatformRuntime runtime, String mode, String approval) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("preference_page_assert", "Read actual page preferences", List.of(), true), task -> {
            var actual = new gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences(context.db()).read(task);
            assertEquals(new gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection(mode, approval), actual);
            try (var connection = context.db().openConnection(task); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_execution")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        });
        assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
    }

    private void sql(PlatformRuntime runtime, String query) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("preference_page_fault", "Controlled preference constraint", List.of(), true), task -> {
            try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                statement.execute(query);
            }
            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        });
        assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
    }
}

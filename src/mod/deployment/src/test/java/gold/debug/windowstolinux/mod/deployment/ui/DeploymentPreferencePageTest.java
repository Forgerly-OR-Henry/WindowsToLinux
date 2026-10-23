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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks actual CLASS page edits before any deployment is started. / 在任何部署开始前检查实际CLASS页面编辑。 */
class DeploymentPreferencePageTest extends DeploymentRuntimeFixture {
    @Test
    void actualPageEditsPersistWithoutStartingDeploymentAndKnownWriteFailureRestoresConfirmedControls() throws Exception {
        enable();
        var mounted = new AtomicReference<JComponent>();
        var pages = registry(mounted);
        var workspaceKey = new ModPageRegistry.PageKey(OWNER, "workspace");
        var originalThreads = java.util.Set.copyOf(Thread.getAllStackTraces().keySet());
        JFrame window = new JFrame("Actual CLASS deployment preferences");
        try (var runtime = open()) {
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            pages.navigateView(workspaceKey, "static-deployment");
            show(window, mounted.get());
            var approval = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var rememberAuthorization = (JCheckBox) named(mounted.get(), "deployment.preference.rememberAuthorization");
            var assisted = (JCheckBox) named(mounted.get(), "deployment.preference.assisted");
            await(() -> approval.isEnabled());
            edt(() -> {
                assertTrue(assisted.isSelected());
                assertEquals("AUTOMATIC", approval.getSelectedItem());
                assertEquals(true, rememberAuthorization.isSelected());
                assisted.doClick();
            });
            await(() -> approval.isEnabled());
            edt(() -> approval.setSelectedItem("FULL_CONTROL"));
            await(() -> approval.isEnabled());
            edt(() -> assertFalse(rememberAuthorization.isVisible()));
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", false));
            edt(() -> {
                assertFalse(assisted.isSelected());
                assertEquals("FULL_CONTROL", approval.getSelectedItem());
                assertEquals(true, rememberAuthorization.isSelected());
            });
            pages.closeView(workspaceKey);
            pages.navigateView(workspaceKey, "static-deployment");
            show(window, mounted.get());
            var reopened = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var reopenedAuthorization = (JCheckBox) named(mounted.get(), "deployment.preference.rememberAuthorization");
            await(() -> reopened.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", reopened.getSelectedItem());
                assertEquals(true, reopenedAuthorization.isSelected());
                assertFalse(((JCheckBox) named(mounted.get(), "deployment.preference.assisted")).isSelected());
            });
            assertTriple(runtime, "STATIC", "FULL_CONTROL", true);
            sql(runtime, "ALTER TABLE preference ADD CONSTRAINT reject_page_authorization CHECK(preference_key<>'deploy_approval' OR preference_value<>'MANUAL_REVIEW')");
            edt(() -> reopened.setSelectedItem("MANUAL_REVIEW"));
            await(() -> reopened.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", reopened.getSelectedItem());
                assertEquals(true, reopenedAuthorization.isSelected());
            });
            assertTriple(runtime, "STATIC", "FULL_CONTROL", true);
            sql(runtime, "ALTER TABLE preference DROP CONSTRAINT reject_page_authorization");
            pages.closeView(workspaceKey);
            pages.navigateView(workspaceKey, "agent-deployment");
            show(window, mounted.get());
            var agent = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var agentAuthorization = (JCheckBox) named(mounted.get(), "deployment.preference.rememberAuthorization");
            await(() -> agent.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", agent.getSelectedItem());
                assertEquals(true, agentAuthorization.isSelected());
                agent.setSelectedItem("MANUAL_REVIEW");
            });
            await(() -> agent.isEnabled());
            edt(() -> assertEquals(true, agentAuthorization.isSelected()));
            pages.closeView(workspaceKey);
            pages.navigateView(workspaceKey, "agent-deployment");
            show(window, mounted.get());
            var agentReopened = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            await(() -> agentReopened.isEnabled());
            edt(() -> assertEquals("MANUAL_REVIEW", agentReopened.getSelectedItem()));
            assertTriple(runtime, "AGENT", "MANUAL_REVIEW", true);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_type IN ('deployment-publication','deployment-agent')"));
            pages.closeView(workspaceKey);
            awaitClosedWorkers(originalThreads);
            await(() -> scalarUnchecked(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'") == 0);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            edt(window::dispose);
        }
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
            && (thread.getName().equals("deployment-page-view") || thread.getName().equals("deployment-agent-page"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"static-deployment", "agent-deployment"})
    void actualPagesExposeCurrentCheckboxAndPreserveCapturedSelectionDuringLock(String view) throws Exception {
        enable();
        var mounted = new AtomicReference<JComponent>();
        var pages = registry(mounted);
        var key = new ModPageRegistry.PageKey(OWNER, "workspace");
        var originalThreads = java.util.Set.copyOf(Thread.getAllStackTraces().keySet());
        var window = new JFrame("Actual deployment rememberAuthorization controls");
        try (var runtime = open()) {
            assertTrue(runtime.startDevelopmentMods(workspace(), pages).problems().isEmpty());
            pages.navigateView(key, view);
            show(window, mounted.get());
            var approval = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var rememberAuthorization = (JCheckBox) named(mounted.get(), "deployment.preference.rememberAuthorization");
            var mode = view.equals("agent-deployment") ? "AGENT" : "ASSISTED";
            await(approval::isEnabled);
            edit(approval, "MANUAL_REVIEW");
            assertTriple(runtime, mode, "MANUAL_REVIEW", true);
            edt(rememberAuthorization::doClick);
            await(approval::isEnabled);
            assertTriple(runtime, mode, "MANUAL_REVIEW", false);
            edit(approval, "AUTOMATIC");
            assertTriple(runtime, mode, "AUTOMATIC", false);
            edt(rememberAuthorization::doClick);
            await(approval::isEnabled);
            assertTriple(runtime, mode, "AUTOMATIC", true);
            edit(approval, "FULL_CONTROL");
            edt(() -> {
                assertFalse(rememberAuthorization.isVisible());
                assertFalse(rememberAuthorization.isEnabled());
            });
            assertTriple(runtime, mode, "FULL_CONTROL", true);
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", true));
            edt(() -> {
                assertFalse(approval.isEnabled());
                assertFalse(rememberAuthorization.isEnabled());
                approval.setSelectedItem("AUTOMATIC");
                rememberAuthorization.doClick();
                assertEquals("FULL_CONTROL", approval.getSelectedItem());
                assertEquals(true, rememberAuthorization.isSelected());
            });
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", false));
            await(approval::isEnabled);
            assertTriple(runtime, mode, "FULL_CONTROL", true);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_type IN ('deployment-publication','deployment-agent')"));
            pages.closeView(key);
            awaitClosedWorkers(originalThreads);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            edt(window::dispose);
        }
    }

    private void edit(JComboBox<?> choice, String value) throws Exception {
        edt(() -> choice.setSelectedItem(value));
        await(choice::isEnabled);
        edt(() -> assertEquals(value, choice.getSelectedItem()));
    }

    private void awaitClosedWorkers(java.util.Set<Thread> originalThreads) throws Exception {
        var owned = Thread.getAllStackTraces().keySet().stream().filter(thread -> !originalThreads.contains(thread)
            && List.of("deployment-page-view", "deployment-agent-page", "deployment-workspace-preferences").contains(thread.getName())).toList();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (var worker : owned) {
            long remaining = until - System.nanoTime();
            assertTrue(remaining > 0);
            worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            assertFalse(worker.isAlive(), "Only an original worker of this closed workspace must exit before host cleanup");
        }
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

    private void assertTriple(PlatformRuntime runtime, String mode, String approval, boolean rememberAuthorization) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("preference_page_assert", "Read actual page preferences", List.of(), true), task -> {
            var actual = new gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore(context.db()).read(task);
            assertEquals(new gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection(mode, approval, rememberAuthorization), actual);
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

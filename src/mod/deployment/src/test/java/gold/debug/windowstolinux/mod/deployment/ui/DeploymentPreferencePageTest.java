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
            var authorization = (JComboBox<?>) named(mounted.get(), "deployment.preference.authorization");
            var assisted = (JCheckBox) named(mounted.get(), "deployment.preference.assisted");
            await(() -> approval.isEnabled());
            edt(() -> {
                assertTrue(assisted.isSelected());
                assertEquals("AUTOMATIC", approval.getSelectedItem());
                assertEquals("GENERAL", authorization.getSelectedItem());
                assisted.doClick();
            });
            await(() -> approval.isEnabled());
            edt(() -> approval.setSelectedItem("FULL_CONTROL"));
            await(() -> approval.isEnabled());
            edt(() -> authorization.setSelectedItem("FULL"));
            await(() -> authorization.isEnabled());
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", false));
            edt(() -> {
                assertFalse(assisted.isSelected());
                assertEquals("FULL_CONTROL", approval.getSelectedItem());
                assertEquals("FULL", authorization.getSelectedItem());
            });
            pages.closeView(workspaceKey);
            pages.navigateView(workspaceKey, "static-deployment");
            show(window, mounted.get());
            var reopened = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var reopenedAuthorization = (JComboBox<?>) named(mounted.get(), "deployment.preference.authorization");
            await(() -> reopened.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", reopened.getSelectedItem());
                assertEquals("FULL", reopenedAuthorization.getSelectedItem());
                assertFalse(((JCheckBox) named(mounted.get(), "deployment.preference.assisted")).isSelected());
            });
            assertTriple(runtime, "STATIC", "FULL_CONTROL", "FULL");
            sql(runtime, "ALTER TABLE preference ADD CONSTRAINT reject_page_authorization CHECK(preference_key<>'deploy_authorization' OR preference_value<>'GENERAL')");
            edt(() -> reopened.setSelectedItem("MANUAL_REVIEW"));
            await(() -> reopened.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", reopened.getSelectedItem());
                assertEquals("FULL", reopenedAuthorization.getSelectedItem());
            });
            assertTriple(runtime, "STATIC", "FULL_CONTROL", "FULL");
            sql(runtime, "ALTER TABLE preference DROP CONSTRAINT reject_page_authorization");
            pages.closeView(workspaceKey);
            pages.navigateView(workspaceKey, "agent-deployment");
            show(window, mounted.get());
            var agent = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var agentAuthorization = (JComboBox<?>) named(mounted.get(), "deployment.preference.authorization");
            await(() -> agent.isEnabled());
            edt(() -> {
                assertEquals("FULL_CONTROL", agent.getSelectedItem());
                assertEquals("FULL", agentAuthorization.getSelectedItem());
                agent.setSelectedItem("MANUAL_REVIEW");
            });
            await(() -> agent.isEnabled());
            edt(() -> assertEquals("GENERAL", agentAuthorization.getSelectedItem()));
            pages.closeView(workspaceKey);
            pages.navigateView(workspaceKey, "agent-deployment");
            show(window, mounted.get());
            var agentReopened = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            await(() -> agentReopened.isEnabled());
            edt(() -> assertEquals("MANUAL_REVIEW", agentReopened.getSelectedItem()));
            assertTriple(runtime, "AGENT", "MANUAL_REVIEW", "GENERAL");
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
    void actualPagesEnforceSevenPolicyPairsForKeyboardMouseProgrammaticEditsAndLock(String view) throws Exception {
        enable();
        var mounted = new AtomicReference<JComponent>();
        var pages = registry(mounted);
        var key = new ModPageRegistry.PageKey(OWNER, "workspace");
        var originalThreads = java.util.Set.copyOf(Thread.getAllStackTraces().keySet());
        var window = new JFrame("Actual deployment authorization controls");
        try (var runtime = open()) {
            assertTrue(runtime.startDevelopmentMods(workspace(), pages).problems().isEmpty());
            pages.navigateView(key, view);
            show(window, mounted.get());
            var approval = (JComboBox<?>) named(mounted.get(), "deployment.preference.approval");
            var authorization = (JComboBox<?>) named(mounted.get(), "deployment.preference.authorization");
            var mode = view.equals("agent-deployment") ? "AGENT" : "ASSISTED";
            await(approval::isEnabled);
            edit(approval, "MANUAL_REVIEW");
            assertTriple(runtime, mode, "MANUAL_REVIEW", "GENERAL");
            assertGreyChoice(authorization, "FULL", 2);
            edt(() -> authorization.setSelectedItem("FULL"));
            edt(() -> assertEquals("GENERAL", authorization.getSelectedItem()));
            clickPopupChoice(authorization, 2);
            edt(() -> assertEquals("GENERAL", authorization.getSelectedItem()));
            edit(authorization, "ONCE");
            assertTriple(runtime, mode, "MANUAL_REVIEW", "ONCE");
            edt(authorization::requestFocusInWindow);
            await(authorization::isFocusOwner);
            var robot = new Robot();
            robot.keyPress(java.awt.event.KeyEvent.VK_END);
            robot.keyRelease(java.awt.event.KeyEvent.VK_END);
            robot.waitForIdle();
            edt(() -> assertEquals("ONCE", authorization.getSelectedItem()));
            edit(approval, "AUTOMATIC");
            assertTriple(runtime, mode, "AUTOMATIC", "ONCE");
            edit(approval, "FULL_CONTROL");
            edt(() -> assertEquals("GENERAL", authorization.getSelectedItem(), "Switching from ONCE to full control must reset only the draft to GENERAL"));
            assertTriple(runtime, mode, "FULL_CONTROL", "GENERAL");
            edit(approval, "AUTOMATIC");
            edit(authorization, "GENERAL");
            assertTriple(runtime, mode, "AUTOMATIC", "GENERAL");
            edit(authorization, "FULL");
            assertTriple(runtime, mode, "AUTOMATIC", "FULL");
            edt(() -> assertTrue(((JTextArea) named(mounted.get(), "deployment.preference.authorizationDescription"))
                .getText().contains("HIGH"), "Automatic full authorization must explain its HIGH alternative policy"));
            edit(approval, "FULL_CONTROL");
            assertTriple(runtime, mode, "FULL_CONTROL", "FULL");
            assertGreyChoice(authorization, "ONCE", 0);
            edt(() -> authorization.setSelectedIndex(0));
            edt(() -> assertEquals("FULL", authorization.getSelectedItem()));
            clickPopupChoice(authorization, 0);
            edt(() -> assertEquals("FULL", authorization.getSelectedItem()));
            edit(authorization, "GENERAL");
            assertTriple(runtime, mode, "FULL_CONTROL", "GENERAL");
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", true));
            edt(() -> {
                assertFalse(approval.isEnabled());
                assertFalse(authorization.isEnabled());
                approval.setSelectedItem("AUTOMATIC");
                authorization.setSelectedItem("FULL");
                assertEquals("FULL_CONTROL", approval.getSelectedItem());
                assertEquals("GENERAL", authorization.getSelectedItem());
            });
            pages.environment(new UiEnvironment(Locale.ENGLISH, "DARK", false));
            await(approval::isEnabled);
            assertTriple(runtime, mode, "FULL_CONTROL", "GENERAL");
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertGreyChoice(JComboBox<?> choice, String value, int index) throws Exception {
        edt(() -> {
            assertEquals(value, choice.getItemAt(index), "Unsupported combinations remain visible");
            var renderer = (ListCellRenderer) choice.getRenderer();
            assertFalse(renderer.getListCellRendererComponent(new JList<>(), value, index, false, false).isEnabled());
        });
    }

    private void clickPopupChoice(JComboBox<?> choice, int index) throws Exception {
        var location = new AtomicReference<Point>();
        edt(() -> {
            choice.showPopup();
            var popup = (javax.swing.plaf.basic.BasicComboPopup) choice.getAccessibleContext().getAccessibleChild(0);
            var list = popup.getList();
            var bounds = list.getCellBounds(index, index);
            var point = list.getLocationOnScreen();
            point.translate(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            location.set(point);
        });
        var robot = new Robot();
        robot.mouseMove(location.get().x, location.get().y);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        edt(choice::hidePopup);
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

    private void assertTriple(PlatformRuntime runtime, String mode, String approval, String authorization) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("preference_page_assert", "Read actual page preferences", List.of(), true), task -> {
            var actual = new gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences(context.db()).read(task);
            assertEquals(new gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection(mode, approval, authorization), actual);
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

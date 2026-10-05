package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;

class DeploymentWorkspaceTest {
    private static final UiEnvironment INITIAL = new UiEnvironment(Locale.ENGLISH, "LIGHT", false);

    static class Page implements ModPage {
        final JTextField draft = new JTextField();

        int creates, closes, notifications, failures;

        boolean failCreate;

        UiEnvironment environment;

        public JComponent create(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            creates++;
            this.environment = environment;
            if (failCreate) throw new IllegalStateException("Fixture creation failed");
            return draft;
        }

        public void environmentChanged(UiEnvironment environment) {
            notifications++;
            this.environment = environment;
        }

        public void close() {
            assertTrue(SwingUtilities.isEventDispatchThread());
            closes++;
            if (failures-- > 0) throw new IllegalStateException("Fixture cleanup pending");
        }
    }

    private static void edt(Checked action) throws Exception {
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() instanceof Exception error) throw error;
        if (failure.get() instanceof Error error) throw error;
    }

    @FunctionalInterface
    interface Checked {
        void run() throws Exception;
    }

    @Test
    void internalViewsAreLazyRetainDraftsAndReceiveEnvironmentWhileHidden() throws Exception {
        var standard = new Page();
        var agent = new Page();
        var applications = new Page();
        var workspace = new DeploymentWorkspace(() -> standard, () -> agent, () -> applications);
        assertEquals(0, standard.creates);
        edt(() -> {
            var root = workspace.create(INITIAL);
            var tabs = (JTabbedPane) root.getComponent(0);
            assertEquals(1, standard.creates);
            assertEquals(0, agent.creates);
            standard.draft.setText("unsubmitted source");
            tabs.setSelectedIndex(1);
            assertFalse(standard.draft.isShowing());
            agent.draft.setText("retained agent requirements");
            tabs.setSelectedIndex(0);
            assertEquals("unsubmitted source", standard.draft.getText());
            assertEquals(1, standard.creates);
            assertEquals(0, applications.creates);
            var locked = new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "DARK", true);
            workspace.environmentChanged(locked);
            assertEquals(locked, agent.environment);
            assertEquals(locked, standard.environment);
            assertTrue(tabs.getTitleAt(0).contains("标准"));
            tabs.setSelectedIndex(2);
            assertEquals(locked, applications.environment);
            workspace.close();
            assertEquals(1, standard.closes);
            assertEquals(1, agent.closes);
            assertEquals(1, applications.closes);
        });
    }

    @Test
    void startupRestoresOnlyTheSelectedInternalViewAndKeepsStableIdentity() throws Exception {
        for (int index : new int[]{1, 2}) {
            var standard = new Page();
            var agent = new Page();
            var applications = new Page();
            var workspace = new DeploymentWorkspace(() -> standard, () -> agent, () -> applications);
            String id = index == 1 ? "agent-deployment" : "applications";
            edt(() -> {
                workspace.restoreNavigation(id);
                assertEquals(id, workspace.navigationId());
                var tabs = (JTabbedPane) workspace.create(INITIAL).getComponent(0);
                assertEquals(index, tabs.getSelectedIndex());
                assertEquals(0, standard.creates);
                assertEquals(index == 1 ? 1 : 0, agent.creates);
                assertEquals(index == 2 ? 1 : 0, applications.creates);
                workspace.restoreNavigation("removed.internal.page");
                assertEquals(id, workspace.navigationId());
                workspace.close();
            });
        }
    }

    @Test
    void failedSiblingCleanupKeepsItsInstanceAndDoesNotRepeatSuccessfulClose() throws Exception {
        var standard = new Page();
        var agent = new Page();
        var applications = new Page();
        agent.failures = 1;
        var workspace = new DeploymentWorkspace(() -> standard, () -> agent, () -> applications);
        edt(() -> {
            var tabs = (JTabbedPane) workspace.create(INITIAL).getComponent(0);
            tabs.setSelectedIndex(1);
            tabs.setSelectedIndex(2);
            assertThrows(Exception.class, workspace::close);
            assertEquals(1, standard.closes);
            assertEquals(1, agent.closes);
            assertEquals(1, applications.closes);
            workspace.close();
            workspace.close();
            assertEquals(1, standard.closes);
            assertEquals(2, agent.closes);
            assertEquals(1, applications.closes);
        });
    }

    @Test
    void partiallyCreatedInternalViewIsRetainedUntilExplicitRetryCleansIt() throws Exception {
        var standard = new Page();
        var bad = new Page();
        var replacement = new Page();
        bad.failCreate = true;
        bad.failures = 1;
        var factories = new java.util.concurrent.atomic.AtomicInteger();
        var workspace = new DeploymentWorkspace(() -> standard,
            () -> factories.getAndIncrement() == 0 ? bad : replacement, Page::new);
        edt(() -> {
            var root = workspace.create(INITIAL);
            var tabs = (JTabbedPane) root.getComponent(0);
            tabs.setSelectedIndex(1);
            tabs.setSelectedIndex(0);
            tabs.setSelectedIndex(1);
            assertEquals(1, factories.get());
            var feedback = (JPanel) root.getComponent(1);
            assertTrue(feedback.isVisible());
            var retry = (JButton) feedback.getComponent(1);
            retry.doClick(0);
            assertEquals(1, factories.get());
            retry.doClick(0);
            assertEquals(2, factories.get());
            assertEquals(2, bad.closes);
            assertEquals(1, replacement.creates);
            assertFalse(feedback.isVisible());
            workspace.close();
            assertEquals(1, replacement.closes);
        });
    }

    @Test
    void preferenceCancellationFailureStillClosesViewsAndRetainsOriginalFailure() throws Exception {
        var original = java.util.UUID.randomUUID();
        var observed = new java.util.concurrent.CountDownLatch(1);
        var completion = new java.util.concurrent.CompletableFuture<gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection>>();
        var cancellation = new gold.debug.windowstolinux.appapi.task.result.ApiException(
            new gold.debug.windowstolinux.appapi.task.result.Problem(
                gold.debug.windowstolinux.appapi.task.result.ProblemCode.IO_FAILURE, "TASK_CANCEL_PERSISTENCE",
                "Cancellation request could not be confirmed", java.util.Map.of(), java.util.Optional.of(original)));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var handle = new gold.debug.windowstolinux.appapi.task.run.TaskHandle<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection>() {
            @Override
            public java.util.UUID id() {
                return original;
            }

            @Override
            public gold.debug.windowstolinux.appapi.task.model.TaskSnapshot snapshot() {
                throw new AssertionError("Preference observation uses original completion");
            }

            @Override
            public java.util.concurrent.CompletionStage<gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection>> completion() {
                observed.countDown();
                return completion;
            }

            @Override
            public void requestCancel() throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                if (calls.getAndIncrement() == 0) throw cancellation;
                completion.complete(new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(
                    gold.debug.windowstolinux.appapi.task.result.TaskResult.CANCELLED, java.util.Optional.empty(), java.util.Optional.empty()));
            }
        };
        var standard = new Page();
        var agent = new Page();
        var workspace = new DeploymentWorkspace(() -> standard, () -> agent, Page::new, Page::new, () -> handle);
        try {
            edt(() -> workspace.create(INITIAL));
            assertTrue(observed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            edt(() -> {
                workspace.restoreNavigation("static-deployment");
                workspace.restoreNavigation("agent-deployment");
                assertSame(cancellation, assertThrows(Exception.class, workspace::close));
                assertEquals(1, standard.closes);
                assertEquals(1, agent.closes);
                assertFalse(completion.isDone());
                workspace.close();
                assertEquals(1, standard.closes);
                assertEquals(1, agent.closes);
                assertEquals(2, calls.get());
            });
        } finally {
            edt(workspace::close);
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentPageService;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Restores real private preferences into actual lazy business views without navigation writes. / 将真实私有偏好恢复到实际惰性业务页且导航不写回。 */
class DeploymentWorkspaceModeTest extends DeploymentRuntimeFixture {
    private static final class LateCancellation implements TaskHandle<DeploymentPreferences.Selection> {
        private final TaskHandle<DeploymentPreferences.Selection> original;

        private final CountDownLatch refused = new CountDownLatch(1), release = new CountDownLatch(1), applied = new CountDownLatch(1);

        private final AtomicReference<Thread> worker = new AtomicReference<>();

        private final CompletableFuture<OperationResult<DeploymentPreferences.Selection>> completion = new CompletableFuture<>() {
            @Override
            public CompletableFuture<OperationResult<DeploymentPreferences.Selection>> whenComplete(
                java.util.function.BiConsumer<? super OperationResult<DeploymentPreferences.Selection>, ? super Throwable> action) {
                return super.whenComplete((result, failure) -> {
                    action.accept(result, failure);
                    SwingUtilities.invokeLater(applied::countDown);
                });
            }
        };

        private LateCancellation(TaskHandle<DeploymentPreferences.Selection> original) {
            this.original = original;
            original.completion().whenComplete((result, failure) -> {
                if (failure == null) completion.complete(result);
                else completion.completeExceptionally(failure);
            });
        }

        @Override
        public UUID id() {
            return original.id();
        }

        @Override
        public TaskSnapshot snapshot() {
            return original.snapshot();
        }

        @Override
        public CompletionStage<OperationResult<DeploymentPreferences.Selection>> completion() {
            return completion;
        }

        @Override
        public void requestCancel() throws ApiException {
            try {
                original.requestCancel();
            } catch (ApiException actual) {
                assertEquals("TASK_NOT_CANCELLABLE", actual.problem().eventCode());
                worker.set(Thread.currentThread());
                refused.countDown();
                try {
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                throw actual;
            }
        }
    }

    @Test
    void closedLatePreferenceRetainsRealUncancellableTaskUntilOriginalCompletion() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var queryEntered = new java.util.concurrent.CountDownLatch(1);
            var queryRelease = new java.util.concurrent.CountDownLatch(1);
            var callEntered = new java.util.concurrent.CountDownLatch(1);
            var publish = new java.util.concurrent.CountDownLatch(1);
            var original = context.tasks().submit(new TaskRequest("workspace-cancel-boundary", "Read original preferences", List.of(), false), task -> {
                queryEntered.countDown();
                assertTrue(queryRelease.await(15, TimeUnit.SECONDS));
                var selection = new DeploymentPreferences(context.db()).read(task);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(selection), Optional.empty());
            });
            var late = new LateCancellation(original);
            var creates = new AtomicInteger();
            gold.debug.windowstolinux.appapi.ui.page.PageFactory factory = () -> {
                creates.incrementAndGet();
                throw new AssertionError("Closed preference callback must not create a view");
            };
            var workspace = new DeploymentWorkspace(factory, factory, factory, factory, () -> {
                callEntered.countDown();
                assertTrue(publish.await(15, TimeUnit.SECONDS));
                return late;
            });
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        workspace.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                assertTrue(queryEntered.await(15, TimeUnit.SECONDS));
                assertTrue(callEntered.await(15, TimeUnit.SECONDS));
                close(workspace);
                publish.countDown();
                assertTrue(late.refused.await(15, TimeUnit.SECONDS));
                assertFalse(original.completion().toCompletableFuture().isDone());
                assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.RUNNING, original.snapshot().state());
                queryRelease.countDown();
                assertEquals(TaskResult.SUCCEEDED, original.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
                assertTrue(late.applied.await(15, TimeUnit.SECONDS));
                late.release.countDown();
                late.worker.get().join(TimeUnit.SECONDS.toMillis(15));
                assertFalse(late.worker.get().isAlive());
                close(workspace);
                assertEquals(0, creates.get());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                publish.countDown();
                queryRelease.countDown();
                late.release.countDown();
                original.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                if (late.worker.get() != null) {
                    late.worker.get().join(TimeUnit.SECONDS.toMillis(15));
                    assertFalse(late.worker.get().isAlive());
                }
                close(workspace);
            }
        }
    }

    private static void close(DeploymentWorkspace workspace) throws Exception {
        var failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                workspace.close();
            } catch (Exception error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATIC", "ASSISTED", "AGENT"})
    void originalPreferenceTaskRestoresActualModeAndLocaleRetainsItsDraft(String mode) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var expected = new DeploymentPreferences.Selection(mode, "MANUAL_REVIEW", "GENERAL");
            var seed = context.tasks().submit(new TaskRequest("seed-workspace-mode", "Save original mode", List.of(), true), task -> {
                new DeploymentPreferences(context.db()).save(task, expected);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(expected), Optional.empty());
            });
            assertEquals(expected, seed.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).value().orElseThrow());
            var service = new DeploymentStaticPageService(context, new DeploymentStaticWorkflow(context, new DeploymentRefFlow(context)));
            var staticCreates = new AtomicInteger();
            var agentCreates = new AtomicInteger();
            var workspace = new DeploymentWorkspace(() -> {
                staticCreates.incrementAndGet();
                return new DeploymentStaticPage(service);
            }, () -> {
                agentCreates.incrementAndGet();
                return new DeploymentAgentPage(new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)));
            }, () -> {
                throw new AssertionError("Applications are not selected by the saved source mode");
            },
                () -> {
                    throw new AssertionError("Environment is not selected by the saved source mode");
                }, service::preferences);
            var root = new AtomicReference<JComponent>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        root.set(workspace.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                await(() -> named(root.get(), "deployment.preference.approval") instanceof JComboBox<?> box
                    && "MANUAL_REVIEW".equals(box.getSelectedItem()) && box.isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(mode.equals("AGENT") ? "agent-deployment" : "static-deployment", workspace.navigationId());
                    assertEquals(mode.equals("AGENT") ? 0 : 1, staticCreates.get());
                    assertEquals(mode.equals("AGENT") ? 1 : 0, agentCreates.get());
                    if (!mode.equals("AGENT")) {
                        var assisted = (JCheckBox) named(root.get(), "deployment.preference.assisted");
                        assertEquals(mode.equals("ASSISTED"), assisted.isSelected());
                        var draft = (JTextField) named(root.get(), "deployment.sourceName");
                        draft.setText("retained-before-publication");
                        workspace.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "DARK", false));
                        assertSame(draft, named(root.get(), "deployment.sourceName"));
                        assertEquals("retained-before-publication", draft.getText());
                    } else workspace.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "DARK", false));
                    assertEquals(mode.equals("AGENT") ? "agent-deployment" : "static-deployment", workspace.navigationId());
                });
                var read = service.preferences();
                assertEquals(expected, read.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).value().orElseThrow());
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        workspace.close();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < until && context.tasks().list(Optional.empty(), 200).items().stream().anyMatch(task -> task.state()
                != gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED)) Thread.sleep(20);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean ready() throws Exception;
    }

    private static void await(Condition condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) {
            var ready = new AtomicReference<>(false);
            var failure = new AtomicReference<Exception>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ready.set(condition.ready());
                } catch (Exception error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw failure.get();
            if (ready.get()) return;
            Thread.sleep(20);
        }
        fail("Actual original mode and page queries did not complete");
    }

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}

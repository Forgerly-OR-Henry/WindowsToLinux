package gold.debug.windowstolinux.mod.deployment.ui.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;
import org.junit.jupiter.api.Test;

class DeploymentTaskFeedbackTest extends DeploymentRuntimeFixture {
    @Test
    void configuredRefreshIntervalIsUsedAndClosingStopsTheTimer() throws Exception {
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, arguments) -> {
                throw new AssertionError("No host query expected");
            });
        var field = DeploymentTaskFeedback.class.getDeclaredField("timer");
        field.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            var feedback = new DeploymentTaskFeedback(new DeploymentDiagnostics(context), Runnable::run, () -> {
            },
                new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.View(1200, 17));
            try {
                var timer = (Timer) field.get(feedback);
                assertEquals(1200, timer.getDelay());
                assertEquals(1200, timer.getInitialDelay());
                feedback.close();
                assertFalse(timer.isRunning());
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            } finally {
                feedback.close();
            }
        });
    }

    @Test
    void workflowMessagesFollowHostLanguageAndKeepManualChoiceTokens() {
        var locale = new AtomicReference<>(Locale.ENGLISH);
        var ui = (gold.debug.windowstolinux.appapi.ui.UiService) Proxy.newProxyInstance(
            ModContext.class.getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},
            (proxy, method, arguments) -> {
                assertEquals("environment", method.getName());
                return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(locale.get(), "LIGHT", false);
            });
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, arguments) -> {
                assertEquals("ui", method.getName());
                return ui;
            });
        assertEquals("Environment: OBSERVED", DeploymentDiagnostics.text(context, "environment.stage", "OBSERVED"));
        assertTrue(DeploymentDiagnostics.text(context, "environment.confirmTarget").contains("cancel by default"));
        locale.set(Locale.SIMPLIFIED_CHINESE);
        assertEquals("环境：OBSERVED", DeploymentDiagnostics.text(context, "environment.stage", "OBSERVED"));
        String prompt = DeploymentDiagnostics.text(context, "source.comparisonPrompt", "static-components", "ai-components");
        assertTrue(prompt.contains("STATIC") && prompt.contains("AI"));
        assertTrue(prompt.contains("static-components") && prompt.contains("ai-components"));
        assertTrue(DeploymentDiagnostics.text(context, "environment.confirmTarget").contains("默认取消"));
        locale.set(Locale.FRENCH);
        assertEquals("Environment: OBSERVED", DeploymentDiagnostics.text(context, "environment.stage", "OBSERVED"));
    }

    @Test
    void liveOriginalStagesSurviveLockAndCancellationRemainsUnfinishedUntilActualCleanup() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var entered = new CountDownLatch(1);
            var advance = new CountDownLatch(1);
            var second = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cancellation = new CountDownLatch(1);
            var handle = context.tasks().submit(new TaskRequest("fixture.feedback", "Feedback", java.util.List.of(), true), task -> {
                DeploymentDiagnostics.progress(context, task, "Preparing original source");
                entered.countDown();
                if (!advance.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture advance missing");
                DeploymentDiagnostics.progress(context, task, "Building original source");
                second.countDown();
                while (!release.await(20, TimeUnit.MILLISECONDS))
                    if (task.cancelToken().requested()) cancellation.countDown();
                return new OperationResult<Void>(task.cancelToken().requested() ? TaskResult.CANCELLED : TaskResult.SUCCEEDED,
                    Optional.empty(), Optional.empty());
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var executor = Executors.newSingleThreadExecutor();
            var feedback = new AtomicReference<DeploymentTaskFeedback>();
            var navigated = new AtomicInteger();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    feedback.set(new DeploymentTaskFeedback(new DeploymentDiagnostics(context), executor, navigated::incrementAndGet));
                    feedback.get().configure(Locale.ENGLISH, false);
                    feedback.get().watch(handle);
                });
                await(() -> text(feedback.get(), "state").contains("Preparing original source"));
                advance.countDown();
                assertTrue(second.await(5, TimeUnit.SECONDS));
                await(() -> text(feedback.get(), "events").contains("Building original source"));
                runtime.security().lock();
                SwingUtilities.invokeAndWait(() -> feedback.get().configure(Locale.SIMPLIFIED_CHINESE, true));
                assertEquals("", text(feedback.get(), "state"));
                assertEquals("", text(feedback.get(), "events"));
                assertFalse(handle.snapshot().endedAt().isPresent());
                runtime.security().unlock(PASSWORD.toCharArray());
                SwingUtilities.invokeAndWait(() -> feedback.get().configure(Locale.ENGLISH, false));
                await(() -> text(feedback.get(), "events").contains("Building original source"));
                SwingUtilities.invokeAndWait(() -> button(feedback.get(), "tasks").doClick());
                assertEquals(1, navigated.get());
                SwingUtilities.invokeAndWait(() -> button(feedback.get(), "cancel").doClick());
                assertTrue(cancellation.await(5, TimeUnit.SECONDS));
                assertFalse(handle.snapshot().endedAt().isPresent(), "Cancellation request is not terminal cleanup");
                assertTrue(text(feedback.get(), "state").contains("Cancellation requested"));
                release.countDown();
                assertEquals(TaskResult.CANCELLED, handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                await(() -> text(feedback.get(), "state").contains("CANCELLED"));
                assertTrue(context.tasks().list(100).items().stream().noneMatch(task -> task.id().equals(handle.id())));
                assertTrue(text(feedback.get(), "state").contains(handle.id().toString()));
                assertTrue(text(feedback.get(), "events").contains("Building original source"));
            } finally {
                advance.countDown();
                release.countDown();
                SwingUtilities.invokeAndWait(() -> {
                    if (feedback.get() != null) feedback.get().close();
                });
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void closeRejectsAnInflightRefreshAndDoesNotCancelTheAcceptedOriginalTask() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var hold = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            TaskHandle<Void> handle = context.tasks().submit(new TaskRequest("fixture.feedback.close", "Background", java.util.List.of(), true), task -> {
                DeploymentDiagnostics.progress(context, task, "Original accepted background task");
                entered.countDown();
                if (!hold.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture hold missing");
                cancelled.set(task.cancelToken().requested());
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var reading = new CountDownLatch(1);
            var returnRead = new CountDownLatch(1);
            var reads = new AtomicInteger();
            var logger = context.logs();
            LogService observed = new LogService() {
                public LogTarget register(LogCategory category) throws ApiException {
                    return logger.register(category);
                }

                public LogWriteResult write(LogTarget target, LogEvent event) {
                    return logger.write(target, event);
                }

                public LogReadResult read(LogTarget target, Optional<UUID> taskId, int limit) throws ApiException {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    reads.incrementAndGet();
                    reading.countDown();
                    try {
                        assertTrue(returnRead.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    return logger.read(target, taskId, limit);
                }
            };
            var readContext = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("logs")) return observed;
                    throw new AssertionError(method.getName());
                });
            var executor = Executors.newSingleThreadExecutor();
            var feedback = new AtomicReference<DeploymentTaskFeedback>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    feedback.set(new DeploymentTaskFeedback(new DeploymentDiagnostics(readContext), executor, () -> {
                    }));
                    feedback.get().watch(handle);
                });
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> feedback.get().close());
                returnRead.countDown();
                executor.submit(() -> {
                }).get(5, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                });
                assertEquals("", text(feedback.get(), "state"));
                assertEquals("", text(feedback.get(), "events"));
                assertEquals(1, reads.get());
                assertFalse(handle.snapshot().endedAt().isPresent());
                hold.countDown();
                handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertFalse(cancelled.get());
                executor.submit(() -> {
                }).get(5, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                });
                assertEquals(1, reads.get());
            } finally {
                hold.countDown();
                returnRead.countDown();
                SwingUtilities.invokeAndWait(() -> {
                    if (feedback.get() != null) feedback.get().close();
                });
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String text(DeploymentTaskFeedback feedback, String suffix) throws Exception {
        var value = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> value.set(((JTextArea) named(feedback.component(), "deployment.task.feedback." + suffix)).getText()));
        return value.get();
    }

    private static JButton button(DeploymentTaskFeedback feedback, String suffix) {
        return (JButton) named(feedback.component(), "deployment.task.feedback." + suffix);
    }

    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container)
            for (var child : container.getComponents()) {
                var result = named(child, name);
                if (result != null) return result;
            }
        return null;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.call());
    }
}

package gold.debug.windowstolinux.mod.deployment.ui.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real host handles verify delivery admission and view revocation independently of the reader's H2 tests. / 实际宿主句柄独立于读取器H2测试核验交付准入与视图撤销。 */
class DeploymentTaskDeliveryTest extends DeploymentRuntimeFixture {
    private static final UiEnvironment OPEN = new UiEnvironment(Locale.ENGLISH, "LIGHT", false);

    @Test
    void actualHostSuccessIsRequiredBeforeExactOriginalDeliveryAndLockClearsEveryAction() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var businessKnown = new CountDownLatch(1);
            var hostMayFinish = new CountDownLatch(1);
            var original = context.tasks().submit(request("publication"), task -> {
                businessKnown.countDown();
                assertTrue(hostMayFinish.await(10, TimeUnit.SECONDS));
                assertFalse(task.cancelToken().requested());
                return result(TaskResult.SUCCEEDED);
            });
            assertTrue(businessKnown.await(5, TimeUnit.SECONDS));
            var identities = new CopyOnWriteArrayList<UUID>();
            var worker = Executors.newSingleThreadExecutor();
            var feedback = new AtomicReference<DeploymentTaskFeedback>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    feedback.set(new DeploymentTaskFeedback(new DeploymentDiagnostics(context), worker, () -> {
                    }, id -> {
                        assertFalse(SwingUtilities.isEventDispatchThread());
                        identities.add(id);
                        return context.tasks().submit(request("usage"), task -> usageResult());
                    }));
                    feedback.get().configure(OPEN);
                    feedback.get().watch(original);
                });
                worker.submit(() -> {
                }).get(5, TimeUnit.SECONDS);
                assertTrue(identities.isEmpty());
                assertFalse(original.snapshot().endedAt().isPresent());
                assertEquals("", facts(feedback.get()));
                hostMayFinish.countDown();
                assertEquals(TaskResult.SUCCEEDED, original.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                await(() -> facts(feedback.get()).contains("demo-service"));
                assertEquals(List.of(original.id()), identities);
                SwingUtilities.invokeAndWait(() -> assertTrue(named(feedback.get(), "delivery.open").isEnabled()));
                runtime.security().lock();
                SwingUtilities.invokeAndWait(() -> feedback.get().configure(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "DARK", true)));
                assertEquals("", facts(feedback.get()));
                SwingUtilities.invokeAndWait(() -> assertFalse(named(feedback.get(), "delivery.open").isEnabled()));
                runtime.security().unlock(PASSWORD.toCharArray());
                SwingUtilities.invokeAndWait(() -> feedback.get().configure(OPEN));
                await(() -> facts(feedback.get()).contains("demo-service"));
                assertEquals(List.of(original.id(), original.id()), identities);
            } finally {
                hostMayFinish.countDown();
                SwingUtilities.invokeAndWait(() -> {
                    if (feedback.get() != null) feedback.get().close();
                });
                worker.shutdown();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
                original.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void pageCloseRevokesOnlyTheInflightDeliveryReadAndRejectsLateValues() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var original = context.tasks().submit(request("publication"), task -> result(TaskResult.SUCCEEDED));
            original.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1);
            var query = new AtomicReference<TaskHandle<Optional<DeploymentApplicationUsage>>>();
            var worker = Executors.newSingleThreadExecutor();
            var feedback = new AtomicReference<DeploymentTaskFeedback>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    feedback.set(new DeploymentTaskFeedback(new DeploymentDiagnostics(context), worker, () -> {
                    }, id -> {
                        assertEquals(original.id(), id);
                        var admitted = context.tasks().submit(request("usage"), task -> {
                            entered.countDown();
                            while (!release.await(20, TimeUnit.MILLISECONDS))
                                if (task.cancelToken().requested()) cancelled.countDown();
                            return task.cancelToken().requested()
                                ? new OperationResult<Optional<DeploymentApplicationUsage>>(TaskResult.CANCELLED, Optional.empty(), Optional.empty())
                                : usageResult();
                        });
                        query.set(admitted);
                        return admitted;
                    }));
                    feedback.get().configure(OPEN);
                    feedback.get().watch(original);
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                worker.submit(() -> {
                }).get(5, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> feedback.get().close());
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                assertFalse(query.get().snapshot().endedAt().isPresent());
                release.countDown();
                assertEquals(TaskResult.CANCELLED, query.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                worker.submit(() -> {
                }).get(5, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                });
                assertEquals("", facts(feedback.get()));
                assertEquals(TaskResult.SUCCEEDED, original.snapshot().outcome().orElseThrow());
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(() -> {
                    if (feedback.get() != null) feedback.get().close();
                });
                worker.shutdown();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
                if (query.get() != null) query.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TaskResult.class, names = {"FAILED", "CANCELLED", "UNKNOWN"})
    void nonSuccessfulOriginalTaskNeverAdmitsDelivery(TaskResult outcome) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            try (var recovery = context.tasks().registerRecovery(request("publication").type(), (previous, task) -> {
                assertEquals(previous.id(), task.id());
                assertTrue(context.tasks().resourcesReleased(task));
                return result(TaskResult.FAILED);
            })) {
                var original = context.tasks().submit(request("publication"), task -> result(outcome));
                await(() -> original.snapshot().state() == (outcome == TaskResult.UNKNOWN ? TaskState.UNKNOWN : TaskState.ENDED));
                var calls = new AtomicInteger();
                var worker = Executors.newSingleThreadExecutor();
                var feedback = new AtomicReference<DeploymentTaskFeedback>();
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        feedback.set(new DeploymentTaskFeedback(new DeploymentDiagnostics(context), worker, () -> {
                        }, id -> {
                            calls.incrementAndGet();
                            throw new AssertionError("Non-success must not admit any delivery query");
                        }));
                        feedback.get().configure(OPEN);
                        feedback.get().watch(original);
                    });
                    worker.submit(() -> {
                    }).get(5, TimeUnit.SECONDS);
                    SwingUtilities.invokeAndWait(() -> {
                    });
                    assertEquals(0, calls.get());
                    assertEquals("", facts(feedback.get()));
                    if (outcome == TaskResult.UNKNOWN) {
                        assertFalse(original.completion().toCompletableFuture().isDone());
                        assertEquals(TaskState.ENDED, reconcileReady(runtime, original).state());
                    }
                    original.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    SwingUtilities.invokeAndWait(() -> {
                    });
                    assertEquals(0, calls.get());
                } finally {
                    SwingUtilities.invokeAndWait(() -> {
                        if (feedback.get() != null) feedback.get().close();
                    });
                    worker.shutdown();
                    assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void successfulReadWithNoAuthoritativeUsageDoesNotExposeActions() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var original = context.tasks().submit(request("publication"), task -> result(TaskResult.SUCCEEDED));
            original.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var worker = Executors.newSingleThreadExecutor();
            var feedback = new AtomicReference<DeploymentTaskFeedback>();
            var query = new AtomicReference<TaskHandle<Optional<DeploymentApplicationUsage>>>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    feedback.set(new DeploymentTaskFeedback(new DeploymentDiagnostics(context), worker, () -> {
                    }, id -> {
                        var admitted = context.tasks().submit(request("usage"), task ->
                            new OperationResult<Optional<DeploymentApplicationUsage>>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty()));
                        query.set(admitted);
                        return admitted;
                    }));
                    feedback.get().configure(OPEN);
                    feedback.get().watch(original);
                });
                await(() -> query.get() != null);
                assertEquals(TaskResult.SUCCEEDED, query.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                worker.submit(() -> {
                }).get(5, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                });
                assertEquals("", facts(feedback.get()));
                SwingUtilities.invokeAndWait(() -> assertFalse(named(feedback.get(), "delivery.copyCommand").isEnabled()));
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (feedback.get() != null) feedback.get().close();
                });
                worker.shutdown();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private static TaskRequest request(String suffix) {
        return new TaskRequest("fixture.delivery." + suffix, "Original delivery " + suffix, List.of(), true);
    }

    private static OperationResult<Void> result(TaskResult outcome) {
        var problem = outcome == TaskResult.FAILED || outcome == TaskResult.UNKNOWN
            ? Optional.of(new Problem(outcome == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.INCOMPATIBLE,
            "CONTROLLED_DELIVERY_NEGATIVE_OUTCOME", "Original controlled publication did not succeed",
            Map.of(), Optional.empty())) : Optional.<Problem>empty();
        return new OperationResult<>(outcome, Optional.empty(), problem);
    }

    private static OperationResult<Optional<DeploymentApplicationUsage>> usageResult() {
        var endpoint = new DeploymentApplicationUsage.Endpoint("web", "https", "https://demo.debug.gold/app",
            Optional.of(URI.create("https://demo.debug.gold/app")));
        var component = new DeploymentApplicationUsage.Component("demo-service", "demo-service",
            DeploymentApplicationUsage.Mode.DAEMON, ApplicationRunState.RUNNING, ApplicationAutostartState.ENABLED,
            Optional.of("fixed-helper-command"), List.of(endpoint), List.of());
        var value = new DeploymentApplicationUsage(new DeploymentApplicationId("demo", new ServerId("server")),
            "a".repeat(64), "b".repeat(64), ApplicationRunState.RUNNING, ApplicationAutostartState.ENABLED,
            List.of(component), Set.of(DeploymentLifecycleAction.START, DeploymentLifecycleAction.STOP));
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(Optional.of(value)), Optional.empty());
    }

    private static String facts(DeploymentTaskFeedback feedback) throws Exception {
        var result = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> result.set(((JTextArea) named(feedback, "delivery.facts")).getText()));
        return result.get();
    }

    private static Component named(DeploymentTaskFeedback feedback, String name) {
        return named(feedback.component(), name);
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

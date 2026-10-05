package gold.debug.windowstolinux.mod.deployment.service.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;

import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.ui.environment.DeploymentEnvironmentPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentEnvironmentPageTest extends DeploymentRuntimeFixture {
    private BiFunction<TaskSnapshot, CancelToken, Boolean> approval = (task, cancellation) -> true;

    private final AtomicReference<TaskHandle<?>> accepted = new AtomicReference<>();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return (PlatformInteractionService.Prompts) Proxy.newProxyInstance(PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{PlatformInteractionService.Prompts.class}, (proxy, method, args) -> {
                if (method.getName().equals("approve"))
                    return approval.apply((TaskSnapshot) args[1], (CancelToken) args[2]);
                throw new AssertionError(method.getName());
            });
    }

    @Test
    void creationAndRefreshRequireExplicitServerChoiceAndSendNoEnvironmentCommands() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            saved(runtime);
            var peer = new DeploymentEnvironmentWorkflowTest.Peer("ubuntu-24.04");
            var original = runtime.modContexts().get(OWNER).context();
            var page = page(original, peer.wrap(original));
            var component = create(page);
            try {
                await(() -> choices(component) == 1);
                SwingUtilities.invokeAndWait(() -> {
                    assertNull(servers(component).getSelectedItem());
                    assertFalse(button(component, "prepare").isEnabled());
                    servers(component).setSelectedIndex(0);
                    assertTrue(button(component, "prepare").isEnabled());
                    button(component, "refresh").doClick();
                });
                await(() -> enabled(component, "prepare"));
                assertEquals(0, peer.authentications.get());
                assertEquals(0, peer.commands.get());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_type='" + DeploymentEnvironmentWorkflow.TASK_TYPE + "'"));
            } finally {
                close(page);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lockOrCloseRevokesUnansweredOriginalConsentWithoutConnecting(boolean lock) throws Exception {
        enable();
        var entered = new CountDownLatch(1);
        var originalId = new AtomicReference<java.util.UUID>();
        approval = (task, cancellation) -> {
            originalId.set(task.id());
            entered.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!cancellation.requested() && System.nanoTime() < until) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            assertTrue(cancellation.requested());
            return false;
        };
        try (var runtime = open()) {
            start(runtime, true);
            saved(runtime);
            var peer = new DeploymentEnvironmentWorkflowTest.Peer("ubuntu-24.04");
            var original = runtime.modContexts().get(OWNER).context();
            var page = page(original, peer.wrap(original));
            var component = create(page);
            try {
                await(() -> choices(component) == 1);
                SwingUtilities.invokeAndWait(() -> {
                    servers(component).setSelectedIndex(0);
                    button(component, "prepare").doClick();
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (lock) {
                    runtime.security().lock();
                    SwingUtilities.invokeAndWait(() -> page.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true)));
                } else close(page);
                awaitResult(runtime, originalId.get(), TaskResult.CANCELLED);
                assertEquals(0, peer.authentications.get());
                assertEquals(0, peer.commands.get());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + originalId.get() + "' AND released_at IS NULL"));
                assertFalse(enabled(component, "prepare"));
            } finally {
                close(page);
            }
        }
    }

    @Test
    void closingAfterExplicitConsentLeavesOriginalBackgroundPreparationRunningUntilItCloses() throws Exception {
        enable();
        var connecting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var task = new AtomicReference<TaskContext>();
        var sawCancellation = new AtomicBoolean();
        try (var runtime = open()) {
            start(runtime, true);
            saved(runtime);
            var peer = new DeploymentEnvironmentWorkflowTest.Peer("ubuntu-24.04");
            var original = runtime.modContexts().get(OWNER).context();
            var peerContext = peer.wrap(original);
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(), new Class<?>[]{LinuxService.class},
                (proxy, method, args) -> {
                    var authentic = (TaskContext) args[1];
                    task.set(authentic);
                    connecting.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    sawCancellation.set(authentic.cancelToken().requested());
                    return method.invoke(peerContext.linux(), args);
                });
            var controlled = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> method.getName().equals("linux") ? linux : method.invoke(original, args));
            var page = page(original, controlled);
            var component = create(page);
            try {
                await(() -> choices(component) == 1);
                SwingUtilities.invokeAndWait(() -> {
                    servers(component).setSelectedIndex(0);
                    button(component, "prepare").doClick();
                });
                assertTrue(connecting.await(5, TimeUnit.SECONDS));
                close(page);
                assertFalse(task.get().cancelToken().requested());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_id='" + task.get().id() + "' AND state='ENDED'"));
                release.countDown();
                awaitResult(runtime, task.get().id(), TaskResult.SUCCEEDED);
                assertFalse(sawCancellation.get());
                assertFalse(peer.active);
                assertEquals(peer.authentications.get(), peer.closes.get());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + task.get().id() + "' AND released_at IS NULL"));
            } finally {
                release.countDown();
                close(page);
            }
        }
    }

    private DeploymentEnvironmentPage page(ModContext original, ModContext environment) {
        var tasks = environment.tasks();
        var observed = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(), new Class<?>[]{TaskService.class},
            (proxy, method, args) -> {
                Object value = method.invoke(tasks, args);
                if (method.getName().equals("submit") && args[0] instanceof TaskRequest request
                    && request.type().equals(DeploymentEnvironmentWorkflow.TASK_TYPE))
                    accepted.set((TaskHandle<?>) value);
                return value;
            });
        var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> method.getName().equals("tasks") ? observed : method.invoke(environment, args));
        return new DeploymentEnvironmentPage(new DeploymentStaticPageService(original,
            new DeploymentStaticWorkflow(original, new DeploymentRefFlow(original))), new DeploymentEnvironmentWorkflow(bound));
    }

    private static JComponent create(DeploymentEnvironmentPage page) throws Exception {
        var component = new AtomicReference<JComponent>();
        SwingUtilities.invokeAndWait(() -> component.set(page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false))));
        return component.get();
    }

    private static void close(DeploymentEnvironmentPage page) throws Exception {
        SwingUtilities.invokeAndWait(page::close);
    }

    private static void saved(PlatformRuntime runtime) throws Exception {
        runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("environment-server", "127.0.0.1", 22,
            "root", "environment-fixture-password")), "environment-fixture-password".toCharArray());
    }

    private static int choices(JComponent root) throws Exception {
        var result = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> result.set(servers(root).getItemCount()));
        return result.get();
    }

    private static boolean enabled(JComponent root, String suffix) throws Exception {
        var result = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> result.set(button(root, suffix).isEnabled()));
        return result.get();
    }

    private static JComboBox<?> servers(JComponent root) {
        return (JComboBox<?>) named(root, "deployment.environment.server");
    }

    private static JButton button(JComponent root, String suffix) {
        return (JButton) named(root, "deployment.environment." + suffix);
    }

    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container)
            for (var child : container.getComponents()) {
                var found = named(child, name);
                if (found != null) return found;
            }
        return null;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.call() && System.nanoTime() < until) Thread.sleep(15);
        assertTrue(condition.call());
    }

    private void awaitResult(PlatformRuntime runtime, java.util.UUID original, TaskResult expected) throws Exception {
        var handle = accepted.get();
        assertNotNull(handle);
        assertEquals(original, handle.id());
        var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(expected, result.outcome(), result.problem().toString());
        assertTrue(handle.snapshot().endedAt().isPresent(), handle.snapshot().toString());
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_id='" + original + "'"));
    }
}

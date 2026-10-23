package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationsPage;
import gold.debug.windowstolinux.mod.deployment.ui.delivery.DeploymentDeliveryPanel;
import org.junit.jupiter.api.Test;

/** Actual Swing clicks use real owned inventory tasks and the concrete desktop action boundary. / 实际Swing点击使用真实所属清单任务与具体桌面动作边界。 */
class DeploymentApplicationsPageInteractionTest extends DeploymentAutostartFixture {
    @Test
    void modelessLogWindowClosesWithoutCancellingAndCancelTargetsTheAcceptedOriginalHandle() throws Exception {
        enable();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var accepted = new AtomicReference<TaskHandle<?>>();
        var executing = new AtomicReference<TaskContext>();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            seedUsage(runtime, usageGraph("demo", false, true), 1);
            var actual = context(runtime);
            var controlledTasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(),
                new Class<?>[]{TaskService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submit") && args[0] instanceof TaskRequest request
                        && request.type().equals(DeploymentApplicationsService.ACTION_TASK)) {
                        var handle = actual.tasks().submit(request, task -> {
                            executing.set(task);
                            entered.countDown();
                            assertTrue(release.await(20, TimeUnit.SECONDS));
                            assertTrue(task.cancelToken().requested(), "Only the explicit cancel click may request cancellation");
                            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                        });
                        accepted.set(handle);
                        return handle;
                    }
                    try {
                        return method.invoke(actual.tasks(), args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var controlled = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("tasks")) return controlledTasks;
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var mounted = mount(new DeploymentApplicationsService(controlled), new DesktopEffects());
            try {
                var component = mounted.root();
                var inventory = (JList<?>) named(component, "applications.inventory");
                waitEdt(() -> inventory.isEnabled() && inventory.getSelectedValue() != null);
                SwingUtilities.invokeAndWait(() -> ((JButton) named(component, "applications.stop")).doClick());
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> ((JButton) named(component, "applications.logs")).doClick());
                waitEdt(() -> named(component, "applications.cancel").isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(named(component, "applications.refreshLogs").isEnabled());
                    var dialog = logsWindow();
                    dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
                    assertFalse(dialog.isDisplayable());
                });
                assertFalse(executing.get().cancelToken().requested());
                assertFalse(accepted.get().completion().toCompletableFuture().isDone());
                SwingUtilities.invokeAndWait(() -> {
                    ((JButton) named(component, "applications.logs")).doClick();
                    ((JButton) named(component, "applications.cancel")).doClick();
                });
                waitEdt(() -> executing.get().cancelToken().requested());
                release.countDown();
                assertEquals(TaskResult.CANCELLED, accepted.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                waitEdt(() -> inventory.isEnabled() && !named(component, "applications.cancel").isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    var output = (JTextArea) named(component, "applications.tasks");
                    assertTrue(output.getText().contains("CANCELLED"));
                    assertTrue(output.getText().contains(accepted.get().id().toString()));
                    inventory.clearSelection();
                    ((JButton) named(component, "applications.refreshLogs")).doClick();
                    assertTrue(output.getText().contains("CANCELLED"));
                    assertTrue(((JButton) named(component, "applications.logs")).getToolTipText().contains("CANCELLED"));
                    mounted.page().close();
                    assertFalse(logsWindowPresent());
                });
                assertEquals(0, peer.effects.get());
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(mounted.page()::close);
            }
            waitEdt(() -> noResources(runtime));
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void clearedApplicationSelectionSurvivesRefreshAndLeavesIndependentTaskOutputIntact() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            seedUsage(runtime, usageGraph("demo", false, true), 1);
            var effects = new DesktopEffects();
            var mounted = mount(new DeploymentApplicationsService(context(runtime)), effects);
            try {
                var component = mounted.root();
                var inventory = (JList<?>) named(component, "applications.inventory");
                var commands = (JComboBox<?>) named(component, "delivery.commands");
                waitEdt(() -> inventory.isEnabled() && inventory.getSelectedValue() != null && commands.getItemCount() == 3);
                SwingUtilities.invokeAndWait(() -> ((JButton) named(component, "applications.logs")).doClick());
                var taskOutput = (JTextArea) named(component, "applications.tasks");
                SwingUtilities.invokeAndWait(() -> {
                    var dialog = logsWindow();
                    assertEquals(Dialog.ModalityType.MODELESS, dialog.getModalityType());
                    assertFalse(named(component, "applications.cancel").isEnabled());
                    assertTrue(named(component, "applications.refreshLogs").isEnabled());
                    assertEquals(UIManager.getColor("Panel.background"), taskOutput.getBackground());
                    String originalTasks = taskOutput.getText();
                    inventory.clearSelection();
                    assertEquals(originalTasks, taskOutput.getText());
                    assertNotNull(taskOutput.getParent());
                    assertEquals(0, commands.getItemCount());
                    assertFalse(named(component, "applications.inspect").isEnabled());
                    assertFalse(named(component, "applications.edit").isEnabled());
                    ((JButton) named(component, "applications.refresh")).doClick();
                });
                waitEdt(() -> inventory.isEnabled() && ((JButton) named(component, "applications.refresh")).isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertNull(inventory.getSelectedValue());
                    assertEquals(1, inventory.getModel().getSize());
                    assertEquals(0, commands.getItemCount());
                    assertFalse(named(component, "applications.inspect").isEnabled());
                    assertFalse(named(component, "applications.edit").isEnabled());
                    assertSame(taskOutput, named(component, "applications.tasks"));
                    assertTrue(effects.copies.isEmpty());
                    assertTrue(effects.opens.isEmpty());
                    var dialog = logsWindow();
                    dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
                    assertFalse(dialog.isDisplayable());
                    assertNull(inventory.getSelectedValue());
                    ((JButton) named(component, "applications.logs")).doClick();
                    assertSame(taskOutput, named(component, "applications.tasks"));
                    var reopened = logsWindow();
                    mounted.page().environmentChanged(new UiEnvironment(Locale.ENGLISH, "LIGHT", true));
                    assertFalse(reopened.isVisible());
                    assertFalse(named(component, "applications.logs").isEnabled());
                    assertFalse(named(component, "applications.cancel").isEnabled());
                    mounted.page().environmentChanged(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
                    assertFalse(reopened.isVisible());
                    ((JButton) named(component, "applications.logs")).doClick();
                    assertNull(inventory.getSelectedValue());
                    assertSame(taskOutput, named(component, "applications.tasks"));
                    mounted.page().close();
                    assertFalse(reopened.isDisplayable());
                    assertFalse(named(component, "applications.logs").isEnabled());
                });
                assertEquals(0, peer.effects.get());
            } finally {
                SwingUtilities.invokeAndWait(mounted.page()::close);
            }
            waitEdt(() -> noResources(runtime));
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void actualButtonsCopyFixedCommandsAndIpv6EndpointsAndOpenOnlyHttpAcrossFilterAndLockChanges() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            seedUsage(runtime, usageGraph("demo", false, true), 1);
            var effects = new DesktopEffects();
            var mounted = mount(new DeploymentApplicationsService(context(runtime)), effects);
            try {
                var component = mounted.root();
                var inventory = (JList<?>) named(component, "applications.inventory");
                var commands = (JComboBox<?>) named(component, "delivery.commands");
                var endpoints = (JComboBox<?>) named(component, "delivery.endpoints");
                var copyCommand = (JButton) named(component, "delivery.copyCommand");
                var copyEndpoint = (JButton) named(component, "delivery.copyEndpoint");
                var browser = (JButton) named(component, "delivery.open");
                waitEdt(() -> inventory.isEnabled() && inventory.getModel().getSize() == 1 && commands.getItemCount() == 3);
                SwingUtilities.invokeAndWait(() -> {
                    for (int index = 0; index < commands.getItemCount(); index++)
                        if (commands.getItemAt(index).toString().equals("client")) commands.setSelectedIndex(index);
                    copyCommand.doClick();
                    assertEquals("sudo /usr/local/lib/windowstolinux/managed-helper app-client demo-client " + "a".repeat(64) + " --", effects.copies.getFirst());
                    assertEquals(4, endpoints.getItemCount());
                    for (int index = 0; index < endpoints.getItemCount(); index++) {
                        endpoints.setSelectedIndex(index);
                        boolean http = index < 2;
                        assertEquals(http, browser.isEnabled());
                        copyEndpoint.doClick();
                        browser.doClick();
                    }
                    assertEquals(List.of(URI.create("http://[2001:db8::1]:8080"), URI.create("https://demo.debug.gold/app")), effects.opens);
                    assertTrue(effects.copies.contains("tcp://[2001:db8::1]:9000"));
                    assertTrue(effects.copies.contains("udp://[2001:db8::1]:9001"));
                    var facts = ((JTextArea) named(component, "delivery.facts")).getText();
                    for (String hidden : List.of(STARTUP, CLIENT, WORKER, "fixture-secret")) {
                        assertFalse(facts.contains(hidden));
                        assertTrue(effects.copies.stream().noneMatch(value -> value.contains(hidden)));
                    }
                    ((JComboBox<?>) named(component, "applications.category")).setSelectedItem("APP");
                    assertEquals(0, inventory.getModel().getSize());
                    assertEquals(0, commands.getItemCount());
                    assertEquals(0, endpoints.getItemCount());
                    assertFalse(copyCommand.isEnabled());
                    ((JComboBox<?>) named(component, "applications.category")).setSelectedItem("ALL");
                    assertNull(inventory.getSelectedValue());
                    inventory.setSelectedIndex(0);
                    int copies = effects.copies.size(), opens = effects.opens.size();
                    mounted.page().environmentChanged(new UiEnvironment(Locale.ENGLISH, "LIGHT", true));
                    assertEquals(0, commands.getItemCount());
                    copyCommand.doClick();
                    copyEndpoint.doClick();
                    browser.doClick();
                    assertEquals(copies, effects.copies.size());
                    assertEquals(opens, effects.opens.size());
                    mounted.page().environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
                    assertEquals("复制服务器命令", copyCommand.getText());
                    assertEquals(3, commands.getItemCount());
                    mounted.page().close();
                    assertEquals(0, commands.getItemCount());
                    copyCommand.doClick();
                    assertEquals(copies, effects.copies.size());
                });
                assertEquals(0, peer.effects.get());
            } finally {
                SwingUtilities.invokeAndWait(mounted.page()::close);
            }
            waitEdt(() -> noResources(runtime));
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void installedJobShowsActualAvailableStateAndCopiesRunCommandWhileAllDaemonButtonsAreDisabled() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            var graph = usageGraph("demo", true, true);
            seedUsage(runtime, graph, 1);
            var controlled = new ManagedPeer(context(runtime), graph, false, false);
            var service = new DeploymentApplicationsService(bind(context(runtime), controlled));
            var effects = new DesktopEffects();
            var mounted = mount(service, effects);
            try {
                var inventory = (JList<?>) named(mounted.root(), "applications.inventory");
                waitEdt(() -> inventory.isEnabled() && inventory.getSelectedValue() != null);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(((JTextArea) named(mounted.root(), "applications.field.savedState")).getText().contains("Installed and available"));
                    ((JButton) named(mounted.root(), "delivery.copyCommand")).doClick();
                    assertEquals("sudo /usr/local/lib/windowstolinux/managed-helper app-run demo-job " + "a".repeat(64) + " --", effects.copies.getFirst());
                    for (var action : DeploymentLifecycleAction.values()) {
                        var button = (JButton) named(mounted.root(), "applications." + action.name().toLowerCase(Locale.ROOT));
                        assertFalse(button.isEnabled());
                        button.doClick();
                    }
                });
                var selected = await(service.inventory()).value().orElseThrow().getFirst().managed().orElseThrow();
                for (var action : DeploymentLifecycleAction.values())
                    assertEquals(TaskResult.FAILED, await(service.lifecycle(selected, action)).outcome());
                assertEquals(0, controlled.connects.get());
                assertEquals(0, controlled.effects.get());
            } finally {
                SwingUtilities.invokeAndWait(mounted.page()::close);
            }
            waitEdt(() -> noResources(runtime));
            assertNoActiveResources(runtime);
        }
    }

    @Test
    void externalButtonsFollowSavedCapabilitiesAndActualScanIssuesAndFailuresRemainVisible() throws Exception {
        enable();
        try (var ssh = ssh(); var runtime = open()) {
            start(runtime, true);
            server(runtime, ssh.server().getPort(), "tester");
            peer.canStart = true;
            peer.canStop = false;
            peer.scanIssues = List.of("DOCKER_PERMISSION");
            var service = new DeploymentApplicationsService(context(runtime));
            var scanned = await(service.scan(new ServerId("server"))).value().orElseThrow();
            assertEquals(TaskResult.SUCCEEDED, await(service.adopt(new ServerId("server"), scanned.applications().getFirst())).outcome());
            var mounted = mount(service, new DesktopEffects());
            try {
                var inventory = (JList<?>) named(mounted.root(), "applications.inventory");
                var details = (JTextArea) named(mounted.root(), "applications.details");
                waitEdt(() -> inventory.isEnabled() && inventory.getSelectedValue() != null);
                SwingUtilities.invokeAndWait(() -> ((JButton) named(mounted.root(), "applications.discover")).doClick());
                var scan = (JButton) named(mounted.root(), "applications.scan");
                var issues = (JTextArea) named(mounted.root(), "applications.discoveryIssues");
                waitEdt(() -> ((JComboBox<?>) named(mounted.root(), "applications.scanServer")).getItemCount() == 1);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(((JButton) named(mounted.root(), "applications.start")).isEnabled());
                    for (String action : List.of("stop", "restart", "enable_autostart", "disable_autostart")) {
                        var button = (JButton) named(mounted.root(), "applications." + action);
                        assertFalse(button.isEnabled());
                        button.doClick();
                    }
                    assertTrue(((JTextArea) named(mounted.root(), "applications.field.externalCapabilities")).getText().contains("true / false"));
                    scan.doClick();
                });
                waitEdt(() -> scan.isEnabled() && issues.getText().contains("DOCKER_PERMISSION"));
                assertEquals(0, peer.effects.get());
                SwingUtilities.invokeAndWait(() -> ((JButton) named(mounted.root(), "applications.start")).doClick());
                waitEdt(() -> inventory.isEnabled() && peer.effects.get() == 1 && inventory.getModel().getSize() == 1);
                assertEquals("RUNNING", await(service.externalApplications()).value().orElseThrow().getFirst().target().state());
                peer.failScan = true;
                SwingUtilities.invokeAndWait(scan::doClick);
                waitEdt(() -> scan.isEnabled() && details.getText().contains("FAILED") && details.getText().contains("SSH_COMMAND_NONZERO"));
                assertEquals(1, await(service.externalApplications()).value().orElseThrow().size());
                assertEquals(1, peer.effects.get());
            } finally {
                SwingUtilities.invokeAndWait(mounted.page()::close);
            }
            waitEdt(() -> noResources(runtime));
            assertNoActiveResources(runtime);
        }
    }

    private Mounted mount(DeploymentApplicationsService service, DesktopEffects effects) throws Exception {
        var mounted = new AtomicReference<Mounted>();
        SwingUtilities.invokeAndWait(() -> {
            var page = new DeploymentApplicationsPage(service, new DeploymentDeliveryPanel(effects));
            mounted.set(new Mounted(page, page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false))));
        });
        return mounted.get();
    }

    private boolean noResources(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime) {
        try {
            return scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") == 0;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Component named(Container root, String name) {
        if (name.equals(root.getName())) return root;
        var local = find(root, name);
        if (local != null) return local;
        for (var window : Window.getWindows())
            if (window.isDisplayable() && ("applications.discovery.window".equals(window.getName())
                || "applications.logs.window".equals(window.getName()))) {
                var found = find(window, name);
                if (found != null) return found;
            }
        throw new AssertionError("Actual component missing: " + name);
    }

    private static Component find(Container root, String name) {
        for (var component : root.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container child) {
                var found = find(child, name);
                if (found != null) return found;
            }
            if (component instanceof JComponent child && child.getComponentPopupMenu() != null) {
                var found = find(child.getComponentPopupMenu(), name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JDialog logsWindow() {
        return Arrays.stream(Window.getWindows())
            .filter(window -> window.isDisplayable() && "applications.logs.window".equals(window.getName()))
            .map(JDialog.class::cast).findFirst().orElseThrow();
    }

    private static boolean logsWindowPresent() {
        return Arrays.stream(Window.getWindows())
            .anyMatch(window -> window.isDisplayable() && "applications.logs.window".equals(window.getName()));
    }

    private static void waitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var done = new AtomicReference<Boolean>();
        do {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Actual applications page did not reach expected state");
    }

    private record Mounted(DeploymentApplicationsPage page, JComponent root) {
    }

    private static final class DesktopEffects implements DeploymentDeliveryPanel.DeliveryActions {
        final List<String> copies = new ArrayList<>();

        final List<URI> opens = new ArrayList<>();

        public void copy(String value) {
            copies.add(value);
        }

        public void open(URI target) {
            opens.add(target);
        }
    }
}

package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.PASSWORD;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformServersWindowTest {
    @TempDir
    Path root;

    static JDialog dialog(String name) {
        return Arrays.stream(Window.getWindows())
            .filter(window -> window instanceof JDialog && window.isShowing() && name.equals(window.getName()))
            .map(window -> (JDialog) window).findFirst().orElse(null);
    }

    @Test
    void serverWorkCompletionCannotCrossLockGenerationOrClearNewBusyState() throws Exception {
        var oldEntered = new java.util.concurrent.CountDownLatch(1);
        var oldRelease = new java.util.concurrent.CountDownLatch(1);
        var newEntered = new java.util.concurrent.CountDownLatch(1);
        var newRelease = new java.util.concurrent.CountDownLatch(1);
        var displayed = new java.util.concurrent.atomic.AtomicInteger();
        try (var runtime = PlatformRuntime.open(root.resolve("generation"), PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            runtime.started();
            runtime.setInteractionPresenter((gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts.class},
                    (proxy, method, args) -> {
                        throw new AssertionError("No human interaction expected in a view query");
                    }));
            var pane = onEdt(() -> new gold.debug.windowstolinux.app.ui.server.PlatformServersPane(null,
                runtime.servers(), new gold.debug.windowstolinux.app.ui.control.DesktopPartFactory(ThemePalette.light()),
                new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"))));
            var work = pane.getClass().getDeclaredMethod("work", java.util.concurrent.Callable.class, java.util.function.Consumer.class);
            work.setAccessible(true);
            try {
                onEdt(() -> {
                    pane.setLocked(false);
                    return null;
                });
                await(() -> named(pane, "servers.refresh").isEnabled());
                onEdt(() -> {
                    work.invoke(pane, (java.util.concurrent.Callable<Integer>) () -> {
                        oldEntered.countDown();
                        assertTrue(oldRelease.await(8, java.util.concurrent.TimeUnit.SECONDS));
                        return 1;
                    }, (java.util.function.Consumer<Integer>) displayed::set);
                    return null;
                });
                assertTrue(oldEntered.await(3, java.util.concurrent.TimeUnit.SECONDS));
                onEdt(() -> {
                    pane.setLocked(true);
                    pane.setLocked(false);
                    return null;
                });
                await(() -> named(pane, "servers.refresh").isEnabled());
                onEdt(() -> {
                    work.invoke(pane, (java.util.concurrent.Callable<Integer>) () -> {
                        newEntered.countDown();
                        assertTrue(newRelease.await(8, java.util.concurrent.TimeUnit.SECONDS));
                        return 2;
                    }, (java.util.function.Consumer<Integer>) displayed::set);
                    return null;
                });
                assertTrue(newEntered.await(3, java.util.concurrent.TimeUnit.SECONDS));
                oldRelease.countDown();
                Thread.sleep(150);
                onEdt(() -> {
                    assertEquals(0, displayed.get());
                    assertFalse(named(pane, "servers.refresh").isEnabled());
                    return null;
                });
                newRelease.countDown();
                await(() -> displayed.get() == 2);
                await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.hasActiveTasks());
            } finally {
                oldRelease.countDown();
                newRelease.countDown();
                onEdt(() -> {
                    pane.close();
                    return null;
                });
                await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.hasActiveTasks());
            }
        }
    }

    @Test
    void actualServerPageSavesWithoutNetworkThenReviewsAuthenticationAndClearsOnLock() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "This test requires the local desktop");
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            runtime.started();
            var frame = new AtomicReference<PlatformFrame>();
            try {
                onEdt(() -> {
                    DesktopThemeService.install(ThemeMode.LIGHT);
                    var messages = MessageCatalog.forLanguageTag("zh-CN");
                    var window = new PlatformFrame(runtime.security(), messages, ThemePalette.light(),
                        new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                        FailureReportStore.disabled());
                    frame.set(window);
                    runtime.setInteractionPresenter(new TaskInteractionPresenter(window, messages));
                    window.installServers(runtime.servers());
                    window.installRecovery(runtime.recovery());
                    ((JTabbedPane) named(window, "platform.pages"))
                        .setSelectedComponent(named(window, "platform.servers"));
                    window.setVisible(true);
                    return null;
                });
                await(() -> named(frame.get(), "servers.add").isEnabled());
                SwingUtilities.invokeLater(() -> ((JButton) named(frame.get(), "servers.add")).doClick(0));
                await(() -> dialog("server.editor") != null);
                onEdt(() -> {
                    var editor = dialog("server.editor");
                    ((JTextField) named(editor, "server.name")).setText("本机回环认证");
                    ((JTextField) named(editor, "server.host")).setText("127.0.0.1");
                    ((JTextField) named(editor, "server.port"))
                        .setText(Integer.toString(remote.listener.server().getPort()));
                    ((JTextField) named(editor, "server.user")).setText("alice");
                    ((JPasswordField) named(editor, "server.password")).setText("fixture-secret");
                    ((JButton) named(editor, "server.save")).doClick(0);
                    assertEquals(0, ((JPasswordField) named(editor, "server.password")).getPassword().length);
                    return null;
                });
                await(() -> dialog("server.editor") == null
                    && ((JTable) named(frame.get(), "servers.table")).getRowCount() == 1
                    && named(frame.get(), "servers.refresh").isEnabled());
                assertEquals(0, remote.authentications.get());
                assertEquals(0, remote.commands.get());
                onEdt(() -> {
                    var table = (JTable) named(frame.get(), "servers.table");
                    assertEquals("未检查", table.getModel().getValueAt(0, 3));
                    table.setRowSelectionInterval(0, 0);
                    ((JButton) named(frame.get(), "servers.check")).doClick(0);
                    return null;
                });
                await(() -> dialog("task.interaction.dialog") != null);
                onEdt(() -> {
                    var review = dialog("task.interaction.dialog");
                    String text = ((JTextArea) named(review, "task.review.exactAction")).getText();
                    assertTrue(text.contains("@platform @ " + runtime.version()));
                    assertTrue(text.contains("127.0.0.1"));
                    assertFalse(text.contains("fixture-secret"));
                    descendants(review).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> button.getText().equals("允许此次操作")).findFirst().orElseThrow().doClick(0);
                    return null;
                });
                await(() -> ((JTextArea) named(frame.get(), "servers.output")).getText().contains("SSH 认证成功")
                    && ((JComboBox<?>) named(frame.get(), "servers.checks")).getItemCount() == 0
                    && "连接正常".equals(((JTable) named(frame.get(), "servers.table")).getModel().getValueAt(0, 3)));
                assertEquals(1, remote.authentications.get());
                assertEquals(0, remote.commands.get());
                assertTrue(runtime.servers().checks().isEmpty());
                onEdt(() -> {
                    ((JButton) named(frame.get(), "servers.rescue")).doClick(0);
                    return null;
                });
                var rescueService = runtime.recovery();
                await(() -> dialog("recovery.dialog") != null && remote.commands.get() == 1
                    && rescueService.pending().isEmpty());
                await(() -> descendants(dialog("recovery.dialog")).filter(JTextArea.class::isInstance)
                    .map(JTextArea.class::cast).anyMatch(area -> area.getText().contains("已验证 SSH 身份、认证和只读执行")));
                onEdt(() -> {
                    capture(dialog("recovery.dialog"), "platform-rescue.png");
                    dialog("recovery.dialog").dispose();
                    return null;
                });
                onEdt(() -> {
                    capture(frame.get(), "platform-servers.png");
                    return null;
                });
                await(() -> named(frame.get(), "security.lock").isEnabled());
                onEdt(() -> {
                    ((JButton) named(frame.get(), "security.lock")).doClick(0);
                    assertTrue(frame.get().getContentPane().isVisible());
                    assertTrue(frame.get().getGlassPane().isVisible());
                    assertTrue(((JTable) named(frame.get(), "servers.table")).getRowCount() > 0);
                    assertFalse(named(frame.get(), "servers.refresh").isEnabled());
                    return null;
                });
            } finally {
                onEdt(() -> {
                    if (frame.get() != null) {
                        for (var owned : frame.get().getOwnedWindows())
                            owned.dispose();
                        frame.get().dispose();
                    }
                    return null;
                });
            }
        }
    }

    @Test
    void interruptedPreviousVersionCheckIsResolvedThroughThePermanentPage() throws Exception {
        UUID task;
        try (var db = gold.debug.windowstolinux.app.db.connect.PlatformDatabase.open(root,
            PlatformStartupTest.PROTECTION)) {
            var persistence = new gold.debug.windowstolinux.app.db.PlatformStorage(db);
            try (var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
                persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            var ledger = new gold.debug.windowstolinux.app.db.task.TaskLedger(db);
            task = PlatformServerServiceTest.old(ledger, PlatformServerServiceTest.HOST,
                PlatformServerServiceTest.TYPE);
            ledger.retain(task,
                new gold.debug.windowstolinux.app.db.task.TaskResource("CONNECTION", "ssh-" + UUID.randomUUID(),
                    PlatformServerServiceTest.HOST, false),
                new gold.debug.windowstolinux.app.db.task.ResourceRecovery("ssh.v1",
                    Map.of("bootId", db.bootId().toString(), "serverId", "fixture")),
                false);
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
            var frame = onEdt(() -> {
                var window = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                    FailureReportStore.disabled());
                window.installServers(runtime.servers());
                ((JTabbedPane) named(window, "platform.pages")).setSelectedComponent(named(window, "platform.servers"));
                window.setVisible(true);
                return window;
            });
            try {
                await(() -> named(frame, "servers.reconcile").isEnabled());
                SwingUtilities.invokeLater(() -> ((JButton) named(frame, "servers.reconcile")).doClick(0));
                await(() -> Arrays.stream(frame.getOwnedWindows()).anyMatch(Window::isShowing));
                onEdt(() -> {
                    var confirmation = Arrays.stream(frame.getOwnedWindows()).filter(Window::isShowing).findFirst()
                        .orElseThrow();
                    descendants(confirmation).filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast)
                        .findFirst().orElseThrow().setValue(JOptionPane.OK_OPTION);
                    return null;
                });
                await(() -> ((JTextArea) named(frame, "servers.taskOutput")).getText().contains("检查已中断")
                    && ((JComboBox<?>) named(frame, "servers.checks")).getItemCount() == 0);
                onEdt(() -> {
                    assertEquals(-1, ((JTable) named(frame, "servers.table")).getSelectedRow());
                    assertFalse(((JTextArea) named(frame, "servers.output")).getText().contains("检查已中断"));
                    assertTrue(named(frame, "servers.tasks").isVisible());
                    return null;
                });
                assertTrue(runtime.tasks().snapshots().isEmpty());
                assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource"));
                try (var connection = runtime.persistence().database().openConnection();
                     var query = connection.prepareStatement(
                         "SELECT mod_version,outcome FROM runtime_log WHERE category='platform_task' AND task_id=?")) {
                    query.setObject(1, task);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("0.0.1-original", rows.getString(1));
                        assertEquals("FAILED", rows.getString(2));
                        assertFalse(rows.next());
                    }
                }
            } finally {
                onEdt(() -> {
                    for (var owned : frame.getOwnedWindows())
                        owned.dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}

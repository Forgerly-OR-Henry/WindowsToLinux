package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.PASSWORD;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.*;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
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
                    assertEquals("未检查", table.getValueAt(0, 3));
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
                        && "连接正常".equals(((JTable) named(frame.get(), "servers.table")).getValueAt(0, 3)));
                assertEquals(1, remote.authentications.get());
                assertEquals(0, remote.commands.get());
                assertTrue(runtime.servers().checks().isEmpty());
                onEdt(() -> {
                    capture(frame.get(), "platform-servers.png");
                    return null;
                });
                await(() -> named(frame.get(), "security.lock").isEnabled());
                onEdt(() -> {
                    ((JButton) named(frame.get(), "security.lock")).doClick(0);
                    assertFalse(frame.get().getContentPane().isVisible());
                    assertEquals(0, ((JTable) named(frame.get(), "servers.table")).getRowCount());
                    assertEquals("", ((JTextArea) named(frame.get(), "servers.output")).getText());
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
        try (var db = gold.debug.windowstolinux.app.db.PlatformDatabase.open(root, PlatformStartupTest.PROTECTION)) {
            var persistence = new gold.debug.windowstolinux.app.db.PlatformPersistence(db);
            try (var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
                    persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            var ledger = new gold.debug.windowstolinux.app.db.task.TaskLedger(db);
            task = PlatformServerServiceTest.old(ledger, PlatformServerServiceTest.HOST,
                    PlatformServerServiceTest.TYPE);
            ledger.retain(task, new gold.debug.windowstolinux.app.db.task.TaskResource("CONNECTION",
                    UUID.randomUUID().toString(), PlatformServerServiceTest.HOST, false));
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
                await(() -> ((JTextArea) named(frame, "servers.output")).getText().contains("检查已中断")
                        && ((JComboBox<?>) named(frame, "servers.checks")).getItemCount() == 0);
                var snapshot = new gold.debug.windowstolinux.app.db.task.TaskLedger(runtime.persistence().database())
                        .snapshot(task);
                assertEquals(gold.debug.windowstolinux.appapi.task.TaskState.ENDED, snapshot.state());
                assertEquals("0.0.1-original", snapshot.ownerVersion());
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

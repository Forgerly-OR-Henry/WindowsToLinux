package gold.debug.windowstolinux.app.ui.recovery;

import gold.debug.windowstolinux.app.service.contract.*;
import gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.shared.model.recovery.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SshRecoveryDialogTest {
    static class Session implements SshRecoverySession {
        boolean approved, closed;
        @Override public RecoverySnapshot snapshot() {
            return new RecoverySnapshot("fixture", RecoverySnapshot.State.AWAITING_CONFIRMATION, "confirm",
                    Optional.of(new RecoveryAction("uname -a", "先确认当前内核与系统事实。", "仅返回系统信息，不修改目标。", false)),
                    "exact-action", false, 1, Map.of("decision/fixture", "VALID"), Optional.empty());
        }
        @Override public List<TerminalTarget> terminals() { return List.of(); }
        @Override public void bindTerminal(String target, String server, boolean consent, boolean reconciled) { fail("no binding during approval"); }
        @Override public void confirmAction(String token, boolean high) { assertEquals("exact-action", token); assertFalse(high); approved = true; }
        @Override public void pause() { }
        @Override public void resume() { }
        @Override public void close() { closed = true; }
    }
    @Test void exposesExactCommandAndCleansUpWhenWindowCloses() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
        var components = new DesktopComponentFactory(ThemePalette.light()); var session = new Session();
        var ai = (AiApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AiApplicationFacade.class}, (p,m,a) -> { throw new AssertionError("no model configuration call expected"); });
        SwingUtilities.invokeAndWait(() -> {
            com.formdev.flatlaf.FlatLightLaf.setup();
            new SshRecoveryDialog(new JPanel(), new ServerProfile("fixture", "example.test", 22, "user", "ssh/fixture", CredentialStorageMode.MASTER_PASSWORD),
                    session, ai, components, messages);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                var dialog = Arrays.stream(Window.getWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
                        .filter(v -> v.isShowing() && v.getTitle().equals(messages.text("recovery.title"))).findFirst().orElseThrow();
                assertTrue(descendants(dialog).filter(JTextArea.class::isInstance).map(JTextArea.class::cast).anyMatch(v -> v.getText().contains("uname -a")));
                var button = descendants(dialog).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(v -> v.getText().equals(messages.text("recovery.approve"))).findFirst().orElseThrow();
                assertTrue(button.isEnabled()); assertFalse(session.approved); button.doClick(); assertTrue(session.approved);
                try {
                    var image = new java.awt.image.BufferedImage(dialog.getWidth(), dialog.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); dialog.paint(graphics); graphics.dispose();
                    Files.createDirectories(Path.of("target/visual-checks")); javax.imageio.ImageIO.write(image, "png", Path.of("target/visual-checks/ssh-recovery.png").toFile());
                } catch (Exception failure) { throw new AssertionError(failure); }
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> Arrays.stream(Window.getWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
                    .filter(v -> v.getTitle().equals(messages.text("recovery.title"))).forEach(Window::dispose));
        }
        assertTrue(session.closed);
    }
    private Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(this::descendants) : Stream.empty());
    }
}

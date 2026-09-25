package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.main.diagnostic.DesktopFailureReportStore;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.DesktopFrame;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSecurityAcceptanceTest {
    @TempDir
    Path directory;

    @Test
    void explicitSetupLocksAllWindowsAndRetainsOnlyAcceptedTasks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        try (var db = DesktopPersistence.open(directory)) {
            var service = new DesktopApplicationFacade(db, directory.resolve("work"), new SshdLinuxGateway());
            var frame = new AtomicReference<DesktopFrame>();
            var dialog = new AtomicReference<JDialog>();
            var release = new CountDownLatch(1);
            var completion = new CompletableFuture<Boolean>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    frame.set(new DesktopFrame(service));
                    frame.get().setVisible(true);
                    assertTrue(frame.get().getContentPane().isVisible());
                    assertFalse(((JButton) named(frame.get(), "security.lock")).isEnabled());
                    assertEquals(0,
                            java.util.Arrays.stream(frame.get().getOwnedWindows()).filter(Window::isShowing).count());
                    capture(frame.get(), "first-launch-main.png");
                });
                chooseSetupPrompt(frame.get(), 0);
                waitFor(() -> named(frame.get(), "security.settings.password").isShowing());
                assertFalse(service.securityState().configured());
                assertThrows(RuntimeException.class, service::beginTask);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(frame.get().getContentPane().isVisible());
                    assertFalse(named(frame.get(), "security.settings.current").isShowing());
                    assertFalse(named(frame.get(), "security.settings.encryption").isShowing());
                    capture(frame.get(), "first-launch-settings.png");
                    ((JPasswordField) named(frame.get(), "security.settings.password")).setText("isolated-ui-password");
                    ((JPasswordField) named(frame.get(), "security.settings.confirmation"))
                            .setText("isolated-ui-password");
                    ((JButton) named(frame.get(), "security.settings.submit")).doClick(0);
                });
                waitFor(() -> service.securityState().unlocked() && !service.securityState().changing());
                waitFor(() -> named(frame.get(), "security.settings.current").isShowing());
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(named(frame.get(), "security.settings.encryption").isShowing());
                    assertEquals(0,
                            ((JPasswordField) named(frame.get(), "security.settings.password")).getPassword().length);
                    var owned = new JDialog(frame.get(), "private details", false);
                    dialog.set(owned);
                    var password = new JPasswordField("transient-secret");
                    password.setName("test.secret");
                    owned.add(password);
                    owned.setSize(350, 180);
                    owned.setVisible(true);
                    DesktopTaskExecutor.run(() -> {
                        release.await();
                        return service.listServerProfiles().isEmpty();
                    }, completion::complete, completion::completeExceptionally);
                    service.lock();
                });
                waitFor(() -> !frame.get().getContentPane().isVisible() && !dialog.get().getContentPane().isVisible());
                assertFalse(service.securityState().unlocked());
                assertTrue(service.securityState().activeTasks() > 0);
                assertThrows(RuntimeException.class, service::listServerProfiles);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(0,
                            ((JPasswordField) descendants(dialog.get()).filter(c -> "test.secret".equals(c.getName()))
                                    .findFirst().orElseThrow()).getPassword().length);
                    frame.get().dispatchEvent(new WindowEvent(frame.get(), WindowEvent.WINDOW_CLOSING));
                    assertTrue(frame.get().isDisplayable());
                    capture(frame.get(), "active-task-locked.png");
                });
                release.countDown();
                assertTrue(completion.get(10, TimeUnit.SECONDS));
                waitFor(() -> service.securityState().activeTasks() == 0);
                assertFalse(service.securityState().unlocked());
                SwingUtilities.invokeAndWait(() -> {
                    dialog.get().dispose();
                    ((JButton) named(frame.get(), "security.enter")).doClick(0);
                    ((JPasswordField) named(frame.get(), "security.password")).setText("isolated-ui-password");
                    ((JButton) named(frame.get(), "security.submit")).doClick(0);
                });
                waitFor(() -> service.securityState().unlocked() && !service.securityState().changing());
                assertTrue(service.listServerProfiles().isEmpty());
                service.lock();
                SwingUtilities.invokeAndWait(
                        () -> frame.get().dispatchEvent(new WindowEvent(frame.get(), WindowEvent.WINDOW_CLOSING)));
                assertFalse(frame.get().isDisplayable());
            } finally {
                release.countDown();
                if (!completion.isDone() && service.securityState().activeTasks() > 0)
                    completion.get(10, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                    if (dialog.get() != null)
                        dialog.get().dispose();
                    if (frame.get() != null)
                        frame.get().dispose();
                });
                service.closeSshRecovery();
                service.closeSecurity();
            }
        }
        try (var reopened = DesktopPersistence.open(directory)) {
            var service = new DesktopApplicationFacade(reopened, directory.resolve("work"), new SshdLinuxGateway());
            var frame = new AtomicReference<DesktopFrame>();
            try {
                assertTrue(service.securityState().configured());
                assertFalse(service.securityState().unlocked());
                SwingUtilities.invokeAndWait(() -> {
                    frame.set(new DesktopFrame(service));
                    frame.get().setVisible(true);
                    assertFalse(frame.get().getContentPane().isVisible());
                    assertTrue(named(frame.get(), "security.enter").isShowing());
                });
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (frame.get() != null)
                        frame.get().dispose();
                });
                service.closeSshRecovery();
                service.closeSecurity();
            }
        }
    }

    @Test
    void deferringSetupLeavesMainWindowAccessibleWithoutAuthorizingTasks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        try (var db = DesktopPersistence.open(directory)) {
            var service = new DesktopApplicationFacade(db, directory.resolve("work"), new SshdLinuxGateway());
            var frame = new AtomicReference<DesktopFrame>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    frame.set(new DesktopFrame(service));
                    frame.get().setVisible(true);
                });
                chooseSetupPrompt(frame.get(), 1);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(frame.get().getContentPane().isVisible());
                    assertFalse(named(frame.get(), "security.settings.password").isShowing());
                    assertFalse(((JButton) named(frame.get(), "security.lock")).isEnabled());
                    assertFalse(service.securityState().configured());
                    assertThrows(RuntimeException.class, service::beginTask);
                    frame.get().dispatchEvent(new WindowEvent(frame.get(), WindowEvent.WINDOW_CLOSING));
                    assertFalse(frame.get().isDisplayable());
                });
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (frame.get() != null)
                        frame.get().dispose();
                });
                service.closeSshRecovery();
                service.closeSecurity();
            }
        }
    }

    private static void chooseSetupPrompt(DesktopFrame frame, int option) throws Exception {
        waitFor(() -> java.util.Arrays.stream(frame.getOwnedWindows()).anyMatch(Window::isShowing));
        SwingUtilities.invokeAndWait(() -> {
            Window prompt = java.util.Arrays.stream(frame.getOwnedWindows()).filter(Window::isShowing)
                    .filter(window -> descendants(window).anyMatch(JOptionPane.class::isInstance)).findFirst()
                    .orElseThrow();
            assertTrue(frame.getContentPane().isVisible());
            assertTrue(((RootPaneContainer) prompt).getContentPane().isVisible());
            capture(prompt, "first-launch-prompt.png");
            JOptionPane pane = (JOptionPane) descendants(prompt).filter(JOptionPane.class::isInstance).findFirst()
                    .orElseThrow();
            String text = pane.getOptions()[option].toString();
            ((JButton) descendants(prompt).filter(JButton.class::isInstance)
                    .filter(button -> text.equals(((JButton) button).getText())).findFirst().orElseThrow()).doClick(0);
        });
    }

    @Test
    void invalidSetupFeedbackRemainsVisibleAndAllowsKeyboardRetry() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        try (var db = DesktopPersistence.open(directory)) {
            var service = new DesktopApplicationFacade(db, directory.resolve("work"), new SshdLinuxGateway());
            var frame = new AtomicReference<DesktopFrame>();
            var catalog = MessageCatalog.forLanguageTag("zh-CN");
            try {
                SwingUtilities.invokeAndWait(() -> {
                    DesktopThemeService.install(ThemeMode.LIGHT);
                    frame.set(new DesktopFrame(service, catalog,
                            new DesktopDisplayConfiguration("zh-CN", ThemeMode.LIGHT), ThemePalette.light(),
                            (source, appearance) -> {
                            }, null, new DesktopFailureReportStore(directory), false));
                    frame.get().setSize(1060, 720);
                    frame.get().setVisible(true);
                });
                chooseSetupPrompt(frame.get(), 0);
                waitFor(() -> named(frame.get(), "security.settings.submit").isShowing());
                SwingUtilities.invokeAndWait(() -> mouseClick(named(frame.get(), "security.settings.submit")));
                waitFor(() -> !((JLabel) named(frame.get(), "security.settings.status")).getText().isEmpty());
                SwingUtilities.invokeAndWait(() -> {
                    Window prompt = java.util.Arrays.stream(frame.get().getOwnedWindows()).filter(Window::isShowing)
                            .filter(window -> descendants(window).anyMatch(JOptionPane.class::isInstance)).findFirst()
                            .orElseThrow();
                    JTextArea details = (JTextArea) descendants(prompt).filter(JTextArea.class::isInstance).findFirst()
                            .orElseThrow();
                    assertTrue(details.getText().startsWith(catalog.text("security.error.passwordInvalid")));
                    assertEquals(0, details.getVisibleRect().y);
                    assertTrue(
                            details.getVisibleRect().height >= details.getFontMetrics(details.getFont()).getHeight());
                    capture(prompt, "first-launch-password-error.png");
                    mouseClick(descendants(prompt).filter(JButton.class::isInstance).filter(
                            button -> button.getParent() instanceof JPanel && !((JButton) button).getText().isEmpty())
                            .findFirst().orElseThrow());
                });
                SwingUtilities.invokeAndWait(() -> {
                    frame.get().validate();
                    capture(frame.get(), "first-launch-invalid-password.png");
                    JLabel status = (JLabel) named(frame.get(), "security.settings.status");
                    assertEquals(catalog.text("security.saveFailed"), status.getText());
                    assertEquals(0, status.getVisibleRect().y);
                    assertTrue(status.getVisibleRect().height >= status.getFontMetrics(status.getFont()).getHeight(),
                            "Password validation must remain visible: " + status.getVisibleRect() + " / "
                                    + status.getPreferredSize());
                    assertTrue(named(frame.get(), "security.settings.submit").isEnabled());
                    assertFalse(service.securityState().configured());
                });
                typePassword(frame.get(), "security.settings.password", "testpass");
                typePassword(frame.get(), "security.settings.confirmation", "testpass");
                SwingUtilities.invokeAndWait(() -> {
                    Component field = named(frame.get(), "security.settings.confirmation");
                    for (int id : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
                                new KeyEvent(field, id, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'));
                });
                waitFor(() -> service.securityState().unlocked() && !service.securityState().changing());
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (frame.get() != null)
                        frame.get().dispose();
                });
                service.closeSshRecovery();
                service.closeSecurity();
            }
        }
    }

    private static void mouseClick(Component component) {
        assertTrue(component.isShowing());
        assertTrue(component.isEnabled());
        int x = component.getWidth() / 2, y = component.getHeight() / 2;
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED})
            component.dispatchEvent(
                    new MouseEvent(component, id, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static void typePassword(DesktopFrame frame, String name, String value) throws Exception {
        JPasswordField field = (JPasswordField) named(frame, name);
        SwingUtilities.invokeAndWait(() -> {
            mouseClick(field);
            field.requestFocusInWindow();
        });
        waitFor(field::isFocusOwner);
        SwingUtilities.invokeAndWait(() -> {
            for (char character : value.toCharArray())
                KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(field,
                        KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, character));
            assertEquals(value.length(), field.getPassword().length, "First-use typing must reach the password field");
        });
    }

    private static Component named(Component root, String name) {
        return descendants(root).filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component value) {
        return Stream.concat(Stream.of(value),
                value instanceof Container c
                        ? Stream.of(c.getComponents()).flatMap(DesktopSecurityAcceptanceTest::descendants)
                        : Stream.empty());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < end)
            Thread.sleep(30);
        assertTrue(condition.getAsBoolean());
    }

    private static void capture(Window frame, String name) {
        try {
            var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            frame.paint(graphics);
            graphics.dispose();
            Path path = Path.of("target/desktop-security", name);
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}

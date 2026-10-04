package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.*;
import org.junit.jupiter.api.Test;

class DesktopSwitchIconTest {
    @Test
    void actualMouseAndKeyboardPreserveSwitchSelectionDisableAndRtlInBothThemes() throws Exception {
        float previous = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int scale : new int[]{100, 200}) {
                    var frame = edt(() -> {
                        DesktopThemeService.install(theme, scale);
                        assertInstanceOf(DesktopSwitchIcon.class, UIManager.get("CheckBox.icon"));
                        var window = new JFrame();
                        var panel = new JPanel();
                        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                        panel.setBorder(new com.formdev.flatlaf.ui.FlatEmptyBorder(16, 16, 16, 16));
                        panel.add(new JCheckBox("下次启动时恢复上次页面 / Restore last page"));
                        var disabled = new JCheckBox("禁用 / Disabled", true);
                        disabled.setEnabled(false);
                        panel.add(disabled);
                        var rtl = new JCheckBox("RTL switch", true);
                        rtl.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
                        panel.add(rtl);
                        window.setContentPane(panel);
                        window.pack();
                        window.setLocation(100, 100);
                        window.setAlwaysOnTop(true);
                        window.setVisible(true);
                        return window;
                    });
                    var robot = new Robot();
                    robot.setAutoDelay(40);
                    try {
                        robot.waitForIdle();
                        awaitActive(frame);
                        var checkbox = (JCheckBox) frame.getContentPane().getComponent(0);
                        click(robot, checkbox);
                        robot.delay(240);
                        assertTrue(edt(checkbox::isSelected));
                        capture(robot, frame, theme + "-" + scale + "-on");
                        edt(() -> {
                            checkbox.requestFocusInWindow();
                            return null;
                        });
                        robot.waitForIdle();
                        assertTrue(edt(checkbox::isFocusOwner));
                        robot.keyPress(KeyEvent.VK_SPACE);
                        robot.keyRelease(KeyEvent.VK_SPACE);
                        robot.waitForIdle();
                        robot.delay(240);
                        assertFalse(edt(checkbox::isSelected));
                        capture(robot, frame, theme + "-" + scale + "-off");
                        var disabled = (JCheckBox) frame.getContentPane().getComponent(1);
                        click(robot, disabled);
                        assertTrue(edt(disabled::isSelected));
                        assertFalse(edt(disabled::isEnabled));
                    } finally {
                        edt(() -> {
                            frame.dispose();
                            return null;
                        });
                    }
                }
        } finally {
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previous * 100));
                return null;
            });
        }
    }

    private static void awaitActive(JFrame frame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!edt(frame::isActive) && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(edt(frame::isActive), "The original window must be active before native input");
    }

    private static void click(Robot robot, Component target) throws Exception {
        Point point = edt(() -> {
            var location = target.getLocationOnScreen();
            location.translate(target.getWidth() / 2, target.getHeight() / 2);
            return location;
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    private static void capture(Robot robot, JFrame frame, String name) throws Exception {
        Rectangle rectangle = edt(() -> new Rectangle(frame.getLocationOnScreen(), frame.getSize()));
        var image = robot.createScreenCapture(rectangle);
        Path directory = Path.of("target/switch-icons");
        Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}

package gold.debug.windowstolinux.app.ui.window;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.ui.FlatBorder;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import org.junit.jupiter.api.Test;

class PlatformNavigationMotionTest {
    @Test
    void actualSidebarFramesKeepStableIdentityDuringCollapseExpansionAndReversal() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        String previousAnimation = System.getProperty("flatlaf.animation");
        try {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int scale : new int[]{100, 150, 200}) {
                    var frame = new JFrame();
                    var shell = edt(() -> {
                        DesktopThemeService.install(theme, scale);
                        var messages = MessageCatalog.forLanguageTag("zh-CN");
                        var pages = new JTabbedPane();
                        var navigation = new PlatformNavigationShell(pages, messages, theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                        for (String key : new String[]{"platform.servers", "platform.models", "settings.appearance.title"}) {
                            var page = new JPanel();
                            pages.addTab(messages.text(key), page);
                            navigation.register(page, key, "navigation/settings", 10, key.startsWith("settings"));
                        }
                        var status = new DesktopLockStatus(new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(messages), () -> {
                        });
                        status.refresh(true, true);
                        navigation.utilities().add(status);
                        frame.setContentPane(navigation);
                        frame.setSize(1080, 720);
                        frame.setLocation(100, 80);
                        frame.setAlwaysOnTop(true);
                        frame.setVisible(true);
                        frame.validate();
                        return navigation;
                    });
                    var robot = new Robot();
                    robot.waitForIdle();
                    Thread.sleep(250);
                    try {
                        record(frame, shell, robot, true, theme + "-" + scale + "-collapse");
                        record(frame, shell, robot, false, theme + "-" + scale + "-expand");
                        edt(() -> {
                            shell.collapse(true, true);
                            return null;
                        });
                        Thread.sleep(70);
                        edt(() -> {
                            int before = named(shell, "platform.sidebar").getPreferredSize().width;
                            shell.collapse(false, true);
                            assertEquals(before, named(shell, "platform.sidebar").getPreferredSize().width,
                                "Reversing must retain current position");
                            return null;
                        });
                        Thread.sleep(600);
                        assertEquals(com.formdev.flatlaf.util.UIScale.scale(168), edt(() -> named(shell, "platform.sidebar").getWidth()));
                        System.setProperty("flatlaf.animation", "false");
                        edt(() -> {
                            shell.collapse(true, true);
                            frame.validate();
                            return null;
                        });
                        assertEquals(com.formdev.flatlaf.util.UIScale.scale(64), edt(() -> named(shell, "platform.sidebar").getWidth()));
                        if (previousAnimation == null) System.clearProperty("flatlaf.animation");
                        else System.setProperty("flatlaf.animation", previousAnimation);
                    } finally {
                        edt(() -> {
                            shell.close();
                            frame.dispose();
                            return null;
                        });
                    }
                }
        } finally {
            if (previousAnimation == null) System.clearProperty("flatlaf.animation");
            else System.setProperty("flatlaf.animation", previousAnimation);
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    @Test
    void inputsAndActionsUseBorderlessSurfacesWithDifferentHoverAndSelectionColors() throws Exception {
        var width = FlatBorder.class.getDeclaredMethod("getBorderWidth", Component.class);
        width.setAccessible(true);
        edt(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                DesktopThemeService.install(theme, 100);
                var factory = new DesktopPartFactory(theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                var action = factory.primaryButton("");
                var secondary = factory.secondaryButton("");
                for (JComponent component : new JComponent[]{action, secondary, new JTextField(), new JPasswordField(), new JComboBox<>(), new JSpinner()}) {
                    assertInstanceOf(FlatBorder.class, component.getBorder());
                    assertEquals(0f, width.invoke(component.getBorder(), component));
                }
                assertNotEquals(action.getBackground(), secondary.getBackground());
                assertNotEquals(UIManager.getColor("TextField.background"), UIManager.getColor("List.background"));
                var regularCard = (JComponent) factory.inventoryCard("", "", "", false).getComponent(0);
                var selectedCard = (JComponent) factory.inventoryCard("", "", "", true).getComponent(0);
                assertEquals(UIManager.getColor("Panel.background"), regularCard.getBackground());
                assertEquals(UIManager.getColor("List.selectionBackground"), selectedCard.getBackground());
                assertNotEquals(regularCard.getBackground(), selectedCard.getBackground());
                var normal = pixel(action);
                action.getModel().setRollover(true);
                assertNotEquals(normal, pixel(action), "Hover feedback must remain visible without a border");
            }
            return null;
        });
    }

    private static int pixel(JButton button) {
        return pixel(button, 20, 21);
    }

    private static int pixel(JButton button, int x, int y) {
        button.setSize(160, 42);
        var image = new BufferedImage(160, 42, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            button.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(x, y);
    }

    private static void record(JFrame frame, PlatformNavigationShell shell, Robot robot, boolean collapse, String name) throws Exception {
        var icon = edt(() -> {
            shell.collapse(collapse, true);
            return ((JButton) named(shell, "navigation.collapse")).getIcon();
        });
        var screenshots = new ArrayList<BufferedImage>();
        int previousWidth = edt(() -> named(shell, "platform.sidebar").getWidth());
        int previousSettingsY = edt(() -> named(shell, "navigation.settings").getLocationOnScreen().y);
        for (int sample = 0; sample < 16; sample++) {
            Thread.sleep(33);
            var bounds = edt(() -> new Rectangle(frame.getLocationOnScreen(), frame.getSize()));
            screenshots.add(robot.createScreenCapture(bounds));
            int current = edt(() -> {
                assertSame(icon, ((JButton) named(shell, "navigation.collapse")).getIcon(), "Each frame must reuse the icon");
                assertFooterLayout(shell);
                return named(shell, "platform.sidebar").getWidth();
            });
            assertTrue(collapse ? current <= previousWidth : current >= previousWidth, "Motion must be continuous and monotonic");
            previousWidth = current;
            int settingsY = edt(() -> named(shell, "navigation.settings").getLocationOnScreen().y);
            assertTrue(collapse ? settingsY <= previousSettingsY : settingsY >= previousSettingsY,
                "Settings must slide continuously with the footer height");
            previousSettingsY = settingsY;
        }
        assertEquals(com.formdev.flatlaf.util.UIScale.scale(collapse ? 64 : 168), previousWidth);
        assertEquals(collapse ? "" : "WindowsToLinux", edt(() -> ((JButton) named(shell, "navigation.logo")).getText()));
        edt(() -> {
            var state = named(shell, "security.state");
            var action = named(shell, "security.lock");
            if (collapse) {
                assertTrue(action.getY() + action.getHeight() <= state.getY(), "Collapsed action belongs above status");
                assertEquals(action.getX() + action.getWidth() / 2, state.getX() + state.getWidth() / 2);
                var label = (JLabel) state;
                assertEquals(action.getX() + action.getWidth() / 2,
                    state.getX() + label.getInsets().left + label.getIcon().getIconWidth() / 2,
                    "Status icon must stay centered beneath the action at each zoom");
            } else
                assertTrue(state.getX() + state.getWidth() <= action.getX(), "Expanded status belongs left of action");
            return null;
        });
        Path directory = Path.of("target", "sidebar-motion");
        Files.createDirectories(directory);
        for (int index : new int[]{0, 2, 5, 15})
            ImageIO.write(screenshots.get(index), "png", directory.resolve(name + "-" + index + ".png").toFile());
    }

    private static void assertFooterLayout(PlatformNavigationShell shell) {
        var control = (Container) named(shell, "security.statusControl");
        var state = named(shell, "security.state");
        var action = named(shell, "security.lock");
        assertFalse(state.getBounds().intersects(action.getBounds()), "Status and action must never overlap during rotation");
        var bounds = new Rectangle(control.getSize());
        assertTrue(bounds.contains(state.getBounds()), "State must remain inside the sidebar footer");
        assertTrue(bounds.contains(action.getBounds()), "Action must remain inside the sidebar footer");
    }

    @Test
    void logoHasNoHoverPressOrSelectionSurfaceAndSettingsInitiallyOpensTheFirstCategory() throws Exception {
        edt(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                DesktopThemeService.install(theme, 100);
                var messages = MessageCatalog.forLanguageTag("zh-CN");
                var pages = new JTabbedPane();
                var shell = new PlatformNavigationShell(pages, messages, theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                try {
                    var home = new JPanel();
                    pages.addTab("home", home);
                    shell.home(home, () -> pages.setSelectedComponent(home));
                    var password = new JPanel();
                    pages.addTab("password", password);
                    shell.register(password, "security.settings", "security/shield", 20, true);
                    var appearance = new JPanel();
                    pages.addTab("appearance", appearance);
                    shell.register(appearance, "settings.appearance.title", "navigation/settings", 10, true);
                    ((JButton) named(shell, "navigation.settings")).doClick();
                    assertSame(appearance, pages.getSelectedComponent());
                    var logo = (JButton) named(shell, "navigation.logo");
                    logo.doClick();
                    assertSame(home, pages.getSelectedComponent());
                    assertEquals(0, pixel(logo, 150, 20));
                    logo.getModel().setRollover(true);
                    assertEquals(0, pixel(logo, 150, 20));
                    logo.getModel().setArmed(true);
                    logo.getModel().setPressed(true);
                    logo.getModel().setSelected(true);
                    assertEquals(0, pixel(logo, 150, 20));
                    assertFalse(logo.isBorderPainted());
                    assertFalse(logo.isFocusPainted());
                } finally {
                    shell.close();
                }
            }
            return null;
        });
    }

    @Test
    void footerMovesContinuouslyWithoutCaptionOrLayoutPhaseSwitches() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            edt(() -> {
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int scale : new int[]{100, 150, 200})
                        for (String locale : new String[]{"zh-CN", "en"}) {
                            DesktopThemeService.install(theme, scale);
                            var control = new DesktopLockStatus(new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(
                                MessageCatalog.forLanguageTag(locale)), () -> {
                            });
                            control.refresh(true, true);
                            var state = (JLabel) named(control, "security.state");
                            var action = (JButton) named(control, "security.lock");
                            String originalState = state.getText(), originalAction = action.getText();
                            Rectangle previousState = null, previousAction = null;
                            for (int sample = 0; sample <= 240; sample++) {
                                double progress = sample / 240d;
                                control.presentation(progress);
                                control.setSize((int) Math.round(com.formdev.flatlaf.util.UIScale.scale(152)
                                    - com.formdev.flatlaf.util.UIScale.scale(104) * progress), control.getPreferredSize().height);
                                control.doLayout();
                                assertEquals(originalState, state.getText(), "Keep the original caption while paint fades it");
                                assertEquals(originalAction, action.getText());
                                assertFalse(state.getBounds().intersects(action.getBounds()));
                                var bounds = new Rectangle(control.getSize());
                                assertTrue(bounds.contains(state.getBounds()));
                                assertTrue(bounds.contains(action.getBounds()));
                                if (previousState != null) {
                                    int tolerance = com.formdev.flatlaf.util.UIScale.scale(3);
                                    assertTrue(Math.abs(state.getX() - previousState.x) <= tolerance);
                                    assertTrue(Math.abs(state.getY() - previousState.y) <= tolerance);
                                    assertTrue(Math.abs(action.getX() - previousAction.x) <= tolerance);
                                    assertTrue(Math.abs(action.getY() - previousAction.y) <= tolerance);
                                }
                                previousState = state.getBounds();
                                previousAction = action.getBounds();
                                var painted = new BufferedImage(control.getWidth(), control.getHeight(), BufferedImage.TYPE_INT_ARGB);
                                var graphics = painted.createGraphics();
                                try {
                                    control.paint(graphics);
                                } finally {
                                    graphics.dispose();
                                }
                            }
                        }
                return null;
            });
        } finally {
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (var child : container.getComponents()) {
            var found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static <T> T edt(java.util.concurrent.Callable<T> task) throws Exception {
        var future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}

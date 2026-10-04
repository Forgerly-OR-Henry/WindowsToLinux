package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey;
import gold.debug.windowstolinux.appapi.ui.page.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformLockedNavigationWindowTest {
    @TempDir
    Path root;

    @Test
    void realInputKeepsNavigationAndBlocksContentThenRetriesUnlock() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            try (var runtime = PlatformRuntime.open(root.resolve(theme.name()), PlatformStartupTest.PROTECTION)) {
                var frame = onEdt(() -> {
                    DesktopThemeService.install(theme, 100);
                    var result = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                        theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
                        new UiEnvironment(Locale.SIMPLIFIED_CHINESE, theme.name(), false), FailureReportStore.disabled());
                    result.setVisible(true);
                    return result;
                });
                try {
                    runtime.security().setupAndUnlock("ui-test-password".toCharArray(), "ui-test-password".toCharArray());
                    await(() -> named(frame, "security.lock").isEnabled());
                    var clicks = new AtomicInteger();
                    var field = new JTextField("retained draft", 24);
                    var business = new JButton("Business action");
                    business.addActionListener(event -> clicks.incrementAndGet());
                    var area = new JTextArea("long content\n".repeat(100));
                    var scroll = new JScrollPane(area);
                    var tabs = new JTabbedPane();
                    var controls = new JPanel(new BorderLayout());
                    var row = new JPanel();
                    row.add(field);
                    row.add(business);
                    controls.add(row, BorderLayout.NORTH);
                    controls.add(scroll);
                    tabs.addTab("First", controls);
                    tabs.addTab("Second", new JLabel("Second page"));
                    var scope = frame.pages().begin("ui.fixture", false);
                    scope.register(new PageSpec("main", "fixture.main", "Fixture", false), () -> new ModPage() {
                        public JComponent create(UiEnvironment environment) {
                            return tabs;
                        }

                        public void environmentChanged(UiEnvironment environment) {
                        }

                        public void close() {
                        }
                    });
                    var otherScope = frame.pages().begin("ui.other", false);
                    otherScope.register(new PageSpec("other", "fixture.other", "Other", false), () -> new ModPage() {
                        public JComponent create(UiEnvironment environment) {
                            return new JLabel("Other view");
                        }

                        public void environmentChanged(UiEnvironment environment) {
                        }

                        public void close() {
                        }
                    });
                    scope.commit();
                    otherScope.commit();
                    ApplicationLauncher.installModManagement(runtime, frame);
                    await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.hasActiveTasks());
                    otherScope.open("other");
                    scope.open("main");
                    var robot = new Robot();
                    robot.setAutoDelay(50);
                    onEdt(() -> {
                        frame.toFront();
                        frame.requestFocus();
                        return null;
                    });
                    click(robot, business);
                    assertEquals(1, clicks.get(), "Real input must reach the unlocked fixture");
                    clicks.set(0);
                    click(robot, field);
                    runtime.security().lock();
                    await(() -> frame.getGlassPane().isVisible());
                    assertTrue(onEdt(() -> frame.getContentPane().isVisible() && tabs.isShowing()));
                    assertEquals(java.awt.dnd.DnDConstants.ACTION_NONE,
                        onEdt(() -> frame.getGlassPane().getDropTarget().getDefaultActions()));
                    assertEquals("已锁定", onEdt(() -> ((JLabel) named(frame, "security.state")).getText()));
                    assertEquals("解锁", onEdt(() -> ((JButton) named(frame, "security.lock")).getText()));
                    var otherRoot = onEdt(() -> (JButton) descendants(named(frame, "mods.navigation"))
                        .filter(c -> c instanceof JButton button && button.getClientProperty("mod.pageKey")
                            instanceof gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key
                            && key.ownerId().equals("ui.other")).findFirst().orElseThrow());
                    click(robot, otherRoot);
                    assertEquals("ui.other", onEdt(frame::currentPage).ownerId());
                    click(robot, named(frame, "mods.workspace.close"));
                    assertEquals("ui.other", onEdt(frame::currentPage).ownerId(), "Closing a root is blocked while locked");
                    var mainRoot = onEdt(() -> (JButton) descendants(named(frame, "mods.navigation"))
                        .filter(c -> c instanceof JButton button && button.getClientProperty("mod.pageKey")
                            instanceof gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key
                            && key.ownerId().equals("ui.fixture")).findFirst().orElseThrow());
                    click(robot, mainRoot);
                    await(tabs::isShowing);
                    click(robot, business);
                    click(robot, field);
                    robot.keyPress(KeyEvent.VK_X);
                    robot.keyRelease(KeyEvent.VK_X);
                    assertEquals(0, clicks.get());
                    assertEquals("retained draft", onEdt(field::getText));
                    int position = onEdt(() -> scroll.getVerticalScrollBar().getValue());
                    click(robot, area);
                    robot.mouseWheel(5);
                    assertEquals(position, onEdt(() -> scroll.getVerticalScrollBar().getValue()));
                    Point second = onEdt(() -> {
                        Point point = tabs.getLocationOnScreen();
                        Rectangle bounds = tabs.getBoundsAt(1);
                        Point local = SwingUtilities.convertPoint(tabs, bounds.x + bounds.width / 2,
                            bounds.y + bounds.height / 2, frame.getGlassPane());
                        assertFalse(frame.getGlassPane().contains(local.x, local.y), "Tab navigation must pass the input gate");
                        return new Point(point.x + bounds.x + bounds.width / 2, point.y + bounds.y + bounds.height / 2);
                    });
                    robot.mouseMove(second.x, second.y);
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    await(() -> tabs.getSelectedIndex() == 1);
                    click(robot, named(frame, "navigation.settings"));
                    await(() -> named(frame, "security.settings.password").isShowing());
                    click(robot, workspaceButton(frame, "ui.fixture"));
                    await(tabs::isShowing);
                    onEdt(() -> {
                        capture(frame, "idea-" + theme + "-locked.png");
                        frame.setSize(800, 560);
                        return null;
                    });
                    onEdt(() -> {
                        capture(frame, "idea-" + theme + "-minimum.png");
                        return null;
                    });
                    click(robot, named(frame, "navigation.settings"));
                    var category = onEdt(() -> (JComboBox<?>) named(frame, "settings.category"));
                    await(category::isShowing);
                    onEdt(() -> {
                        category.setLightWeightPopupEnabled(false);
                        return null;
                    });
                    click(robot, category);
                    await(category::isPopupVisible);
                    var popup = onEdt(() -> (javax.swing.plaf.basic.ComboPopup)
                        category.getAccessibleContext().getAccessibleChild(0));
                    var list = popup.getList();
                    int categoryIndex = onEdt(() -> (category.getSelectedIndex() + 1) % category.getItemCount());
                    Point choice = onEdt(() -> {
                        Point location = list.getLocationOnScreen();
                        Rectangle bounds = list.getCellBounds(categoryIndex, categoryIndex);
                        return new Point(location.x + bounds.x + bounds.width / 2,
                            location.y + bounds.y + bounds.height / 2);
                    });
                    robot.mouseMove(choice.x, choice.y);
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    await(() -> !category.isPopupVisible() && category.getSelectedIndex() == categoryIndex);
                    click(robot, workspaceButton(frame, "ui.fixture"));
                    await(tabs::isShowing);
                    click(robot, named(frame, "navigation.collapse"));
                    await(() -> named(frame, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale.scale(64));
                    onEdt(() -> {
                        capture(frame, "idea-" + theme + "-collapsed.png");
                        return null;
                    });
                    click(robot, named(frame, "security.lock"));
                    await(() -> unlockWindow(frame) != null);
                    var prompt = unlockWindow(frame);
                    click(robot, named(prompt, "security.password"));
                    robot.keyPress(KeyEvent.VK_A);
                    robot.keyRelease(KeyEvent.VK_A);
                    robot.keyPress(KeyEvent.VK_ENTER);
                    robot.keyRelease(KeyEvent.VK_ENTER);
                    await(() -> named(prompt, "security.password").isEnabled());
                    assertFalse(runtime.security().securityState().unlocked());
                    onEdt(() -> {
                        capture(prompt, "idea-" + theme + "-unlock.png");
                        ((JPasswordField) named(prompt, "security.password")).setText("ui-test-password");
                        return null;
                    });
                    click(robot, named(prompt, "security.submit"));
                    await(() -> runtime.security().securityState().unlocked() && !frame.getGlassPane().isVisible());
                    onEdt(() -> {
                        tabs.setSelectedIndex(0);
                        return null;
                    });
                    click(robot, business);
                    assertEquals(1, clicks.get());
                    assertEquals("retained draft", onEdt(field::getText));
                    assertNotNull(onEdt(() -> ((JButton) named(frame, "navigation.logo")).getIcon()));
                    runtime.security().lock();
                    await(() -> frame.getGlassPane().isVisible());
                    click(robot, named(frame, "security.lock"));
                    await(() -> unlockWindow(frame) != null);
                    var cancelled = unlockWindow(frame);
                    click(robot, named(cancelled, "security.cancel"));
                    await(() -> !cancelled.isDisplayable());
                    assertFalse(runtime.security().securityState().unlocked());
                    scope.close();
                    otherScope.close();
                } finally {
                    onEdt(() -> {
                        frame.dispose();
                        return null;
                    });
                }
            }
        }
    }

    private static Component workspaceButton(PlatformFrame frame, String owner) throws Exception {
        return onEdt(() -> descendants(named(frame, "mods.navigation")).filter(c -> c instanceof JButton button
                && button.getClientProperty("mod.pageKey") instanceof PageKey key && key.ownerId().equals(owner))
            .findFirst().orElseThrow());
    }

    private static Window unlockWindow(Window frame) {
        for (Window window : frame.getOwnedWindows())
            if (window.isShowing() && "security.unlockDialog".equals(window.getName())) return window;
        return null;
    }

    private static void click(Robot robot, Component component) throws Exception {
        Point point = onEdt(() -> {
            Point location = component.getLocationOnScreen();
            return new Point(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }
}

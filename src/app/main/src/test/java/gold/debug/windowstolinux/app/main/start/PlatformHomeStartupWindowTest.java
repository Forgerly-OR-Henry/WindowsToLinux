package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.setting.StartupPageService;
import gold.debug.windowstolinux.app.service.setting.StartupPageLocation;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformHomeStartupWindowTest {
    @TempDir
    Path root;

    @Test
    void realCloseRemembersThePageAndRestartOnlyRestoresAnEnabledChoice() throws Exception {
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            Path data = root.resolve(theme.name());
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                var frame = open(runtime, new DesktopDisplayConfig("zh-CN", theme));
                try {
                    assertEquals("shell.home", onEdt(frame::currentPage).platformId());
                    var navigationRobot = new Robot();
                    click(navigationRobot, named(frame, "navigation.settings"));
                    assertEquals("settings.appearance.title", onEdt(frame::currentPage).platformId());
                    click(navigationRobot, named(frame, "navigation.logo"));
                    assertEquals("shell.home", onEdt(frame::currentPage).platformId());
                    runtime.security().setupAndUnlock("home-test".toCharArray(), "home-test".toCharArray());
                    await(() -> named(frame, "appearance.save").isEnabled());
                    for (int scale : new int[]{100, 150, 200}) {
                        onEdt(() -> {
                            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.appearance"));
                            ((JComboBox<?>) named(frame, "appearance.scale")).setSelectedItem(scale);
                            ((JButton) named(frame, "appearance.save")).doClick(0);
                            return null;
                        });
                        await(() -> !DesktopTaskRunner.hasActiveTasks() && named(frame, "appearance.save").isEnabled());
                        onEdt(() -> {
                            frame.showHome();
                            frame.validate();
                            capture(frame, "home-" + theme + "-" + scale + ".png");
                            return null;
                        });
                    }
                    onEdt(() -> {
                        frame.setSize(800, 560);
                        frame.validate();
                        capture(frame, "home-" + theme + "-minimum.png");
                        return null;
                    });
                    saveRestore(frame, true);
                    assertEquals("true", runtime.persistence().preferences().find("ui_restore_last_page").orElseThrow());
                    onEdt(() -> {
                        ((JButton) named(frame, "navigation.platform.servers")).doClick(0);
                        return null;
                    });
                    assertEquals("platform.servers", onEdt(frame::currentPage).platformId());
                    closeNormally(frame, runtime);
                } finally {
                    onEdt(() -> {
                        frame.dispose();
                        return null;
                    });
                }
            }
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                assertFalse(runtime.security().securityState().unlocked());
                assertEquals(StartupPageLocation.platform("platform.servers"), new StartupPageService(runtime.persistence()).read().orElseThrow());
                var frame = open(runtime, stored(runtime));
                try {
                    assertEquals("platform.servers", onEdt(frame::currentPage).platformId());
                    assertFalse(onEdt(() -> named(frame, "servers.add").isEnabled()));
                    var robot = new Robot();
                    click(robot, named(frame, "navigation.logo"));
                    await(() -> frame.currentPage().platformId().equals("shell.home"));
                    onEdt(() -> {
                        ((JButton) named(frame, "navigation.collapse")).doClick(0);
                        return null;
                    });
                    await(() -> named(frame, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale.scale(64));
                    onEdt(() -> {
                        ((JButton) named(frame, "navigation.platform.models")).doClick(0);
                        return null;
                    });
                    onEdt(() -> {
                        ((JButton) named(frame, "navigation.logo")).requestFocusInWindow();
                        return null;
                    });
                    robot.waitForIdle();
                    robot.keyPress(KeyEvent.VK_SPACE);
                    robot.keyRelease(KeyEvent.VK_SPACE);
                    robot.waitForIdle();
                    await(() -> frame.currentPage().platformId().equals("shell.home"));
                    assertEquals("", onEdt(() -> ((JButton) named(frame, "navigation.logo")).getText()));
                    runtime.security().unlock("home-test".toCharArray());
                    await(() -> named(frame, "appearance.save").isEnabled());
                    saveRestore(frame, false);
                    onEdt(() -> {
                        ((JButton) named(frame, "navigation.tasks.title")).doClick(0);
                        return null;
                    });
                    closeNormally(frame, runtime);
                } finally {
                    onEdt(() -> {
                        frame.dispose();
                        return null;
                    });
                }
            }
            try (var runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION)) {
                assertEquals(StartupPageLocation.platform("tasks.title"), new StartupPageService(runtime.persistence()).read().orElseThrow());
                var frame = open(runtime, stored(runtime));
                try {
                    assertEquals("shell.home", onEdt(frame::currentPage).platformId());
                    closeNormally(frame, runtime);
                } finally {
                    onEdt(() -> {
                        frame.dispose();
                        return null;
                    });
                }
            }
        }
    }

    @Test
    void publishedModRestoresExactlyAndUnavailableOrSupersededTargetsStayOnHomeOrTheNewSelection() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var frame = open(runtime, new DesktopDisplayConfig("en", ThemeMode.LIGHT));
            try {
                var seen = new AtomicReference<UiEnvironment>();
                var internal = new AtomicReference<>("");
                var scope = frame.pages().begin("home.fixture", false);
                scope.register(new PageSpec("main", "fixture.home", "Home fixture", false), () -> new ModPage() {
                    public JComponent create(UiEnvironment environment) {
                        seen.set(environment);
                        return new JPanel();
                    }

                    public void environmentChanged(UiEnvironment environment) {
                        seen.set(environment);
                    }

                    public String navigationId() {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        return internal.get();
                    }

                    public void restoreNavigation(String id) {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        internal.set(id);
                    }

                    public void close() {
                    }
                });
                StartupPageLocation mod = new StartupPageLocation("shell.workspace", "home.fixture", "main", "");
                onEdt(() -> {
                    frame.resumePage(mod);
                    return null;
                });
                assertEquals("shell.home", onEdt(frame::currentPage).platformId());
                scope.commit();
                onEdt(() -> {
                    frame.finishStartupNavigation();
                    return null;
                });
                assertEquals(mod, onEdt(frame::currentPage));
                assertTrue(seen.get().locked());
                new StartupPageService(runtime.persistence()).remember(mod);
                assertEquals(mod, new StartupPageService(runtime.persistence()).read().orElseThrow());
                var restoredInternal = new StartupPageLocation("shell.workspace", "home.fixture", "main", "agent-deployment");
                onEdt(() -> {
                    frame.pages().closeView(new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey("home.fixture", "main"));
                    frame.resumePage(new StartupPageLocation("shell.workspace", "home.fixture", "agent-deployment", ""));
                    frame.finishStartupNavigation();
                    assertEquals(restoredInternal, frame.currentPage());
                    frame.showHome();
                    frame.pages().navigateView(new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey("home.fixture", "main"));
                    assertEquals(restoredInternal, frame.currentPage());
                    return null;
                });
                new StartupPageService(runtime.persistence()).remember(onEdt(frame::currentPage));
                assertEquals(restoredInternal, new StartupPageService(runtime.persistence()).read().orElseThrow());
                onEdt(() -> {
                    frame.resumePage(new StartupPageLocation("shell.workspace", "home.fixture", "main", "applications"));
                    frame.finishStartupNavigation();
                    assertEquals("applications", frame.currentPage().detailId());
                    frame.resumePage(StartupPageLocation.platform("shell.workspace"));
                    frame.finishStartupNavigation();
                    assertEquals("shell.home", frame.currentPage().platformId());
                    return null;
                });
                onEdt(() -> {
                    frame.resumePage(new StartupPageLocation("shell.workspace", "removed.fixture", "missing", ""));
                    frame.finishStartupNavigation();
                    assertEquals("shell.home", frame.currentPage().platformId());
                    frame.resumePage(mod);
                    ((JButton) named(frame, "navigation.platform.servers")).doClick(0);
                    frame.finishStartupNavigation();
                    assertEquals("platform.servers", frame.currentPage().platformId());
                    frame.resumePage(StartupPageLocation.platform("removed.page"));
                    frame.finishStartupNavigation();
                    assertEquals("shell.home", frame.currentPage().platformId());
                    return null;
                });
                scope.close();
                var management = new StartupPageLocation("shell.mods", "", "", "mods.originals_class_mode");
                onEdt(() -> {
                    frame.resumePage(management);
                    return null;
                });
                ApplicationLauncher.installModManagement(runtime, frame);
                onEdt(() -> {
                    frame.finishStartupNavigation();
                    assertEquals(management, frame.currentPage());
                    return null;
                });
                new StartupPageService(runtime.persistence()).remember(management);
                assertEquals(management, new StartupPageService(runtime.persistence()).read().orElseThrow());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void corruptPagePreferenceIsReportedAndPreserved() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = new StartupPageService(runtime.persistence());
            service.remember(StartupPageLocation.platform("platform.servers"));
            assertEquals(StartupPageLocation.platform("platform.servers"), service.read().orElseThrow());
            for (String invalid : new String[]{"not json", "{\"formatVersion\":2}",
                "{\"formatVersion\":1,\"platformId\":\"shell.home\",\"ownerId\":\"\",\"pageId\":\"\",\"detailId\":\"\",\"platformId\":\"platform.servers\"}"}) {
                runtime.persistence().preferences().save("ui_last_page", invalid);
                assertThrows(SQLException.class, service::read);
                assertEquals(invalid, runtime.persistence().preferences().find("ui_last_page").orElseThrow());
            }
        }
    }

    private static PlatformFrame open(PlatformRuntime runtime, DesktopDisplayConfig config) throws Exception {
        var models = runtime.models();
        var frame = onEdt(() -> ApplicationLauncher.createWindow(runtime, MessageCatalog.forLanguageTag(config.localeTag()),
            config, config.themeMode(), FailureReportStore.disabled(), models));
        ApplicationLauncher.restoreStartupPage(runtime, config, frame);
        runtime.started();
        onEdt(() -> {
            frame.setVisible(true);
            frame.finishStartupNavigation();
            return null;
        });
        return frame;
    }

    private static DesktopDisplayConfig stored(PlatformRuntime runtime) throws Exception {
        var preferences = runtime.persistence().preferences();
        return DesktopDisplayConfig.fromStoredValues(preferences.find("ui_locale").orElse(null), preferences.find("ui_theme").orElse(null),
            preferences.find("ui_scale").orElse(null), preferences.find("ui_restore_last_page").orElse(null), Locale.ENGLISH);
    }

    private static void saveRestore(PlatformFrame frame, boolean selected) throws Exception {
        onEdt(() -> {
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.appearance"));
            frame.validate();
            var scroll = (JScrollPane) named(frame, "appearance.scroll");
            var body = (JComponent) scroll.getViewport().getView();
            var save = (JButton) named(frame, "appearance.save");
            Rectangle bounds = SwingUtilities.convertRectangle(save.getParent(), save.getBounds(), body);
            body.scrollRectToVisible(bounds);
            assertTrue(scroll.getViewport().getViewRect().intersects(bounds), "Save must remain reachable in the complete preference viewport");
            ((JCheckBox) named(frame, "appearance.restoreLastPage")).setSelected(selected);
            ((JButton) named(frame, "appearance.save")).doClick(0);
            return null;
        });
        await(() -> !DesktopTaskRunner.hasActiveTasks() && named(frame, "appearance.save").isEnabled());
        onEdt(() -> {
            capture(frame, "startup-switch-" + selected + ".png");
            return null;
        });
    }

    private static void closeNormally(PlatformFrame frame, PlatformRuntime runtime) throws Exception {
        await(() -> !DesktopTaskRunner.hasActiveTasks() && runtime.security().securityState().activeTasks() == 0);
        onEdt(() -> {
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            return null;
        });
        await(() -> !frame.isDisplayable());
    }

    private static void click(Robot robot, Component target) throws Exception {
        var center = onEdt(() -> {
            var point = target.getLocationOnScreen();
            point.translate(target.getWidth() / 2, target.getHeight() / 2);
            return point;
        });
        robot.mouseMove(center.x, center.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }
}

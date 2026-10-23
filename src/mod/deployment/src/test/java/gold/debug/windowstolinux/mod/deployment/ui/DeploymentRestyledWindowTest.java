package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.ApplicationLauncher;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModNavigationPane;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Actual startup shell and CLASS Mod together, with isolated storage and no remote work. / 实际启动外壳与 CLASS Mod 联合验收，使用隔离存储而不执行远程操作。 */
class DeploymentRestyledWindowTest extends DeploymentRuntimeFixture {
    @Test
    void currentControlSurfacesKeepFixedContentAndOriginalClassModDraft() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        enable();
        Path evidence = Files.createDirectories(Path.of("target/phase13-p0"));
        Files.writeString(evidence.resolve("controls-layout.csv"),
            "variant,contentWidth,contentHeight,sidebarWidth,headerHeight,statusHeight,modWidth,modHeight\n");
        var create = ApplicationLauncher.class.getDeclaredMethod("createWindow", PlatformRuntime.class,
            MessageCatalog.class, DesktopDisplayConfig.class, ThemeMode.class,
            FailureReportStore.class, PlatformModelService.class);
        create.setAccessible(true);
        var install = ApplicationLauncher.class.getDeclaredMethod("installModManagement", PlatformRuntime.class,
            PlatformFrame.class);
        install.setAccessible(true);
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int scale : new int[]{100, 150}) {
                    String variant = theme + "-zh-CN-" + scale;
                    try (var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION)) {
                        runtime.security().unlock(PASSWORD.toCharArray());
                        var models = runtime.models();
                        PlatformFrame frame = edt(() -> (PlatformFrame) create.invoke(null, runtime,
                            MessageCatalog.forLanguageTag("zh-CN"), new DesktopDisplayConfig("zh-CN", theme, scale, false),
                            theme, FailureReportStore.disabled(), models));
                        runtime.started();
                        try {
                            if (scalar(runtime, "SELECT COUNT(*) FROM server_profile") == 0)
                                runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(
                                        "visual-server", "127.0.0.1", 22, "fixture", "fixture", "控件验收服务器")),
                                    "fixture".toCharArray());
                            edt(() -> {
                                frame.setVisible(true);
                                return null;
                            });
                            var report = runtime.startDevelopmentMods(workspace(), frame.pages());
                            assertTrue(report.problems().isEmpty(), report.problems().toString());
                            edt(() -> {
                                frame.showModStartup(report.running().size(), report.problems());
                                return null;
                            });
                            install.invoke(null, runtime, frame);
                            await(() -> ((ModNavigationPane) named(frame, "mods.navigation")).available());
                            openInside(frame, "static-deployment");
                            await(() -> ((JComboBox<?>) named(frame, "deployment.server")).getItemCount() == 1);
                            JTextField draft = edt(() -> (JTextField) named(frame, "deployment.sourceName"));
                            JTabbedPane tabs = edt(() -> (JTabbedPane) named(frame, "deployment.workspace.tabs"));
                            edt(() -> {
                                draft.setText("controls-保留的部署草稿");
                                frame.showHome();
                                frame.pages().navigateView(new ModPageRegistry.PageKey(OWNER, "workspace"));
                                assertEquals("static-deployment", frame.currentPage().detailId());
                                return null;
                            });
                            for (String id : new String[]{"static-deployment", "agent-deployment", "applications"}) {
                                openInside(frame, id);
                                await(() -> id.equals("applications") ? named(frame, "applications.refresh").isEnabled()
                                    : ((JComboBox<?>) named(frame, id.equals("static-deployment")
                                    ? "deployment.server" : "deployment.agent.server")).getItemCount() == 1);
                                await(() -> !DesktopTaskRunner.hasActiveTasks());
                                captureControlsAt(frame, 1600, 900, variant + "-" + id);
                                captureControlsAt(frame, 1280, 720, variant + "-" + id + "-minimum");
                                edt(() -> {
                                    assertEquals(id, frame.currentPage().detailId());
                                    assertSame(tabs, named(frame, "deployment.workspace.tabs"));
                                    assertSame(draft, named(frame, "deployment.sourceName"));
                                    assertEquals("controls-保留的部署草稿", draft.getText());
                                    return null;
                                });
                            }
                            openInside(frame, "static-deployment");
                            edt(() -> {
                                ((JComboBox<?>) named(frame, "deployment.sourceMode")).setSelectedIndex(1);
                                ((JButton) named(frame, "deployment.advanced")).doClick(0);
                                var advanced = (JDialog) Stream.of(frame.getOwnedWindows())
                                    .filter(window -> "deployment.advanced.window".equals(window.getName()))
                                    .findFirst().orElseThrow();
                                var url = (JTextField) named(advanced, "deployment.git.url");
                                url.setText("https://example.invalid/controls-retained.git");
                                capture(advanced, "controls-" + variant + "-advanced");
                                var scroll = (JScrollPane) advanced.getContentPane().getComponent(0);
                                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                                capture(advanced, "controls-" + variant + "-advanced-bottom");
                                advanced.setVisible(false);
                                ((JButton) named(frame, "deployment.advanced")).doClick(0);
                                assertEquals("https://example.invalid/controls-retained.git", url.getText());
                                advanced.setVisible(false);
                                return null;
                            });
                            frame.pages().closeView(new ModPageRegistry.PageKey(OWNER, "workspace"));
                        } finally {
                            edt(() -> {
                                frame.dispose();
                                return null;
                            });
                            await(() -> !DesktopTaskRunner.hasActiveTasks()
                                && runtime.security().securityState().activeTasks() == 0);
                        }
                    }
                }
        } finally {
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    @Test
    void actualMainWindowKeepsOriginalModDraftAndOwnedWindowAcrossAppearanceAndLock() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        enable();
        Files.createDirectories(Path.of("target/phase13-p0"));
        Files.writeString(Path.of("target/phase13-p0/layout.csv"), "variant,frameWidth,frameHeight,sidebarWidth,hostWidth,hostHeight,contentWidth,contentHeight,rootWidth,rootHeight\n");
        for (int scale : new int[]{100, 150}) {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                for (String language : new String[]{"zh-CN", "en"}) {
                    String variant = theme + "-" + language + "-" + scale;
                    try (var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
                         var focusTrace = edt(FocusTrace::new)) {
                        runtime.security().unlock(PASSWORD.toCharArray());
                        var models = runtime.models();
                        var create = ApplicationLauncher.class.getDeclaredMethod("createWindow", PlatformRuntime.class,
                            MessageCatalog.class, DesktopDisplayConfig.class, ThemeMode.class,
                            FailureReportStore.class, PlatformModelService.class);
                        create.setAccessible(true);
                        PlatformFrame frame = edt(() -> (PlatformFrame) create.invoke(null, runtime,
                            MessageCatalog.forLanguageTag(language), new DesktopDisplayConfig(language, theme, scale, false),
                            theme, FailureReportStore.disabled(), models));
                        runtime.started();
                        if (scalar(runtime, "SELECT COUNT(*) FROM server_profile") == 0)
                            runtime.servers().saveServerProfile(
                                ServerProfile.fromStored(new StoredServerProfile("visual-server", "127.0.0.1", 22,
                                    "fixture", "fixture", "验收服务器 / Long isolated development server")),
                                "fixture".toCharArray());

                        try {
                            edt(() -> {
                                frame.setVisible(true);
                                return null;
                            });
                            var report = runtime.startDevelopmentMods(workspace(), frame.pages());
                            assertTrue(report.problems().isEmpty(), report.problems().toString());
                            edt(() -> {
                                frame.showModStartup(report.running().size(), report.problems());
                                return null;
                            });
                            var install = ApplicationLauncher.class.getDeclaredMethod("installModManagement", PlatformRuntime.class,
                                PlatformFrame.class);
                            install.setAccessible(true);
                            install.invoke(null, runtime, frame);
                            await(() -> components(frame).anyMatch(c -> "mods.management".equals(c.getName())));
                            await(() -> ((ModNavigationPane) named(frame, "mods.navigation")).available());
                            assertEquals(1, frame.pages().contributions().stream()
                                .filter(page -> !page.spec().settingsPage()).count());
                            assertEquals("workspace", frame.pages().contributions().getFirst().key().pageId());
                            assertTrue(components(frame).noneMatch(c -> "deployment.workspace".equals(c.getName())));
                            await(() -> named(frame, "servers.refresh").isEnabled());
                            edt(() -> {
                                ((JButton) named(frame, "servers.refresh")).doClick(0);
                                return null;
                            });
                            await(() -> ((JTable) named(frame, "servers.table")).getRowCount() == 1);
                            edt(() -> {
                                ((JTabbedPane) named(frame, "platform.pages"))
                                    .setSelectedComponent(named(frame, "platform.servers"));
                                capture(frame, variant + "-server-card");
                                return null;
                            });
                            captureAt(frame, 800, 560, variant + "-server-card-minimum");
                            for (String id : new String[]{"static-deployment", "agent-deployment", "applications"}) {
                                var key = new ModPageRegistry.PageKey(OWNER, id);
                                openInside(frame, id);
                                if (id.equals("applications"))
                                    await(() -> named(frame, "applications.refresh").isEnabled());
                                else
                                    await(() -> ((JComboBox<?>) named(frame,
                                        id.equals("static-deployment")
                                            ? "deployment.server"
                                            : "deployment.agent.server"))
                                        .getItemCount() == 1);
                                await(() -> !DesktopTaskRunner.hasActiveTasks());
                                edt(() -> {
                                    assertEquals(id, frame.currentPage().detailId());
                                    frame.showHome();
                                    frame.pages().navigateView(new ModPageRegistry.PageKey(OWNER, "workspace"));
                                    assertEquals(id, frame.currentPage().detailId());
                                    return null;
                                });
                                if (id.equals("applications")) edt(() -> {
                                    var list = (JList<?>) named(frame, "applications.inventory");
                                    assertEquals(UIManager.getColor("Panel.background").getRGB(), inventoryPixel(list, false));
                                    assertEquals(UIManager.getColor("List.selectionBackground").getRGB(), inventoryPixel(list, true));
                                    return null;
                                });
                                captureAt(frame, 1120, 760, variant + "-" + id);
                                captureAt(frame, 800, 560, variant + "-" + id + "-minimum");
                            }
                            capturePreviews(frame, variant);
                            var key = new ModPageRegistry.PageKey(OWNER, "workspace");
                            openInside(frame, "static-deployment");
                            edt(() -> {
                                ((JButton) named(frame, "navigation.collapse")).doClick(0);
                                return null;
                            });
                            await(() -> named(frame, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale
                                .scale(64));
                            captureAt(frame, 1120, 760, variant + "-default-collapsed");
                            captureAt(frame, 800, 560, variant + "-minimum-collapsed");
                            edt(() -> {
                                capture(frame, variant + "-pinned-collapsed");
                                var pin = (JButton) named(frame, "mods.pinned.0");
                                assertEquals(pin.getAccessibleContext().getAccessibleName(), pin.getText());
                                assertFalse(pin.getAccessibleContext().getAccessibleName().isBlank());
                                assertTrue(pin.getWidth() >= pin.getIcon().getIconWidth() + pin.getInsets().left
                                    + pin.getInsets().right, pin.getBounds() + " / " + pin.getInsets());
                                ((JButton) named(frame, "navigation.collapse")).doClick(0);
                                return null;
                            });
                            await(() -> named(frame, "platform.sidebar").getWidth() == 160);
                            var field = edt(() -> (JTextField) named(frame, "deployment.sourceName"));
                            var originalTabs = edt(() -> (JTabbedPane) named(frame, "deployment.workspace.tabs"));
                            edt(() -> {
                                frame.setSize(1120, 760);
                                field.setText("unsaved-中文-project");
                                ((JComboBox<?>) named(frame, "deployment.sourceMode")).setSelectedIndex(1);
                                ((JButton) named(frame, "deployment.advanced")).doClick(0);
                                return null;
                            });
                            JDialog advanced = edt(() -> (JDialog) Stream.of(frame.getOwnedWindows())
                                .filter(w -> "deployment.advanced.window".equals(w.getName())).findFirst()
                                .orElseThrow());
                            edt(() -> {
                                ((JTextField) named(advanced, "deployment.git.url"))
                                    .setText("https://example.invalid/retained.git");
                                capture(advanced, variant + "-advanced");
                                var scroll = (JScrollPane) advanced.getContentPane().getComponent(0);
                                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                                capture(advanced, variant + "-advanced-bottom");
                                scroll.getVerticalScrollBar().setValue(0);
                                advanced.setVisible(false);
                                ((JTabbedPane) named(frame, "platform.pages"))
                                    .setSelectedComponent(named(frame, "platform.appearance"));
                                ((JComboBox<?>) named(frame, "appearance.language"))
                                    .setSelectedItem(language.equals("en") ? "zh-CN" : "en");
                                ((JComboBox<?>) named(frame, "appearance.theme"))
                                    .setSelectedItem(theme == ThemeMode.LIGHT ? ThemeMode.DARK : ThemeMode.LIGHT);
                                ((JButton) named(frame, "appearance.save")).doClick(0);
                                return null;
                            });
                            await(() -> named(frame, "appearance.save").isEnabled()
                                && !DesktopTaskRunner.hasActiveTasks());
                            openInside(frame, "static-deployment");
                            edt(() -> {
                                focusTrace.bind(originalTabs);
                                return null;
                            });
                            {
                                edt(() -> {
                                    assertSame(field, named(frame, "deployment.sourceName"));
                                    assertSame(originalTabs, named(frame, "deployment.workspace.tabs"));
                                    assertEquals(language.equals("en") ? "标准 / 辅助部署" : "Standard / assisted deployment",
                                        originalTabs.getTitleAt(0));
                                    assertEquals("unsaved-中文-project", field.getText());
                                    assertEquals(language.equals("en") ? "高级选项" : "Advanced options", advanced.getTitle());
                                    ((JButton) named(frame, "deployment.advanced")).doClick(0);
                                    assertEquals("https://example.invalid/retained.git",
                                        ((JTextField) named(advanced, "deployment.git.url")).getText());
                                    ((JButton) named(frame, "security.lock")).doClick(0);
                                    return null;
                                });
                                await(() -> !runtime.security().securityState().unlocked()
                                    && frame.getContentPane().isVisible() && frame.getGlassPane().isVisible());
                                var tabRobot = new Robot();
                                tabRobot.setAutoDelay(40);
                                edt(() -> {
                                    frame.toFront();
                                    frame.requestFocus();
                                    return null;
                                });
                                tabRobot.waitForIdle();
                                await(() -> frame.isActive() && frame.isFocused());
                                Point tabPoint = edt(() -> {
                                    var tabs = (JTabbedPane) named(frame, "deployment.workspace.tabs");
                                    assertEquals(0, tabs.getSelectedIndex());
                                    assertTrue(tabs.isShowing() && tabs.isEnabled() && tabs.isFocusable());
                                    Rectangle header = tabs.getBoundsAt(0);
                                    Point gatePoint = SwingUtilities.convertPoint(tabs, header.x + header.width / 2,
                                        header.y + header.height / 2, frame.getGlassPane());
                                    assertFalse(frame.getGlassPane().contains(gatePoint.x, gatePoint.y), "The actual locked tab header remains admitted navigation");
                                    Point origin = tabs.getLocationOnScreen();
                                    return new Point(origin.x + header.x + header.width / 2,
                                        origin.y + header.y + header.height / 2);
                                });
                                tabRobot.mouseMove(tabPoint.x, tabPoint.y);
                                tabRobot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                                tabRobot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                                tabRobot.waitForIdle();
                                edt(() -> {
                                    var tabs = (JTabbedPane) named(frame, "deployment.workspace.tabs");
                                    capture(frame, variant + "-locked-focus");
                                    assertTrue(navigationHasFocus(tabs) || tabs.requestFocusInWindow(), "An active unlocked-navigation target must have or accept keyboard focus: "
                                        + focusTrace.describe(frame));
                                    return null;
                                });
                                await(() -> navigationHasFocus(originalTabs), () -> focusTrace.describe(frame));
                                edt(() -> {
                                    focusTrace.bindings();
                                    return null;
                                });
                                tabRobot.keyPress(java.awt.event.KeyEvent.VK_RIGHT);
                                tabRobot.keyRelease(java.awt.event.KeyEvent.VK_RIGHT);
                                tabRobot.waitForIdle();
                                await(() -> originalTabs.getSelectedIndex() == 1, () -> focusTrace.describe(frame));
                            }
                            edt(() -> {
                                assertSame(originalTabs, named(frame, "deployment.workspace.tabs"));
                                originalTabs.setSelectedIndex(0);
                                originalTabs.revalidate();
                                return null;
                            });
                            var businessRobot = new Robot();
                            businessRobot.setAutoDelay(40);
                            businessRobot.waitForIdle();
                            await(() -> {
                                frame.validate();
                                if (!field.isShowing() || field.getWidth() <= 0 || field.getHeight() <= 0)
                                    return false;
                                var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, field);
                                assertNotNull(viewport, "The original form must retain its real scroll viewport");
                                var view = (JComponent) viewport.getView();
                                view.scrollRectToVisible(SwingUtilities.convertRectangle(field,
                                    new Rectangle(0, 0, field.getWidth(), field.getHeight()), view));
                                Rectangle visible = field.getVisibleRect();
                                return visible.width > 0 && visible.height > 0;
                            }, () -> businessGeometry(field, originalTabs));
                            Point businessPoint = edt(() -> {
                                assertSame(field, named(frame, "deployment.sourceName"));
                                assertEquals(0, originalTabs.getSelectedIndex());
                                assertTrue(field.isShowing());
                                Rectangle visible = field.getVisibleRect();
                                assertTrue(visible.width > 0 && visible.height > 0, () -> businessGeometry(field, originalTabs));
                                Point gatePoint = SwingUtilities.convertPoint(field, visible.x + visible.width / 2,
                                    visible.y + visible.height / 2, frame.getGlassPane());
                                assertTrue(frame.getGlassPane().contains(gatePoint.x, gatePoint.y), "The locked business text field must remain blocked");
                                Point origin = field.getLocationOnScreen();
                                return new Point(origin.x + visible.x + visible.width / 2, origin.y + visible.y + visible.height / 2);
                            });
                            businessRobot.mouseMove(businessPoint.x, businessPoint.y);
                            businessRobot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                            businessRobot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                            businessRobot.keyPress(java.awt.event.KeyEvent.VK_Q);
                            businessRobot.keyRelease(java.awt.event.KeyEvent.VK_Q);
                            businessRobot.waitForIdle();
                            edt(() -> {
                                assertSame(field, named(frame, "deployment.sourceName"));
                                assertEquals("unsaved-中文-project", field.getText());
                                assertFalse(advanced.isVisible());
                                capture(frame, variant + "-locked");
                                return null;
                            });
                            SwingUtilities.invokeLater(() -> ((JButton) named(frame, "security.lock")).doClick(0));
                            await(() -> Stream.of(frame.getOwnedWindows()).anyMatch(window -> window.isShowing()
                                && "security.unlockDialog".equals(window.getName())));
                            edt(() -> {
                                var prompt = Stream.of(frame.getOwnedWindows()).filter(window -> window.isShowing()
                                    && "security.unlockDialog".equals(window.getName())).findFirst().orElseThrow();
                                ((JPasswordField) named(prompt, "security.password")).setText(PASSWORD);
                                ((JButton) named(prompt, "security.submit")).doClick(0);
                                return null;
                            });
                            await(() -> runtime.security().securityState().unlocked()
                                && !runtime.security().securityState().changing());
                            frame.pages().closeView(key);
                            edt(() -> {
                                assertFalse(advanced.isDisplayable());
                                return null;
                            });
                            openInside(frame, "static-deployment");
                            edt(() -> {
                                assertNotSame(field, named(frame, "deployment.sourceName"));
                                return null;
                            });
                            frame.pages().closeView(new ModPageRegistry.PageKey(OWNER, "workspace"));
                        } finally {
                            edt(() -> {
                                frame.dispose();
                                return null;
                            });
                            await(() -> !DesktopTaskRunner.hasActiveTasks()
                                && runtime.security().securityState().activeTasks() == 0);
                        }
                    }
                }
            }
        }
    }

    private static void openInside(PlatformFrame frame, String id) throws Exception {
        frame.pages().openView(new ModPageRegistry.PageKey(OWNER, "workspace"));
        await(() -> components(frame).filter(component -> "deployment.workspace.tabs".equals(component.getName()))
            .anyMatch(Component::isEnabled));
        frame.pages().navigateView(new ModPageRegistry.PageKey(OWNER, "workspace"), id);
        await(() -> id.equals(frame.currentPage().detailId()) && components(frame).anyMatch(component -> component.isShowing()
            && (id.equals("applications") ? "applications.refresh"
            : id.equals("agent-deployment") ? "deployment.agent.server" : "deployment.server").equals(component.getName())));
    }

    private static <T> int inventoryPixel(JList<T> list, boolean selected) {
        var row = (JComponent) list.getCellRenderer().getListCellRendererComponent(list, null, 0, selected, false);
        row.setSize(400, 86);
        layout(row);
        var image = new BufferedImage(400, 86, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            row.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(20, 20);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root),
            root instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(DeploymentRestyledWindowTest::components)
                : Stream.empty());
    }

    private static Component named(Component root, String name) {
        return components(root).filter(c -> name.equals(c.getName())).findFirst()
            .orElseThrow(() -> new AssertionError(name));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        await(condition, () -> "Condition did not become true");
    }

    private static void await(java.util.function.BooleanSupplier condition, Supplier<String> diagnostic) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (edt(condition::getAsBoolean))
                return;
            Thread.sleep(25);
        }
        edt(() -> {
            assertTrue(condition.getAsBoolean(), diagnostic);
            return null;
        });
    }

    private static boolean navigationHasFocus(JTabbedPane tabs) {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus == tabs || focus != null && SwingUtilities.isDescendingFrom(focus, tabs)
            && !SwingUtilities.isDescendingFrom(focus, tabs.getSelectedComponent());
    }

    private static String businessGeometry(JTextField field, JTabbedPane tabs) {
        var details = new StringBuilder("The original business hit must be visible after tab layout: selectedIndex=")
            .append(tabs.getSelectedIndex()).append(", fieldVisibleRect=").append(field.getVisibleRect());
        for (Component ancestor = field; ancestor != null; ancestor = ancestor.getParent()) {
            details.append("\n").append(ancestor.getClass().getName()).append('[').append(ancestor.getName())
                .append("] bounds=").append(ancestor.getBounds()).append(", showing=").append(ancestor.isShowing())
                .append(", valid=").append(ancestor.isValid());
            if (ancestor instanceof JViewport viewport)
                details.append(", viewRect=").append(viewport.getViewRect()).append(", viewSize=").append(viewport.getViewSize())
                    .append(", extent=").append(viewport.getExtentSize()).append(", position=").append(viewport.getViewPosition());
        }
        return details.toString();
    }

    private static String identity(Component component) {
        return component == null ? "null" : component.getClass().getName() + "@"
            + Integer.toHexString(System.identityHashCode(component)) + "[name=" + component.getName()
            + ",showing=" + component.isShowing() + ",enabled=" + component.isEnabled() + "]";
    }

    private static final class FocusTrace implements AutoCloseable {
        private JTabbedPane tabs;

        private final KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();

        private final ArrayDeque<String> events = new ArrayDeque<>();

        private String bindings = "not observed";

        private final KeyEventDispatcher before = event -> {
            key("before gate", event);
            return false;
        };

        private final KeyEventDispatcher after = event -> {
            key("after gate", event);
            return false;
        };

        private final java.awt.event.KeyListener componentKeys = new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                key("tab pressed", event);
            }

            @Override
            public void keyReleased(java.awt.event.KeyEvent event) {
                key("tab released", event);
            }
        };

        private final PropertyChangeListener listener = event -> {
            if (events.size() == 64) events.removeFirst();
            events.addLast(event.getPropertyName() + ": " + describeValue(event.getOldValue())
                + " -> " + describeValue(event.getNewValue()));
        };

        private FocusTrace() {
            manager.addKeyEventDispatcher(before);
        }

        private void bind(JTabbedPane tabs) {
            this.tabs = tabs;
            for (String property : new String[]{"focusOwner", "permanentFocusOwner", "activeWindow", "focusedWindow"})
                manager.addPropertyChangeListener(property, listener);
            tabs.addPropertyChangeListener("UI", listener);
            tabs.addKeyListener(componentKeys);
            manager.addKeyEventDispatcher(after);
        }

        private void key(String stage, java.awt.event.KeyEvent event) {
            if (tabs == null || event.getKeyCode() != java.awt.event.KeyEvent.VK_RIGHT
                && event.getKeyCode() != java.awt.event.KeyEvent.VK_Q) return;
            if (events.size() == 64) events.removeFirst();
            events.addLast(stage + ": id=" + event.getID() + ", code=" + event.getKeyCode()
                + ", modifiers=" + event.getModifiersEx() + ", consumed=" + event.isConsumed()
                + ", source=" + identity(event.getComponent()) + ", focus=" + identity(manager.getFocusOwner()));
        }

        private void bindings() {
            var stroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0, false);
            Object binding = tabs.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            Action action = binding == null ? null : tabs.getActionMap().get(binding);
            var enabled = new java.util.ArrayList<Boolean>();
            for (int index = 0; index < tabs.getTabCount(); index++) enabled.add(tabs.isEnabledAt(index));
            bindings = "count=" + tabs.getTabCount() + ", placement=" + tabs.getTabPlacement()
                + ", leftToRight=" + tabs.getComponentOrientation().isLeftToRight() + ", enabled=" + enabled
                + ", focusedBinding=" + binding + ", ancestorBinding="
                + tabs.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke)
                + ", action=" + (action == null ? "null" : action.getClass().getName() + "/enabled=" + action.isEnabled())
                + ", selectionFollowsFocus=" + UIManager.get("TabbedPane.selectionFollowsFocus");
        }

        private String describe(PlatformFrame frame) {
            var currentTabs = named(frame, "deployment.workspace.tabs");
            Component focus = manager.getFocusOwner();
            return "Locked navigation keyboard state: frameActive=" + frame.isActive() + ", frameFocused=" + frame.isFocused()
                + ", activeWindow=" + identity(manager.getActiveWindow()) + ", focusedWindow=" + identity(manager.getFocusedWindow())
                + ", focusOwner=" + identity(focus) + ", permanentFocusOwner=" + identity(manager.getPermanentFocusOwner())
                + ", originalTabs=" + identity(tabs) + ", currentTabs=" + identity(currentTabs)
                + ", sameTabs=" + (tabs == currentTabs) + ", tabUI=" + tabs.getUI().getClass().getName()
                + ", descendantFocus=" + (focus != null && SwingUtilities.isDescendingFrom(focus, tabs))
                + ", headerFocus=" + navigationHasFocus(tabs) + ", selectedIndex=" + tabs.getSelectedIndex()
                + ", bindings=" + bindings + ", focusEvents=" + events;
        }

        private static String describeValue(Object value) {
            return value instanceof Component component ? identity(component) : String.valueOf(value);
        }

        @Override
        public void close() throws Exception {
            if (!SwingUtilities.isEventDispatchThread()) {
                edt(() -> {
                    close();
                    return null;
                });
                return;
            }
            manager.removeKeyEventDispatcher(before);
            manager.removeKeyEventDispatcher(after);
            for (String property : new String[]{"focusOwner", "permanentFocusOwner", "activeWindow", "focusedWindow"})
                manager.removePropertyChangeListener(property, listener);
            if (tabs != null) {
                tabs.removePropertyChangeListener("UI", listener);
                tabs.removeKeyListener(componentKeys);
            }
        }
    }

    private static void capture(Window window, String name) throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            components(window).filter(Container.class::isInstance).map(Container.class::cast)
                .forEach(Container::invalidate);
            window.validate();
        }
        var bitmap = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            window.paint(graphics);
        } finally {
            graphics.dispose();
        }
        var file = Path.of("target/phase13-p0", (name.startsWith("controls-") ? name : "main-" + name) + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(bitmap, "png", file.toFile());
    }

    private static void captureControlsAt(PlatformFrame window, int width, int height, String name) throws Exception {
        edt(() -> {
            window.setSize(width + window.getWidth() - window.getContentPane().getWidth(),
                height + window.getHeight() - window.getContentPane().getHeight());
            window.validate();
            return null;
        });
        await(() -> window.getContentPane().getSize().equals(new Dimension(width, height)));
        edt(() -> {
            components(window).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast)
                .filter(Component::isShowing).forEach(scroll -> scroll.getVerticalScrollBar().setValue(0));
            capture(window, "controls-" + name);
            assertEquals(new Dimension(width, height), window.getContentPane().getSize());
            assertEquals(160, named(window, "platform.sidebar").getWidth());
            assertEquals(60, named(window, "platform.header").getHeight());
            assertEquals(30, named(window, "platform.statusBar").getHeight());
            var mod = named(window, "deployment.workspace");
            assertTrue(mod.getWidth() > 0 && mod.getHeight() > 0);
            Files.writeString(Path.of("target/phase13-p0/controls-layout.csv"), name + "," + width + "," + height
                + ",160,60,30," + mod.getWidth() + "," + mod.getHeight() + "\n", StandardOpenOption.APPEND);
            return null;
        });
    }

    private static void captureAt(PlatformFrame window, int width, int height, String name) throws Exception {
        edt(() -> {
            window.setSize(width, height);
            window.validate();
            return null;
        });
        edt(() -> {
            capture(window, name);
            var root = components(window).filter(c -> "deployment.workspace".equals(c.getName()) && c.isShowing()).findFirst();
            if (root.isPresent()) {
                var host = named(window, "mods.workspace");
                var body = named(window, "mods.workspace.content");
                var actual = root.orElseThrow();
                String row = name + "," + window.getWidth() + "," + window.getHeight() + ","
                    + named(window, "platform.sidebar").getWidth() + "," + host.getWidth() + "," + host.getHeight() + ","
                    + body.getWidth() + "," + body.getHeight() + "," + actual.getWidth() + "," + actual.getHeight() + "\n";
                Files.writeString(Path.of("target/phase13-p0/layout.csv"), row, StandardOpenOption.APPEND);
                assertTrue(actual.getWidth() > 0 && actual.getHeight() > 0);
                var tabs = (JTabbedPane) named(window, "deployment.workspace.tabs");
                assertEquals(4, tabs.getTabCount(), "The actual production workspace also retains the explicit environment entry");
                assertTrue(tabs.getSelectedComponent().getWidth() > 0 && tabs.getSelectedComponent().getHeight() > 0);
                var close = named(window, "mods.workspace.close");
                assertTrue(close.getWidth() > 0 && close.getX() + close.getWidth() <= close.getParent().getWidth());
            }
            if (width == 800) {
                var page = ((JTabbedPane) named(window, "platform.pages")).getSelectedComponent();
                var scroll = components(page).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast)
                    .filter(value -> value.isShowing() && value.getVerticalScrollBar().isVisible()).findFirst();
                if (scroll.isPresent()) {
                    var bar = scroll.orElseThrow().getVerticalScrollBar();
                    bar.setValue(bar.getMaximum());
                    capture(window, name + "-bottom");
                    bar.setValue(0);
                }
            }
            return null;
        });
    }

    private static void capturePreviews(PlatformFrame frame, String variant) throws Exception {
        Window gallery = edt(() -> {
            ((JTabbedPane) named(frame, "platform.pages"))
                .setSelectedComponent(named(frame, "platform.security"));
            ((JToggleButton) named(frame, "settings.advanced")).doClick(0);
            ((JButton) named(frame, "settings.uiDebug")).doClick(0);
            return Stream.of(frame.getOwnedWindows())
                .filter(value -> "settings.uiDebug.gallery".equals(value.getName())).findFirst().orElseThrow();
        });
        for (String kind : new String[]{"go_service", "node_service"}) {
            var key = new ModPageRegistry.PageKey(OWNER, "runtime-preview-" + kind);
            frame.pages().openPreview(key);
            edt(() -> {
                var preview = Stream.of(gallery.getOwnedWindows())
                    .filter(value -> ("debug.preview." + OWNER + ".runtime-preview-" + kind).equals(value.getName()))
                    .findFirst().orElseThrow();
                capture(preview, variant + "-runtime-preview-" + kind);
                return null;
            });
            frame.pages().closeView(key);
        }
        edt(() -> {
            gallery.setVisible(false);
            return null;
        });
    }
}

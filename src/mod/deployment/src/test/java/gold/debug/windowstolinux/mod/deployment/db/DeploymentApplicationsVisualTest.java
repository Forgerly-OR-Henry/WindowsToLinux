package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationsPage;
import org.junit.jupiter.api.Test;

/** Renders the actual application page from isolated real publication fixtures. / 从隔离的真实发布夹具渲染应用页面。 */
class DeploymentApplicationsVisualTest extends DeploymentAutostartFixture {
    @Test
    void rendersSavedApplicationPageAcrossThemesScalesAndWindowSizes() throws Exception {
        enable();
        ThemeMode previousTheme = FlatLaf.isLafDark() ? ThemeMode.DARK : ThemeMode.LIGHT;
        int previousZoom = Math.round(UIScale.getZoomFactor() * 100);
        var view = new AtomicReference<DeploymentApplicationsPage>();
        var window = new AtomicReference<JFrame>();
        var content = new AtomicReference<JComponent>();
        var logOutput = new AtomicReference<JTextArea>();
        Path output = Path.of(System.getProperty("deployment.ui.screenshots", "target/deployment-ui-screenshots"));
        Files.createDirectories(output);
        try (var runtime = open()) {
            start(runtime, true);
            usageServer(runtime);
            for (String name : new String[]{"orders", "admin", "docs", "files", "search", "api", "worker", "job"})
                seedUsage(runtime, usageGraph(name, name.equals("job"), true), 1);
            SwingUtilities.invokeAndWait(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var page = new DeploymentApplicationsPage(new DeploymentApplicationsService(context(runtime)));
                view.set(page);
                content.set(page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
                var frame = new JFrame("已部署应用 · 实际 Swing 验证");
                window.set(frame);
                frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                frame.setContentPane(content.get());
                content.get().setPreferredSize(new Dimension(1600, 900));
                frame.pack();
                frame.setLocation(0, 0);
                frame.setVisible(true);
            });
            waitEdt(() -> {
                var list = (JList<?>) named(content.get(), "applications.inventory");
                return list.isEnabled() && list.getModel().getSize() == 8 && list.getSelectedValue() != null;
            });
            for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int zoom : new int[]{100, 150})
                    for (boolean minimum : new boolean[]{false, true}) {
                        SwingUtilities.invokeAndWait(() -> {
                            DesktopThemeService.install(theme, zoom);
                            SwingUtilities.updateComponentTreeUI(window.get());
                            view.get().environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, theme.name(), false));
                            content.get().setPreferredSize(new Dimension(minimum ? 1280 : 1600, minimum ? 720 : 900));
                            window.get().pack();
                            window.get().validate();
                        });
                        SwingUtilities.invokeAndWait(() -> {
                            layout(content.get());
                            assertNotNull(((JList<?>) named(content.get(), "applications.inventory")).getSelectedValue());
                            assertEquals(UIManager.getColor("Table.background"), named(content.get(), "applications.inventory").getBackground());
                            assertEquals(0, ((JScrollPane) named(content.get(), "applications.detailScroll")).getViewport().getViewPosition().y);
                            assertTrue(named(content.get(), "applications.inspect").isEnabled());
                            for (String name : new String[]{"applications.refresh", "applications.discover", "applications.logs", "applications.inspect", "applications.more"}) {
                                var button = named(content.get(), name);
                                assertTrue(button.getWidth() >= button.getPreferredSize().width,
                                    name + " must keep its full natural width");
                                assertTrue(button.getX() >= 0 && button.getX() + button.getWidth() <= button.getParent().getWidth(),
                                    name + " must stay inside its parent");
                            }
                            var actions = named(content.get(), "applications.actionBar");
                            assertEquals(actions.getParent().getHeight(), actions.getY() + actions.getHeight());
                            assertFalse(SwingUtilities.isDescendingFrom(actions, named(content.get(), "applications.detailScroll")));
                            assertFalse(hasNestedScroll((Container) named(content.get(), "delivery.panel")));
                            String suffix = theme + "-" + zoom + "-" + (minimum ? "minimum" : "normal") + ".png";
                            writeImage(content.get(), output.resolve("applications-" + suffix));
                            var inventory = (JList<?>) named(content.get(), "applications.inventory");
                            Object selected = inventory.getSelectedValue();
                            ((JButton) named(content.get(), "applications.logs")).doClick();
                            var dialog = logsWindow();
                            dialog.validate();
                            layout(dialog.getContentPane());
                            var text = (JTextArea) named(dialog.getContentPane(), "applications.tasks");
                            if (logOutput.get() == null) logOutput.set(text);
                            else assertSame(logOutput.get(), text);
                            assertEquals(UIManager.getColor("Panel.background"), text.getBackground());
                            assertFalse(named(dialog.getContentPane(), "applications.cancel").isEnabled());
                            assertEquals(java.awt.Dialog.ModalityType.MODELESS, dialog.getModalityType());
                            assertSame(selected, inventory.getSelectedValue());
                            writeImage((JComponent) dialog.getContentPane(), output.resolve("applications-logs-" + suffix));
                            dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
                            assertFalse(dialog.isDisplayable());
                            assertSame(selected, inventory.getSelectedValue());
                        });
                    }
            var scrolledTo = new AtomicReference<Integer>();
            SwingUtilities.invokeAndWait(() -> {
                var scroll = (JScrollPane) named(content.get(), "applications.detailScroll");
                var viewport = scroll.getViewport();
                int y = Math.min(60, Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height));
                assertTrue(y > 0, "The minimum-window fixture must exercise scrolling");
                scrolledTo.set(y);
                viewport.setViewPosition(new java.awt.Point(0, y));
                DesktopThemeService.install(ThemeMode.LIGHT, 150);
                SwingUtilities.updateComponentTreeUI(window.get());
                view.get().environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
                window.get().validate();
            });
            SwingUtilities.invokeAndWait(() -> {
                var scroll = (JScrollPane) named(content.get(), "applications.detailScroll");
                assertEquals(scrolledTo.get().intValue(), scroll.getViewport().getViewPosition().y,
                    "Theme changes preserve the user's detail position");
                ((JList<?>) named(content.get(), "applications.inventory")).setSelectedIndex(1);
            });
            SwingUtilities.invokeAndWait(() -> assertEquals(0,
                ((JScrollPane) named(content.get(), "applications.detailScroll")).getViewport().getViewPosition().y,
                "A different application starts at its heading"));
            SwingUtilities.invokeAndWait(() -> {
                view.get().close();
                window.get().dispose();
            });
            waitEdt(() -> {
                try {
                    return scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") == 0;
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            assertNoActiveResources(runtime);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (view.get() != null) view.get().close();
                if (window.get() != null) window.get().dispose();
                DesktopThemeService.install(previousTheme, previousZoom);
            });
        }
    }

    private static boolean hasNestedScroll(Container parent) {
        for (var child : parent.getComponents()) {
            if (child instanceof JScrollPane) return true;
            if (child instanceof Container nested && hasNestedScroll(nested)) return true;
        }
        return false;
    }

    private static JDialog logsWindow() {
        return java.util.Arrays.stream(java.awt.Window.getWindows())
            .filter(window -> window.isDisplayable() && "applications.logs.window".equals(window.getName()))
            .map(JDialog.class::cast).findFirst().orElseThrow();
    }

    private static void writeImage(JComponent component, Path file) {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(UIManager.getColor("Panel.background"));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        try {
            assertTrue(ImageIO.write(image, "png", file.toFile()));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Component named(Container parent, String name) {
        if (name.equals(parent.getName())) return parent;
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var found = find(nested, name);
                if (found != null) return found;
            }
        }
        throw new AssertionError("Missing actual component: " + name);
    }

    private static Component find(Container parent, String name) {
        if (name.equals(parent.getName())) return parent;
        for (var child : parent.getComponents())
            if (child instanceof Container nested) {
                var found = find(nested, name);
                if (found != null) return found;
            } else if (name.equals(child.getName())) return child;
        return null;
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (var child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static void waitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var done = new AtomicReference<Boolean>();
        do {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Actual application page did not settle");
    }
}

package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.DesktopMain;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModNavigationPane;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Actual startup shell and CLASS Mod together, with isolated storage and no remote work. / 实际启动外壳与 CLASS Mod 联合验收，使用隔离存储而不执行远程操作。 */
class DeploymentRestyledWindowTest extends DeploymentRuntimeFixture {
    @Test
    void actualMainWindowKeepsOriginalModDraftAndOwnedWindowAcrossAppearanceAndLock() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        enable();
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            for (String language : new String[]{"zh-CN", "en"}) {
                String variant = theme + "-" + language + "-" + System.getProperty("flatlaf.uiScale", "1.0");
                try (var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION)) {
                    runtime.security().unlock(PASSWORD.toCharArray());
                    var models = runtime.models();
                    var create = DesktopMain.class.getDeclaredMethod("createWindow", PlatformRuntime.class,
                        MessageCatalog.class, DesktopDisplayConfiguration.class, ThemeMode.class,
                        FailureReportStore.class, PlatformModelService.class);
                    create.setAccessible(true);
                    PlatformFrame frame = edt(() -> (PlatformFrame) create.invoke(null, runtime,
                        MessageCatalog.forLanguageTag(language), new DesktopDisplayConfiguration(language, theme),
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
                        var install = DesktopMain.class.getDeclaredMethod("installModManagement", PlatformRuntime.class,
                            PlatformFrame.class);
                        install.setAccessible(true);
                        install.invoke(null, runtime, frame);
                        await(() -> components(frame).anyMatch(c -> "mods.management".equals(c.getName())));
                        await(() -> ((ModNavigationPane) named(frame, "mods.navigation")).available());
                        for (String id : new String[]{"static-deployment", "agent-deployment", "applications"}) {
                            edt(() -> {
                                ((ModNavigationPane) named(frame, "mods.navigation"))
                                    .pin(new ModPageRegistry.PageKey(OWNER, id), true);
                                return null;
                            });
                            await(() -> ((ModNavigationPane) named(frame, "mods.navigation")).available());
                        }
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
                            frame.pages().openView(key);
                            if (id.equals("applications"))
                                await(() -> named(frame, "applications.refresh").isEnabled());
                            else
                                await(() -> ((JComboBox<?>) named(frame,
                                    id.equals("static-deployment")
                                        ? "deployment.server"
                                        : "deployment.agent.server"))
                                    .getItemCount() == 1);
                            await(() -> !DesktopTaskExecutor.hasActiveTasks());
                            captureAt(frame, 1120, 760, variant + "-" + id);
                            captureAt(frame, 800, 560, variant + "-" + id + "-minimum");
                        }
                        capturePreviews(frame, variant);
                        var key = new ModPageRegistry.PageKey(OWNER, "static-deployment");
                        frame.pages().openView(key);
                        edt(() -> {
                            ((JButton) named(frame, "navigation.collapse")).doClick(0);
                            return null;
                        });
                        await(() -> named(frame, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale
                            .scale(64));
                        edt(() -> {
                            capture(frame, variant + "-pinned-collapsed");
                            var pin = (JButton) named(frame, "mods.pinned.0");
                            assertEquals("", pin.getText());
                            assertFalse(pin.getAccessibleContext().getAccessibleName().isBlank());
                            assertTrue(pin.getWidth() >= pin.getIcon().getIconWidth() + pin.getInsets().left
                                + pin.getInsets().right, pin.getBounds() + " / " + pin.getInsets());
                            ((JButton) named(frame, "navigation.collapse")).doClick(0);
                            return null;
                        });
                        await(() -> named(frame, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale
                            .scale(168));
                        var field = edt(() -> (JTextField) named(frame, "deployment.sourceName"));
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
                            && !DesktopTaskExecutor.hasActiveTasks());
                        frame.pages().openView(key);
                        edt(() -> {
                            assertSame(field, named(frame, "deployment.sourceName"));
                            assertEquals("unsaved-中文-project", field.getText());
                            assertEquals(language.equals("en") ? "高级选项" : "Advanced options", advanced.getTitle());
                            ((JButton) named(frame, "deployment.advanced")).doClick(0);
                            assertEquals("https://example.invalid/retained.git",
                                ((JTextField) named(advanced, "deployment.git.url")).getText());
                            ((JButton) named(frame, "security.lock")).doClick(0);
                            return null;
                        });
                        await(() -> !runtime.security().securityState().unlocked()
                            && !frame.getContentPane().isVisible());
                        edt(() -> {
                            assertFalse(advanced.isVisible());
                            capture(frame, variant + "-locked");
                            ((JButton) named(frame, "security.enter")).doClick(0);
                            ((JPasswordField) named(frame, "security.password")).setText(PASSWORD);
                            ((JButton) named(frame, "security.submit")).doClick(0);
                            return null;
                        });
                        await(() -> runtime.security().securityState().unlocked()
                            && !runtime.security().securityState().changing());
                        frame.pages().closeView(key);
                        edt(() -> {
                            assertFalse(advanced.isDisplayable());
                            return null;
                        });
                        frame.pages().openView(key);
                        edt(() -> {
                            assertNotSame(field, named(frame, "deployment.sourceName"));
                            return null;
                        });
                        for (String id : new String[]{"static-deployment", "agent-deployment", "applications"})
                            frame.pages().closeView(new ModPageRegistry.PageKey(OWNER, id));
                    } finally {
                        edt(() -> {
                            frame.dispose();
                            return null;
                        });
                        await(() -> !DesktopTaskExecutor.hasActiveTasks()
                            && runtime.security().securityState().activeTasks() == 0);
                    }
                }
            }
        }
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!edt(condition::getAsBoolean) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertTrue(edt(condition::getAsBoolean));
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
        var file = Path.of("target/ui-restyle", "main-" + name + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(bitmap, "png", file.toFile());
    }

    private static void captureAt(PlatformFrame window, int width, int height, String name) throws Exception {
        edt(() -> {
            window.setSize(width, height);
            window.validate();
            return null;
        });
        edt(() -> {
            capture(window, name);
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

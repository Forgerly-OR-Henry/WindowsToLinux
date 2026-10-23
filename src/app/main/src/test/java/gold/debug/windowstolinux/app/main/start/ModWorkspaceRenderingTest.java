package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.mod.store.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.PlatformModsPane;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Swing layout and paint evidence over isolated persistence and numeric-loopback HTTP. / 基于隔离持久库及数字回环HTTP的真实Swing布局和绘制证据。 */
class ModWorkspaceRenderingTest {
    private static final String OPTIONAL = "example.optionaladdon";

    private static final String DESCRIPTION = "在本地与 Linux 服务器之间传输文件，支持目录浏览和任务进度。 Transfer files between local storage and Linux servers.";

    @TempDir
    Path root;

    @Test
    void downloadedAndPendingMatrix() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        float previousScale = UIScale.getZoomFactor();
        try {
            for (var theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                for (int scale : new int[]{100, 150})
                    for (String language : List.of("zh-CN", "en")) {
                        String variant = variant(theme, scale, language);
                        System.out.println("Mod Swing downloaded rendering: " + variant);
                        var setup = new PlatformModIntentsTest();
                        setup.root = Files.createDirectories(root.resolve("downloaded-" + variant));
                        try (var runtime = setup.runtime()) {
                            downloadedFacts(runtime);
                            PlatformFrame frame = null;
                            try {
                                frame = open(runtime, language, theme, scale, null);
                                final PlatformFrame window = frame;
                                await(() -> !DesktopTaskRunner.hasActiveTasks());
                                for (var size : sizes()) {
                                    size(window, size);
                                    var pane = (PlatformModsPane) named(window, "platform.mods");
                                    onEdt(() -> {
                                        pane.leaveDetails();
                                        return null;
                                    });
                                    assertLayout(window, "mods", false);
                                    screenshot(window, "downloaded-" + variant + "-" + size.name() + "-full.png");
                                    onEdt(() -> {
                                        pane.selectOwner(P);
                                        return null;
                                    });
                                    assertLayout(window, "mods", true);
                                    onEdt(() -> {
                                        assertTrue(named(window, "mods.dependency." + C).isVisible());
                                        assertTrue(named(window, "mods.dependency." + OPTIONAL).isVisible());
                                        return null;
                                    });
                                    screenshot(window, "downloaded-" + variant + "-" + size.name() + "-details.png");
                                    SwingUtilities.invokeLater(() -> ((AbstractButton) named(window, "mods.viewChanges")).doClick(0));
                                    var dialog = pendingDialog(window);
                                    onEdt(() -> {
                                        assertEquals(2, descendants(dialog).filter(c -> c.getName() != null && c.getName().startsWith("mods.change.")).count());
                                        assertFalse(((JTextArea) named(dialog, "mods.change." + P)).getText().isBlank());
                                        assertFalse(((JTextArea) named(dialog, "mods.change." + FREE)).getText().isBlank());
                                        assertVisibleInside(dialog, (JComponent) named(dialog, "mods.changes.close"));
                                        return null;
                                    });
                                    screenshot(dialog, "downloaded-" + variant + "-" + size.name() + "-pending.png");
                                    onEdt(() -> {
                                        ((AbstractButton) named(dialog, "mods.changes.close")).doClick(0);
                                        return null;
                                    });
                                    await(() -> !dialog.isDisplayable());
                                }
                            } finally {
                                close(frame);
                            }
                        }
                    }
        } finally {
            onEdt(() -> {
                UIScale.setZoomFactor(previousScale);
                return null;
            });
        }
    }

    @Test
    void storeMatrix() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        float previousScale = UIScale.getZoomFactor();
        try {
            for (var theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                for (int scale : new int[]{100, 150})
                    for (String language : List.of("zh-CN", "en")) {
                        String variant = variant(theme, scale, language);
                        System.out.println("Mod Swing store rendering: " + variant);
                        Path home = Files.createDirectories(root.resolve("store-" + variant));
                        try (var fixture = new ModStoreFixture(home)) {
                            var dependency = publish(fixture, C, "连接支持 Connection Support", Map.of(), Map.of());
                            var optional = publish(fixture, OPTIONAL, "校验增强 Optional Validation", Map.of(), Map.of());
                            var primary = publish(fixture, P, "文件传输 File Transfer", Map.of(C, VERSION), Map.of(OPTIONAL, VERSION));
                            publish(fixture, FREE, "应用部署 Application Deployment", Map.of(), Map.of());
                            publish(fixture, G, "一个较长的中英文 Mod 名称 Long Mod title for truncation and responsive layout", Map.of(), Map.of());
                            var catalog = fixture.service().catalog(() -> false);
                            var install = fixture.service().plan(catalog.revision(), new ModStoreSelection(dependency, Set.of(), Map.of()), () -> false);
                            assertTrue(install.valid());
                            assertEquals(TaskResult.SUCCEEDED, fixture.service().install(install.revision(), () -> false, ignored -> {
                            }).outcome());
                            PlatformFrame frame = null;
                            try {
                                frame = open(fixture.runtime(), language, theme, scale, fixture.service());
                                final PlatformFrame window = frame;
                                await(() -> !DesktopTaskRunner.hasActiveTasks()
                                    && ((JList<?>) named(window, "store.results")).getModel().getSize() == 5);
                                for (var size : sizes()) {
                                    size(window, size);
                                    var pane = (gold.debug.windowstolinux.app.ui.mod.ModStorePane) named(window, "mods.store");
                                    onEdt(() -> {
                                        pane.leaveDetails();
                                        return null;
                                    });
                                    assertLayout(window, "store", false);
                                    screenshot(window, "store-" + variant + "-" + size.name() + "-full.png");
                                    onEdt(() -> {
                                        pane.selectOwner(primary.owner());
                                        return null;
                                    });
                                    await(() -> !DesktopTaskRunner.hasActiveTasks() && ((JTable) named(window, "store.plan")).getRowCount() >= 2);
                                    assertLayout(window, "store", true);
                                    screenshot(window, "store-" + variant + "-" + size.name() + "-details.png");
                                }
                                assertEquals(0, fixture.requests(primary));
                                assertEquals(0, fixture.requests(optional));
                                assertEquals(1, fixture.requests(dependency));
                            } finally {
                                close(frame);
                            }
                        }
                    }
        } finally {
            onEdt(() -> {
                UIScale.setZoomFactor(previousScale);
                return null;
            });
        }
    }

    private static ReleaseKey publish(ModStoreFixture fixture, String owner, String name, Map<String, String> required,
                                      Map<String, String> optional) throws Exception {
        return fixture.publish(owner, VERSION, required, optional, Map.of(), 21, "[1.0.0,2.0.0)", name, DESCRIPTION);
    }

    private static void downloadedFacts(PlatformRuntime runtime) throws Exception {
        add(runtime, C, true, Map.of(), null);
        add(runtime, P, true, Map.of(C, VERSION), null);
        add(runtime, FREE, false, Map.of(), null);
        add(runtime, G, false, Map.of(), null);
        release(runtime, P, "1.1.0", Map.of(C, VERSION), null, List.of());
        for (var entry : Map.of(P, "文件传输 File Transfer", C, "连接支持 Connection Support", FREE,
            "应用部署 Application Deployment", G, "一个较长的中英文 Mod 名称 Long Mod title for truncation and responsive layout").entrySet()) {
            var versions = entry.getKey().equals(P) ? List.of(VERSION, "1.1.0") : List.of(VERSION);
            for (String version : versions) {
                var manifest = (ObjectNode) JSON.readTree(manifest(entry.getKey(), version,
                    entry.getKey().equals(P) ? Map.of(C, VERSION) : Map.of(), null, List.of()));
                manifest.put("name", entry.getValue());
                manifest.put("description", DESCRIPTION);
                if (entry.getKey().equals(P)) manifest.withArray("dependencies").addObject()
                    .put("id", OPTIONAL).put("versionRange", VERSION).put("optional", true);
                sql(runtime.persistence().database(), "UPDATE mod_version SET manifest_json=? WHERE mod_id=? AND version=?",
                    manifest.toString(), entry.getKey(), version);
            }
        }
        var service = runtime.modIntents();
        service.apply(service.review(P, choice(true, "1.1.0"), Optional.empty()), true);
        service.apply(service.review(FREE, choice(true, VERSION), Optional.empty()), true);
        assertEquals(2, service.pendingChanges().size());
    }

    private static PlatformFrame open(PlatformRuntime runtime, String language, ThemeMode theme, int scale,
                                      ModStoreService store) throws Exception {
        var intents = runtime.modIntents();
        var recovery = runtime.modRecovery();
        return onEdt(() -> {
            DesktopThemeService.apply(theme, scale);
            var messages = MessageCatalog.forLanguageTag(language);
            var frame = new PlatformFrame(runtime.security(), messages, theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
                new UiEnvironment(messages.locale(), theme.name(), false), FailureReportStore.disabled());
            try {
                frame.installMods(intents, new PlatformModsPane.SchemaActions() {
                    public PlatformModIntents.RetryReview review(String owner, Optional<SchemaIdentity> source) throws Exception {
                        return recovery.review(owner, source);
                    }

                    public TaskResult retry(PlatformModIntents.RetryReview review) throws Exception {
                        return recovery.retry(review);
                    }

                    public TaskResult reconcile(UUID operation) throws Exception {
                        return recovery.reconcile(operation);
                    }
                });
                if (store != null) frame.installStore(store);
                frame.setLocation(12, 12);
                frame.setSize(1616, 940);
                frame.setVisible(true);
                var outer = (JTabbedPane) named(frame, "platform.pages");
                var management = (JTabbedPane) named(frame, "mods.management");
                outer.setSelectedComponent(management);
                management.setSelectedComponent(named(frame, store == null ? "platform.mods" : "mods.store"));
                return frame;
            } catch (Exception | Error failure) {
                frame.dispose();
                throw failure;
            }
        });
    }

    private static void assertLayout(PlatformFrame frame, String prefix, boolean expanded) throws Exception {
        onEdt(() -> {
            layout(frame);
            var page = (JComponent) named(frame, prefix.equals("mods") ? "platform.mods" : "mods.store");
            var scroll = (JScrollPane) named(page, prefix + ".inventoryScroll");
            var search = (JTextField) named(page, prefix.equals("mods") ? "mods.search" : "store.search");
            var refresh = (JComponent) named(page, prefix.equals("mods") ? "mods.refresh" : "store.retry");
            var modes = (JComponent) named(page, "modui.modes");
            var detail = (JScrollPane) named(page, prefix + ".detailScroll");
            assertEquals(expanded, detail.isShowing());
            assertTrue(scroll.getViewport().getHeight() >= UIScale.scale(120), "Retained task feedback must leave a usable catalog viewport");
            if (expanded)
                assertTrue(detail.getViewport().getHeight() >= UIScale.scale(120), "Retained task feedback must leave a usable detail viewport");
            Rectangle refreshBounds = bounds(frame.getContentPane(), refresh);
            Rectangle modesBounds = bounds(frame.getContentPane(), modes);
            Rectangle barBounds = bounds(frame.getContentPane(), scroll.getVerticalScrollBar());
            assertEquals(refreshBounds.x + refreshBounds.width, barBounds.x + barBounds.width, UIScale.scale(2), "refresh/scroll right edge");
            assertEquals(refreshBounds.x + refreshBounds.width, modesBounds.x + modesBounds.width, UIScale.scale(2), "mode/refresh right edge");
            assertTrue(bounds(frame.getContentPane(), search).y < bounds(frame.getContentPane(), scroll).y);
            Point fixedSearch = search.getLocation();
            Point fixedRefresh = refresh.getLocation();
            int previousScroll = scroll.getVerticalScrollBar().getValue();
            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
            assertEquals(fixedSearch, search.getLocation());
            assertEquals(fixedRefresh, refresh.getLocation());
            scroll.getVerticalScrollBar().setValue(previousScroll);
            var list = (JList<?>) scroll.getViewport().getView();
            if (expanded) {
                assertTrue(list.getSelectedIndex() >= 0);
                assertTrue(list.getVisibleRect().contains(list.getCellBounds(list.getSelectedIndex(), list.getSelectedIndex())),
                    "Opening details must keep the selected owner fully visible after resize");
            }
            if (expanded) detail.getVerticalScrollBar().setValue(0);
            var status = bounds(frame.getContentPane(), named(frame, "platform.statusBar"));
            if (prefix.equals("store")) {
                assertFalse(named(page, "store.check").isVisible(), "No uncertain task exists in this catalog fixture");
                assertFalse(named(page, "store.cancel").isVisible(), "No acquisition is running in this catalog fixture");
            }
            var actions = prefix.equals("mods") ? List.of("mods.logs", "mods.more", "mods.viewChanges", "mods.save", "mods.saveRestart")
                : List.of("store.install");
            for (String name : actions) {
                var button = (JComponent) named(page, name);
                assertVisibleInside(frame, button);
                Rectangle buttonBounds = bounds(frame.getContentPane(), button);
                assertTrue(buttonBounds.y + buttonBounds.height <= status.y, name + " overlaps status bar");
            }
            return null;
        });
    }

    private static void assertVisibleInside(Window window, JComponent component) {
        assertTrue(component.isShowing(), component.getName());
        assertTrue(component.getWidth() > 0 && component.getHeight() > 0, component.getName());
        assertTrue(component.getVisibleRect().contains(new Rectangle(0, 0, component.getWidth(), component.getHeight())), component.getName() + " clipped by parent");
        Rectangle bounds = bounds(window, component);
        assertTrue(new Rectangle(0, 0, window.getWidth(), window.getHeight()).contains(bounds), component.getName() + " outside window");
    }

    private static Rectangle bounds(Component root, Component component) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
    }

    private static void size(PlatformFrame frame, Size size) throws Exception {
        onEdt(() -> {
            var insets = frame.getInsets();
            frame.setSize(size.width() + insets.left + insets.right, size.height() + insets.top + insets.bottom);
            layout(frame);
            for (int pass = 0; pass < 3; pass++) {
                int missingWidth = size.width() - frame.getContentPane().getWidth();
                int missingHeight = size.height() - frame.getContentPane().getHeight();
                if (missingWidth == 0 && missingHeight == 0) break;
                frame.setSize(frame.getWidth() + missingWidth, frame.getHeight() + missingHeight);
                layout(frame);
            }
            assertEquals(size.width(), frame.getContentPane().getWidth());
            assertEquals(size.height(), frame.getContentPane().getHeight());
            return null;
        });
    }

    private static void layout(Window frame) {
        for (int pass = 0; pass < 4; pass++) {
            descendants(frame).filter(Container.class::isInstance).map(Container.class::cast).forEach(Container::invalidate);
            frame.validate();
        }
    }

    private static void screenshot(Window frame, String name) throws Exception {
        onEdt(() -> {
            layout(frame);
            var bitmap = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = bitmap.createGraphics();
            try {
                frame.paint(graphics);
            } finally {
                graphics.dispose();
            }
            String configured = System.getProperty("wtl.mod.screenshots");
            Path directory = configured == null ? Path.of("target", "mod-workspace-rendering").toAbsolutePath() : Path.of(configured);
            assertTrue(directory.isAbsolute(), "wtl.mod.screenshots must be an absolute output directory");
            Files.createDirectories(directory);
            assertTrue(ImageIO.write(bitmap, "png", directory.resolve(name).toFile()));
            return null;
        });
    }

    private static JDialog pendingDialog(PlatformFrame frame) throws Exception {
        await(() -> Arrays.stream(frame.getOwnedWindows()).anyMatch(w -> "mods.changes".equals(w.getName()) && w.isShowing()));
        return onEdt(() -> (JDialog) Arrays.stream(frame.getOwnedWindows()).filter(w -> "mods.changes".equals(w.getName()) && w.isShowing()).findFirst().orElseThrow());
    }

    private static void close(PlatformFrame frame) throws Exception {
        if (frame != null) onEdt(() -> {
            for (var owned : frame.getOwnedWindows()) owned.dispose();
            frame.dispose();
            assertFalse(frame.isDisplayable());
            return null;
        });
        await(() -> !DesktopTaskRunner.hasActiveTasks());
    }

    private static String variant(ThemeMode theme, int scale, String language) {
        return theme.name().toLowerCase(Locale.ROOT) + "-" + scale + "-" + language;
    }

    private static List<Size> sizes() {
        return List.of(new Size("normal", 1600, 900), new Size("minimum", 1280, 720));
    }

    private record Size(String name, int width, int height) {
    }
}

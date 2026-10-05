package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.service.mod.store.ModStoreGateway;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreService;
import gold.debug.windowstolinux.app.service.mod.store.UnavailableModStoreGateway;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.service.mod.*;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModStorePane;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFeaturesWindowTest {
    @TempDir
    Path root;

    void idle() throws Exception {
        await(() -> !DesktopTaskRunner.hasActiveTasks());
    }

    AppConfig appOnly() {
        var value = AppConfig.defaultsConfig();
        return new AppConfig("data", value.ui(), value.defaults(), value.ssh(), value.ai(), 100, false, Optional.empty());
    }

    @Test
    void simulatedStoreRequiresConfirmationShowsProgressAndInstallsThroughOriginalOwner() throws Exception {
        var fixture = new PlatformModInstallationTest();
        fixture.root = root;
        fixture.compile();
        Path original = fixture.original("window-source.wtlmod", "1.0.0", "模拟商店");
        var inspection = gold.debug.windowstolinux.app.mod.install.ModPackageReader.read(original);
        var release = new ModStoreGateway.Release(inspection.manifest(), java.net.URI.create("packages/window.wtlmod"),
            Files.size(original), inspection.archiveSha256(), Map.of());
        var started = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var downloads = new java.util.concurrent.atomic.AtomicInteger();
        ModStoreGateway gateway = new ModStoreGateway() {
            public Catalog catalog(java.net.URI base, gold.debug.windowstolinux.appapi.task.run.CancelToken cancellation) {
                return new Catalog("a".repeat(64), List.of(release));
            }

            public DownloadResult download(java.net.URI base, Release value, Path destination, java.util.function.Consumer<Progress> observer,
                                           gold.debug.windowstolinux.appapi.task.run.CancelToken cancellation) throws Exception {
                downloads.incrementAndGet();
                Files.copy(original, destination);
                observer.accept(new Progress(50, 100));
                started.countDown();
                if (!finish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new java.io.IOException("Test progress fixture timed out");
                return new DownloadResult(destination);
            }

            public Verification verify(Release value, DownloadResult result) {
                return new Verification(true, "SIMULATED trust adapter");
            }
        };
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            var service = new ModStoreService(Optional.of(java.net.URI.create("https://mods.example.com")), gateway, Optional.of(installer), runtime.security(),
                runtime.persistence().database(), root, gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse("1.0.0-dev.1"));
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var host = new JFrame("Simulated store acceptance");
                var pane = new ModStorePane(service, new gold.debug.windowstolinux.app.ui.control.DesktopPartFactory(ThemePalette.light()),
                    new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"), FailureReportStore.disabled()), () -> {
                });
                host.add(pane);
                host.setSize(900, 600);
                host.setVisible(true);
                pane.setLocked(false);
                return host;
            });
            try {
                idle();
                var robot = new java.awt.Robot();
                robot.setAutoDelay(35);
                var list = (JList<?>) named(frame, "store.results");
                ModStoreWindowTest.clickArea(robot, list, onEdt(() -> list.getCellBounds(0, 0)));
                idle();
                onEdt(() -> {
                    assertTrue(((JTextArea) named(frame, "store.details")).getText().contains("sample.bundle"));
                    return null;
                });
                ModStoreWindowTest.click(robot, (JComponent) named(frame, "store.install"));
                await(() -> PlatformServersWindowTest.dialog("store.confirmation") != null);
                assertEquals(0, downloads.get());
                ModStoreWindowTest.click(robot, (JComponent) named(PlatformServersWindowTest.dialog("store.confirmation"), "store.confirm.accept"));
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                onEdt(() -> {
                    assertTrue(named(frame, "store.progress").isVisible());
                    capture(frame, "configured-store-progress.png");
                    return null;
                });
                finish.countDown();
                idle();
                assertEquals(1, downloads.get());
                assertEquals("FALSE/1.0.0", PlatformModIntentsTest.text(runtime, "SELECT enabled || '/' || selected_version FROM mod_registry"));
            } finally {
                finish.countDown();
                onEdt(() -> {
                    ((ModStorePane) named(frame, "mods.store")).close();
                    frame.dispose();
                    return null;
                });
                idle();
            }
        }
    }

    @Test
    void appearanceMovesAndResetsPlatformEntriesAfterDatabaseCommit() throws Exception {
        var layout = new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS, root, root.resolve("data"));
        try (var runtime = PlatformRuntime.open(layout, PlatformStartupTest.PROTECTION, appOnly())) {
            var password = "isolated-sort-window-password".toCharArray();
            runtime.security().setupAndUnlock(password, password.clone());
            var models = runtime.models();
            var frame = onEdt(() -> {
                var created = ApplicationLauncher.createWindow(runtime, MessageCatalog.forLanguageTag("zh-CN"),
                    new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT), ThemeMode.LIGHT, FailureReportStore.disabled(), models);
                created.setVisible(true);
                var tabs = (JTabbedPane) named(created, "platform.pages");
                tabs.setSelectedComponent(named(created, "platform.appearance"));
                return created;
            });
            try {
                idle();
                onEdt(() -> {
                    assertFalse(((JToggleButton) named(frame, "settings.advanced")).isSelected());
                    assertEquals("收起导航", ((JButton) named(frame, "navigation.collapse")).getToolTipText());
                    var list = (JList<?>) named(frame, "navigation.platform.list");
                    assertEquals(3, list.getModel().getSize());
                    list.setSelectedIndex(1);
                    ((JButton) named(frame, "navigation.platform.up")).doClick(0);
                    return null;
                });
                idle();
                assertTrue(runtime.persistence().preferences().find("platform_navigation_order").orElseThrow().contains("platform.models"));
                onEdt(() -> {
                    var list = (JList<?>) named(frame, "navigation.platform.list");
                    assertEquals("platform.models", list.getModel().getElementAt(0));
                    capture(frame, "configured-navigation-order.png");
                    ((JButton) named(frame, "navigation.platform.reset")).doClick(0);
                    return null;
                });
                idle();
                assertEquals(List.of("platform.servers", "platform.models", "tasks.title"), runtime.persistence().navigation().read(List.of()));
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
                idle();
            }
        }
    }

    @Test
    void unavailableStoreKeepsKeywordAcrossRetryAndShowsTruthfulState() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var password = "isolated-store-window-password".toCharArray();
            runtime.security().setupAndUnlock(password, password.clone());
            var service = new ModStoreService(Optional.empty(), new UnavailableModStoreGateway(), Optional.empty(), runtime.security(),
                runtime.persistence().database(), root, gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse("1.0.0-dev.1"));
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var host = new JFrame("Store acceptance");
                var pane = new ModStorePane(service, new gold.debug.windowstolinux.app.ui.control.DesktopPartFactory(ThemePalette.light()),
                    new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"), FailureReportStore.disabled()), () -> {
                });
                host.add(pane);
                host.setSize(900, 600);
                host.setVisible(true);
                pane.setLocked(false);
                return host;
            });
            try {
                idle();
                onEdt(() -> {
                    ((JTextField) named(frame, "store.search")).setText("保留搜索词");
                    return null;
                });
                ModStoreWindowTest.click(new java.awt.Robot(), (JComponent) named(frame, "store.retry"));
                idle();
                onEdt(() -> {
                    assertEquals("保留搜索词", ((JTextField) named(frame, "store.search")).getText());
                    assertTrue(descendants(frame).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .anyMatch(label -> "网络未连接，请点击重试".equals(label.getText())));
                    assertFalse(((JButton) named(frame, "store.install")).isEnabled());
                    assertEquals(0, ((JList<?>) named(frame, "store.results")).getModel().getSize());
                    capture(frame, "configured-store-unavailable.png");
                    return null;
                });
            } finally {
                onEdt(() -> {
                    ((ModStorePane) named(frame, "mods.store")).close();
                    frame.dispose();
                    return null;
                });
                idle();
            }
        }
    }
}

package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production startup assembly with isolated storage. / 使用隔离存储检查生产启动装配。 */
class PlatformRestyledWindowTest {
    @TempDir
    Path root;

    @Test
    void actualStartupPagesKeepDraftsAcrossNavigationAndFitBothWindowSizes() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            for (String language : new String[]{"zh-CN", "en"}) {
                String variant = theme + "-" + language + "-" + System.getProperty("flatlaf.uiScale", "1.0");
                try (var runtime = PlatformRuntime.open(root.resolve(variant), PlatformStartupTest.PROTECTION)) {
                    var models = runtime.models();
                    var create = DesktopMain.class.getDeclaredMethod("createWindow", PlatformRuntime.class,
                        MessageCatalog.class, DesktopDisplayConfiguration.class, ThemeMode.class,
                        FailureReportStore.class, PlatformModelService.class);
                    create.setAccessible(true);
                    var window = onEdt(() -> (PlatformFrame) create.invoke(null, runtime,
                        MessageCatalog.forLanguageTag(language), new DesktopDisplayConfiguration(language, theme),
                        theme, FailureReportStore.disabled(), models));
                    runtime.started();
                    try {
                        onEdt(() -> {
                            window.setVisible(true);
                            return null;
                        });
                        captureAt(window, 1120, 760, "restyled-" + variant + "-first-use.png");
                        captureAt(window, 800, 560, "restyled-" + variant + "-first-use-minimum.png");
                        setupFailure(window, variant);
                        assertFalse(runtime.security().securityState().configured());
                        onEdt(() -> {
                            ((JPasswordField) named(window, "security.settings.password"))
                                .setText("visual-fixture-password");
                            ((JPasswordField) named(window, "security.settings.confirmation"))
                                .setText("visual-fixture-password");
                            ((JButton) named(window, "security.settings.submit")).doClick(0);
                            return null;
                        });
                        await(() -> runtime.security().securityState().unlocked()
                            && !runtime.security().securityState().changing());
                        DesktopMain.installModManagement(runtime, window);
                        await(() -> descendants(window).anyMatch(c -> "mods.management".equals(c.getName()))
                            && named(window, "servers.refresh").isEnabled());
                        onEdt(() -> {
                            var search = (JTextField) named(window, "servers.search");
                            search.setText("draft-server-草稿");
                            return null;
                        });
                        for (String page : new String[]{"platform.servers", "platform.models", "mods.management",
                            "platform.tasks", "platform.appearance", "platform.security", "platform.authorization",
                            "platform.transfers", "platform.logs"}) {
                            onEdt(() -> {
                                var content = (JTabbedPane) named(window, "platform.pages");
                                content.setSelectedComponent(named(window, page));
                                assertTrue(named(window, page).isShowing(), page);
                                return null;
                            });
                            captureAt(window, 1120, 760, "restyled-" + variant + "-" + page + ".png");
                            captureAt(window, 800, 560, "restyled-" + variant + "-" + page + "-minimum.png");
                        }
                        onEdt(() -> {
                            var search = (JTextField) named(window, "servers.search");
                            ((JButton) named(window, "navigation.platform.servers")).doClick(0);
                            assertEquals("draft-server-草稿", search.getText());
                            assertSame(search, named(window, "servers.search"));
                            var settings = named(window, "navigation.settings");
                            var sidebar = named(window, "platform.sidebar");
                            assertTrue(
                                SwingUtilities.convertPoint(settings, 0, 0, sidebar).y > sidebar.getHeight() / 2);
                            ((JButton) named(window, "navigation.collapse")).doClick(0);
                            return null;
                        });
                        await(() -> named(window, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale
                            .scale(64));
                        onEdt(() -> {
                            var nav = (JButton) named(window, "navigation.platform.servers");
                            assertEquals("", nav.getText());
                            assertNotNull(nav.getIcon());
                            assertFalse(nav.getAccessibleContext().getAccessibleName().isBlank());
                            window.setSize(800, 560);
                            window.validate();
                            assertTrue(named(window, "servers.search").getWidth() > 120);
                            capture(window, "restyled-" + variant + "-minimum-collapsed.png");
                            ((JButton) named(window, "navigation.settings")).doClick(0);
                            capture(window, "restyled-" + variant + "-minimum-settings.png");
                            return null;
                        });
                        runningTask(window, runtime, variant);
                    } finally {
                        await(() -> !DesktopTaskExecutor.hasActiveTasks());
                        onEdt(() -> {
                            window.dispose();
                            return null;
                        });
                    }
                }
            }
        }
    }

    private static void runningTask(PlatformFrame window, PlatformRuntime runtime, String variant) throws Exception {
        PlatformTaskAdmissionIntegrationTest.register(runtime.persistence().database(), null);
        var release = new java.util.concurrent.CountDownLatch(1);
        var binding = runtime.tasks().bind(PlatformTaskAdmissionIntegrationTest.OWNER,
            PlatformTaskAdmissionIntegrationTest.VERSION, false);
        var task = binding.submit(PlatformTaskAdmissionIntegrationTest.request(), context -> {
            context.progress("Local visual acceptance / 本机界面验收", java.util.Optional.of(0.4));
            assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS));
            return PlatformTaskAdmissionIntegrationTest.success(null);
        });
        try {
            PlatformTaskAdmissionIntegrationTest.state(task,
                gold.debug.windowstolinux.appapi.task.TaskState.RUNNING);
            await(() -> named(window, "tasks.reload").isEnabled());
            onEdt(() -> {
                ((JButton) named(window, "navigation.collapse")).doClick(0);
                ((JTabbedPane) named(window, "platform.pages"))
                    .setSelectedComponent(named(window, "platform.tasks"));
                ((JButton) named(window, "tasks.reload")).doClick(0);
                return null;
            });
            await(() -> named(window, "tasks.reload").isEnabled()
                && ((JTable) named(window, "tasks.table")).getRowCount() == 1);
            onEdt(() -> {
                ((JTable) named(window, "tasks.table")).setRowSelectionInterval(0, 0);
                return null;
            });
            captureAt(window, 1120, 760, "restyled-" + variant + "-task-running.png");
            captureAt(window, 800, 560, "restyled-" + variant + "-task-running-minimum.png");
            assertEquals(gold.debug.windowstolinux.appapi.task.TaskState.RUNNING, task.snapshot().state());
        } finally {
            release.countDown();
            assertEquals(gold.debug.windowstolinux.appapi.task.Outcome.SUCCEEDED,
                task.completion().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).outcome());
        }
    }

    private static void captureAt(PlatformFrame window, int width, int height, String name) throws Exception {
        onEdt(() -> {
            window.setSize(width, height);
            window.validate();
            return null;
        });
        onEdt(() -> {
            capture(window, name);
            if (width == 800) {
                var page = ((JTabbedPane) named(window, "platform.pages")).getSelectedComponent();
                var scroll = descendants(page).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast)
                    .filter(value -> value.isShowing() && value.getVerticalScrollBar().isVisible()).findFirst();
                if (scroll.isPresent()) {
                    var bar = scroll.orElseThrow().getVerticalScrollBar();
                    bar.setValue(bar.getMaximum());
                    capture(window, name.replace(".png", "-bottom.png"));
                    bar.setValue(0);
                }
            }
            return null;
        });
    }

    private static void setupFailure(PlatformFrame window, String variant) throws Exception {
        onEdt(() -> {
            ((JPasswordField) named(window, "security.settings.password")).setText("short");
            ((JPasswordField) named(window, "security.settings.confirmation")).setText("short");
            ((JButton) named(window, "security.settings.submit")).doClick(0);
            return null;
        });
        await(() -> java.util.Arrays.stream(window.getOwnedWindows())
            .anyMatch(value -> value instanceof JDialog && value.isShowing()));
        onEdt(() -> {
            var dialog = java.util.Arrays.stream(window.getOwnedWindows())
                .filter(value -> value instanceof JDialog && value.isShowing()).findFirst().orElseThrow();
            String confirmation = variant.contains("-en-") ? "OK" : "确定";
            assertTrue(descendants(dialog).anyMatch(value -> value instanceof JButton button
                && confirmation.equals(button.getText())));
            capture(dialog, "restyled-" + variant + "-setup-error.png");
            dialog.dispose();
            return null;
        });
    }
}

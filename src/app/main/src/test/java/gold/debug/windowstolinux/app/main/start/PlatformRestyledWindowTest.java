package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production startup assembly with isolated storage. / 使用隔离存储检查生产启动装配。 */
class PlatformRestyledWindowTest {
    @TempDir
    Path root;

    @Test
    void appearanceZoomIsSavedAppliedLiveAndRestoredWithBilingualChoices() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                Path storage = root.resolve("zoom-" + theme);
                try (var runtime = PlatformRuntime.open(storage, PlatformStartupTest.PROTECTION)) {
                    runtime.security().setupAndUnlock("zoom-fixture".toCharArray(), "zoom-fixture".toCharArray());
                    var models = runtime.models();
                    var window = onEdt(() -> ApplicationLauncher.createWindow(runtime,
                        MessageCatalog.forLanguageTag("zh-CN"), new DesktopDisplayConfig("zh-CN", theme, 100),
                        theme, FailureReportStore.disabled(), models));
                    runtime.started();
                    try {
                        onEdt(() -> {
                            window.setSize(1120, 900);
                            window.setVisible(true);
                            ((JTabbedPane) named(window, "platform.pages"))
                                .setSelectedComponent(named(window, "platform.appearance"));
                            ((JTextField) named(window, "servers.search")).setText("scale-draft");
                            assertLanguageChoices(window, "简体中文(简体中文)", "English(英语)");
                            return null;
                        });
                        await(() -> !DesktopTaskRunner.hasActiveTasks() && named(window, "navigation.platform.list").isEnabled());
                        var originalPage = onEdt(() -> named(window, "platform.appearance"));
                        var originalDraft = onEdt(() -> named(window, "servers.search"));
                        for (int scale : new int[]{150, 200, 100}) {
                            String language = scale == 200 ? "en" : "zh-CN";
                            onEdt(() -> {
                                ((JComboBox<?>) named(window, "appearance.language")).setSelectedItem(language);
                                ((JComboBox<?>) named(window, "appearance.scale")).setSelectedItem(scale);
                                ((JButton) named(window, "appearance.save")).doClick(0);
                                return null;
                            });
                            await(() -> !DesktopTaskRunner.hasActiveTasks() && named(window, "appearance.save").isEnabled());
                            assertEquals(Integer.toString(scale), runtime.persistence().preferences().find("ui_scale").orElseThrow());
                            assertEquals(language, runtime.persistence().preferences().find("ui_locale").orElseThrow());
                            onEdt(() -> {
                                window.validate();
                                assertEquals(scale / 100f, com.formdev.flatlaf.util.UIScale.getZoomFactor());
                                assertEquals(Math.round(13 * scale / 100f), named(window, "appearance.theme").getFont().getSize());
                                assertEquals(com.formdev.flatlaf.util.UIScale.scale(168), named(window, "platform.sidebar").getWidth());
                                assertEquals(com.formdev.flatlaf.util.UIScale.scale(48),
                                    ((JList<?>) named(window, "navigation.platform.list")).getFixedCellHeight());
                                assertEquals(com.formdev.flatlaf.util.UIScale.scale(36),
                                    ((JButton) named(window, "navigation.logo")).getIcon().getIconWidth());
                                assertSame(originalPage, named(window, "platform.appearance"));
                                assertSame(originalDraft, named(window, "servers.search"));
                                assertEquals("scale-draft", ((JTextField) originalDraft).getText());
                                assertLanguageChoices(window, language.equals("en") ? "简体中文(Simplified Chinese)" : "简体中文(简体中文)",
                                    language.equals("en") ? "English(English)" : "English(英语)");
                                ((JComboBox<?>) named(window, "appearance.scale")).showPopup();
                                var popup = (javax.swing.plaf.basic.BasicComboPopup) ((JComboBox<?>) named(window, "appearance.scale"))
                                    .getUI().getAccessibleChild((JComponent) named(window, "appearance.scale"), 0);
                                assertEquals(3, popup.getList().getModel().getSize());
                                ((JComboBox<?>) named(window, "appearance.scale")).hidePopup();
                                var brand = (JButton) named(window, "navigation.logo");
                                assertEquals("WindowsToLinux", brand.getText());
                                capture(window, "appearance-zoom-" + theme + "-" + scale + ".png");
                                assertTrue(brand.getFontMetrics(brand.getFont()).stringWidth(brand.getText())
                                        + brand.getIcon().getIconWidth() + brand.getIconTextGap() <= brand.getWidth(),
                                    "Full brand must fit beside the logo: width=" + brand.getWidth() + ", required="
                                        + (brand.getFontMetrics(brand.getFont()).stringWidth(brand.getText()) + brand.getIcon().getIconWidth() + brand.getIconTextGap()));
                                return null;
                            });
                            onEdt(() -> {
                                ((JButton) named(window, "navigation.collapse")).doClick(0);
                                return null;
                            });
                            await(() -> named(window, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale.scale(64));
                            onEdt(() -> {
                                assertEquals("", ((JButton) named(window, "navigation.logo")).getText());
                                assertNotNull(((JButton) named(window, "navigation.logo")).getIcon());
                                capture(window, "appearance-zoom-" + theme + "-" + scale + "-collapsed.png");
                                ((JButton) named(window, "navigation.collapse")).doClick(0);
                                return null;
                            });
                            await(() -> named(window, "platform.sidebar").getWidth() == com.formdev.flatlaf.util.UIScale.scale(168));
                            if (scale == 200)
                                captureAt(window, 800, 560, "appearance-zoom-" + theme + "-200-minimum.png");
                            onEdt(() -> {
                                window.setSize(1120, 900);
                                return null;
                            });
                        }
                        onEdt(() -> {
                            ((JComboBox<?>) named(window, "appearance.scale")).setSelectedItem(200);
                            ((JButton) named(window, "appearance.save")).doClick(0);
                            return null;
                        });
                        await(() -> !DesktopTaskRunner.hasActiveTasks() && named(window, "appearance.save").isEnabled());
                    } finally {
                        onEdt(() -> {
                            window.dispose();
                            return null;
                        });
                        await(() -> !DesktopTaskRunner.hasActiveTasks());
                    }
                }
                try (var reopened = PlatformRuntime.open(storage, PlatformStartupTest.PROTECTION)) {
                    var saved = DesktopDisplayConfig.fromStoredValues(reopened.persistence().preferences().find("ui_locale").orElse(null),
                        reopened.persistence().preferences().find("ui_theme").orElse(null),
                        reopened.persistence().preferences().find("ui_scale").orElse(null), java.util.Locale.ENGLISH);
                    assertEquals(200, saved.scalePercent());
                    var models = reopened.models();
                    var restored = onEdt(() -> ApplicationLauncher.createWindow(reopened, MessageCatalog.forLanguageTag(saved.localeTag()),
                        saved, theme, FailureReportStore.disabled(), models));
                    try {
                        onEdt(() -> {
                            assertEquals(200, ((JComboBox<?>) named(restored, "appearance.scale")).getSelectedItem());
                            assertEquals(2f, com.formdev.flatlaf.util.UIScale.getZoomFactor());
                            assertEquals(26, named(restored, "appearance.theme").getFont().getSize());
                            assertFalse(named(restored, "appearance.scale").isEnabled(), "Locked startup cannot edit scale");
                            return null;
                        });
                    } finally {
                        onEdt(() -> {
                            restored.dispose();
                            return null;
                        });
                        await(() -> !DesktopTaskRunner.hasActiveTasks());
                    }
                }
            }
        } finally {
            onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    private static void assertLanguageChoices(PlatformFrame window, String chinese, String english) {
        @SuppressWarnings("unchecked")
        var choices = (JComboBox<String>) named(window, "appearance.language");
        var list = new JList<String>();
        for (int index = 0; index < 2; index++) {
            var label = (JLabel) choices.getRenderer().getListCellRendererComponent(list, choices.getItemAt(index), index, false, false);
            assertEquals(index == 0 ? chinese : english, label.getText());
        }
    }

    @Test
    void appearanceOrderCardsFollowTheViewportInBothThemesAndLanguages() throws Exception {
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
            for (String language : new String[]{"zh-CN", "en"}) {
                String variant = theme + "-" + language + "-" + System.getProperty("flatlaf.uiScale", "1.0");
                try (var runtime = PlatformRuntime.open(root.resolve(variant), PlatformStartupTest.PROTECTION)) {
                    runtime.security().setupAndUnlock("appearance-fixture".toCharArray(), "appearance-fixture".toCharArray());
                    var models = runtime.models();
                    var create = ApplicationLauncher.class.getDeclaredMethod("createWindow", PlatformRuntime.class,
                        MessageCatalog.class, DesktopDisplayConfig.class, ThemeMode.class,
                        FailureReportStore.class, PlatformModelService.class);
                    create.setAccessible(true);
                    var window = onEdt(() -> (PlatformFrame) create.invoke(null, runtime,
                        MessageCatalog.forLanguageTag(language), new DesktopDisplayConfig(language, theme),
                        theme, FailureReportStore.disabled(), models));
                    runtime.started();
                    try {
                        onEdt(() -> {
                            window.setVisible(true);
                            ((JTabbedPane) named(window, "platform.pages"))
                                .setSelectedComponent(named(window, "platform.appearance"));
                            return null;
                        });
                        await(() -> named(window, "navigation.platform.list").isEnabled()
                            && !DesktopTaskRunner.hasActiveTasks());
                        captureAt(window, 1120, 900, "navigation-pilot-" + variant + ".png");
                        captureAt(window, 800, 560, "navigation-pilot-" + variant + "-minimum.png");
                        onEdt(() -> {
                            var appearance = named(window, "platform.appearance");
                            descendants(appearance).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast)
                                .filter(Component::isShowing).forEach(scroll -> {
                                    assertFalse(scroll.getHorizontalScrollBar().isVisible(), "Cards must fit the viewport width");
                                    assertTrue(scroll.getViewport().getView().getWidth() <= scroll.getViewport().getWidth());
                                });
                            return null;
                        });
                    } finally {
                        onEdt(() -> {
                            window.dispose();
                            return null;
                        });
                        await(() -> !DesktopTaskRunner.hasActiveTasks());
                    }
                }
            }
    }

    @Test
    void actualStartupPagesKeepDraftsAcrossNavigationAndFitBothWindowSizes() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            for (String language : new String[]{"zh-CN", "en"}) {
                String variant = theme + "-" + language + "-" + System.getProperty("flatlaf.uiScale", "1.0");
                try (var runtime = PlatformRuntime.open(root.resolve(variant), PlatformStartupTest.PROTECTION)) {
                    var models = runtime.models();
                    var create = ApplicationLauncher.class.getDeclaredMethod("createWindow", PlatformRuntime.class,
                        MessageCatalog.class, DesktopDisplayConfig.class, ThemeMode.class,
                        FailureReportStore.class, PlatformModelService.class);
                    create.setAccessible(true);
                    var window = onEdt(() -> (PlatformFrame) create.invoke(null, runtime,
                        MessageCatalog.forLanguageTag(language), new DesktopDisplayConfig(language, theme),
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
                        ApplicationLauncher.installModManagement(runtime, window);
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
                        await(() -> !DesktopTaskRunner.hasActiveTasks());
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
                gold.debug.windowstolinux.appapi.task.model.TaskState.RUNNING);
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
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.RUNNING, task.snapshot().state());
        } finally {
            release.countDown();
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,
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

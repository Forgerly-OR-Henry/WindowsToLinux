package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.config.*;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredRuntimeTest {
    @TempDir
    Path root;

    AppConfig config() {
        var base = AppConfig.defaultsConfig();
        return new AppConfig("data", new AppConfig.Ui(true, true), new AppConfig.Defaults("zh-CN", "DARK", 45, 600, false),
            base.ssh(), base.ai(), 3, false, Optional.empty());
    }

    RunModeResolver.RuntimeLayout layout() {
        return new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS, root, root.resolve("data"));
    }

    public static final class RejectModRecovery implements org.h2.api.Trigger {
        public void fire(java.sql.Connection connection, Object[] before, Object[] after) throws java.sql.SQLException {
            throw new java.sql.SQLException("Disabled Mod boot must not run dedicated recovery updates");
        }
    }

    @Test
    void releaseModesRejectDisabledModCompositionBeforeCreatingData() {
        for (var mode : List.of(RunModeResolver.RunMode.RUN_JAR, RunModeResolver.RunMode.RUN_APP)) {
            var data = root.resolve(mode.name());
            var release = new RunModeResolver.RuntimeLayout(mode, root, data);
            assertThrows(IllegalArgumentException.class, () -> PlatformRuntime.open(release, PlatformStartupTest.PROTECTION, config()));
            assertFalse(Files.exists(data));
        }
    }

    @Test
    void disabledModeDefersDedicatedDatabaseRecoveryQueriesUntilEnabledBoot() throws Exception {
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            PlatformTaskAdmissionIntegrationTest.sql(runtime.persistence().database(),
                "CREATE TRIGGER config_no_mod_recovery BEFORE UPDATE ON mod_operation CALL '" + RejectModRecovery.class.getName() + "'");
        }
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            assertFalse(runtime.startup().config().modLoad());
        }
        assertThrows(Exception.class, () -> PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, AppConfig.defaultsConfig()));
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            PlatformTaskAdmissionIntegrationTest.sql(runtime.persistence().database(), "DROP TRIGGER config_no_mod_recovery");
        }
    }

    @Test
    void disabledModSkipsDamagedSourcesCatalogPreferencesServicesAndClosesNormally() throws Exception {
        Files.createDirectories(root.resolve("src/mod/broken/target"));
        Files.writeString(root.resolve("src/mod/broken/mod.json"), "broken manifest");
        Files.writeString(root.resolve("src/mod/broken/target/dev-info.json"), "broken build");
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            runtime.security().setupAndUnlock("isolated-config-password".toCharArray(), "isolated-config-password".toCharArray());
            runtime.persistence().preferences().save("mod_navigation_pins", "corrupt unused pins");
            runtime.started();
            assertEquals(45, runtime.transfers().read().connectSeconds());
            assertFalse(runtime.fileSettings().enabled());
            var before = Files.readString(root.resolve("src/mod/broken/mod.json"));
            var report = ApplicationLauncher.startMods(runtime, layout(), null, null);
            assertTrue(report.running().isEmpty());
            assertTrue(report.problems().isEmpty());
            assertTrue(runtime.modContexts().isEmpty());
            assertTrue(runtime.modSessions().isEmpty());
            assertThrows(IllegalStateException.class, runtime::services);
            assertThrows(IllegalStateException.class, runtime::modIntents);
            assertEquals(before, Files.readString(root.resolve("src/mod/broken/mod.json")));
            assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
        }
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            assertEquals("corrupt unused pins", runtime.persistence().preferences().find("mod_navigation_pins").orElseThrow());
        }
    }

    @Test
    void userSettingsAndProtectedDatabaseSurviveChangedYamlDefaults() throws Exception {
        UUID generation;
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            generation = runtime.persistence().database().generation();
            runtime.security().setupAndUnlock("isolated-config-password".toCharArray(), "isolated-config-password".toCharArray());
            runtime.persistence().preferences().saveAll(Map.of("ui_locale", "en", "ui_theme", "LIGHT", "transfer_connect_seconds", "65", "file_delete_ai_explanation", "true"));
        }
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            runtime.security().unlock("isolated-config-password".toCharArray());
            assertEquals(generation, runtime.persistence().database().generation());
            assertEquals("en", runtime.persistence().preferences().find("ui_locale").orElseThrow());
            assertEquals("LIGHT", runtime.persistence().preferences().find("ui_theme").orElseThrow());
            assertEquals(65, runtime.transfers().read().connectSeconds());
            assertTrue(runtime.fileSettings().enabled());
        }
    }

    @Test
    void bothUiInitialStatesApplyWithoutMountingAnyModSurfaces() throws Exception {
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            var window = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"), ThemePalette.light(),
                    new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled(), config());
                frame.installUiDebug(true);
                frame.setVisible(true);
                return frame;
            });
            try {
                onEdt(() -> {
                    var tabs = (JTabbedPane) named(window, "platform.pages");
                    assertEquals(2, tabs.getTabCount());
                    assertEquals(List.of(MessageCatalog.forLanguageTag("zh-CN").text("shell.home"),
                            MessageCatalog.forLanguageTag("zh-CN").text("security.settings")),
                        java.util.stream.IntStream.range(0, tabs.getTabCount()).mapToObj(tabs::getTitleAt).toList());
                    assertEquals("展开导航", ((JButton) named(window, "navigation.collapse")).getToolTipText());
                    tabs.setSelectedIndex(tabs.indexOfTab(MessageCatalog.forLanguageTag("zh-CN").text("security.settings")));
                    assertTrue(((JToggleButton) named(window, "settings.advanced")).isSelected());
                    assertTrue(named(window, "settings.uiDebug").isShowing());
                    assertTrue(descendants(window).noneMatch(c -> "mods.store".equals(c.getName()) || "mods.management".equals(c.getName()) || "mods.navigation".equals(c.getName())));
                    capture(window, "configured-app-only.png");
                    return null;
                });
            } finally {
                onEdt(() -> {
                    window.dispose();
                    return null;
                });
            }
        }
    }
}

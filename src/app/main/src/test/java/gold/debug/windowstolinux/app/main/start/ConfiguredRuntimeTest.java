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

    @Test
    void releaseModesRejectDisabledAutomaticLoadingBeforeCreatingData() {
        for (var mode : List.of(RunModeResolver.RunMode.RUN_JAR, RunModeResolver.RunMode.RUN_APP)) {
            var data = root.resolve(mode.name());
            var release = new RunModeResolver.RuntimeLayout(mode, root, data);
            assertThrows(IllegalArgumentException.class, () -> PlatformRuntime.open(release, PlatformStartupTest.PROTECTION, config()));
            assertFalse(Files.exists(data));
        }
    }

    @Test
    void disabledAutomaticLoadingKeepsRegisteredChoicesAndHostLedgerRecovery() throws Exception {
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            PlatformModIntentsTest.add(runtime, "example.saved", false, Map.of(), null);
            PlatformTaskAdmissionIntegrationTest.sql(runtime.persistence().database(),
                "INSERT INTO mod_operation(operation_id,mod_id,operation_type,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,'example.saved','INSTALL','RUNNING','PREPARING','{}','{}',?,1,1)",
                UUID.randomUUID(), UUID.randomUUID().toString());
        }
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            assertFalse(runtime.startup().config().modLoad());
            runtime.security().setupAndUnlock("isolated-config-password".toCharArray(), "isolated-config-password".toCharArray());
            runtime.started();
            var saved = runtime.modIntents().list().getFirst();
            assertEquals("example.saved", saved.owner());
            assertFalse(saved.choice().enabled());
            assertEquals(Optional.of("1.0.0"), saved.choice().version());
            assertEquals("UNKNOWN", saved.operations().getFirst().state());
            assertTrue(runtime.modContexts().isEmpty());
            assertTrue(runtime.modSessions().isEmpty());
        }
    }

    @Test
    void disabledAutomaticLoadingSkipsDamagedSourcesButKeepsManagementServices() throws Exception {
        Files.createDirectories(root.resolve("src/mod/broken/target"));
        Files.writeString(root.resolve("src/mod/broken/mod.json"), "broken manifest");
        Files.writeString(root.resolve("src/mod/broken/target/dev-info.json"), "broken build");
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            runtime.security().setupAndUnlock("isolated-config-password".toCharArray(), "isolated-config-password".toCharArray());
            runtime.persistence().preferences().save("mod_navigation_pins", "[]");
            runtime.started();
            assertEquals(45, runtime.transfers().read().connectSeconds());
            assertFalse(runtime.fileSettings().enabled());
            var before = Files.readString(root.resolve("src/mod/broken/mod.json"));
            var report = ApplicationLauncher.startMods(runtime, layout(), null);
            assertTrue(report.running().isEmpty());
            assertTrue(report.problems().isEmpty());
            assertTrue(runtime.modContexts().isEmpty());
            assertTrue(runtime.modSessions().isEmpty());
            assertNotNull(runtime.services());
            assertTrue(runtime.modIntents().list().isEmpty());
            assertThrows(IllegalStateException.class, runtime::modInstallation);
            assertEquals(before, Files.readString(root.resolve("src/mod/broken/mod.json")));
            assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
        }
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            assertEquals("[]", runtime.persistence().preferences().find("mod_navigation_pins").orElseThrow());
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
    void bothUiInitialStatesApplyWithModSurfacesWhenAutomaticLoadingIsDisabled() throws Exception {
        try (var runtime = PlatformRuntime.open(layout(), PlatformStartupTest.PROTECTION, config())) {
            runtime.security().setupAndUnlock("isolated-config-password".toCharArray(), "isolated-config-password".toCharArray());
            runtime.started();
            var window = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"), ThemePalette.light(),
                    new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled(), config());
                frame.installUiDebug(true);
                frame.setVisible(true);
                return frame;
            });
            try {
                var report = ApplicationLauncher.startMods(runtime, layout(), window.pages());
                ApplicationLauncher.installModManagement(runtime, window);
                onEdt(() -> {
                    window.showModStartup(report.running().size(), report.problems());
                    var tabs = (JTabbedPane) named(window, "platform.pages");
                    assertEquals(5, tabs.getTabCount());
                    assertEquals(List.of(MessageCatalog.forLanguageTag("zh-CN").text("shell.home"),
                            MessageCatalog.forLanguageTag("zh-CN").text("platform.pages"),
                            MessageCatalog.forLanguageTag("zh-CN").text("security.settings"),
                            MessageCatalog.forLanguageTag("zh-CN").text("platform.modSettings"), "Mod"),
                        java.util.stream.IntStream.range(0, tabs.getTabCount()).mapToObj(tabs::getTitleAt).toList());
                    assertEquals("展开导航", ((JButton) named(window, "navigation.collapse")).getToolTipText());
                    tabs.setSelectedIndex(tabs.indexOfTab(MessageCatalog.forLanguageTag("zh-CN").text("security.settings")));
                    assertTrue(((JToggleButton) named(window, "settings.advanced")).isSelected());
                    assertTrue(named(window, "settings.uiDebug").isShowing());
                    assertNotNull(named(window, "mods.store"));
                    assertNotNull(named(window, "mods.navigation"));
                    var management = (JTabbedPane) named(window, "mods.management");
                    assertEquals(3, management.getTabCount());
                    assertNotNull(named(window, "originals.classMode"));
                    ((JButton) named(window, "navigation.shell.mods")).doClick(0);
                    assertEquals("shell.mods", window.currentPage().platformId());
                    assertTrue(management.isShowing());
                    capture(window, "configured-manual-mod.png");
                    return null;
                });
                assertTrue(report.running().isEmpty());
                assertTrue(report.problems().isEmpty());
                assertTrue(runtime.modSessions().isEmpty());
            } finally {
                onEdt(() -> {
                    window.dispose();
                    return null;
                });
            }
        }
    }
}

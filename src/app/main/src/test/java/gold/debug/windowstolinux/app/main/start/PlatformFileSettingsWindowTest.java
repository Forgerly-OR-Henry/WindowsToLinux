package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Locale;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.setting.FileSettingsService;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformFileSettingsWindowTest {
    @TempDir
    Path root;

    PlatformFrame window(PlatformRuntime runtime) throws Exception {
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"), ThemePalette.light(),
                new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
            frame.installFileSettings(runtime.fileSettings());
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.fileSettings"));
            frame.setVisible(true);
            return frame;
        });
    }

    @Test
    void persistsConfirmedToggleAcrossReopenAndRollsBackFailedWriteWithPrivacyGates() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            assertTrue(runtime.fileSettings().enabled());
            var frame = window(runtime);
            try {
                await(() -> named(frame, "fileSettings.enabled").isEnabled());
                onEdt(() -> {
                    assertTrue(((JCheckBox) named(frame, "fileSettings.enabled")).isSelected());
                    ((JCheckBox) named(frame, "fileSettings.enabled")).doClick(0);
                    return null;
                });
                await(() -> ((JLabel) named(frame, "fileSettings.status")).getText().equals("设置已保存。"));
                assertFalse(runtime.fileSettings().enabled());
                sql(runtime.persistence().database(), "ALTER TABLE desktop_preference ADD CONSTRAINT fixture_file_setting CHECK(preference_key<>'file_delete_ai_explanation' OR preference_value<>'true')");
                try {
                    onEdt(() -> {
                        ((JCheckBox) named(frame, "fileSettings.enabled")).doClick(0);
                        return null;
                    });
                    await(() -> ((JLabel) named(frame, "fileSettings.status")).getText().startsWith("设置未能确认"));
                    onEdt(() -> {
                        assertFalse(((JCheckBox) named(frame, "fileSettings.enabled")).isSelected());
                        capture(frame, "platform-file-settings.png");
                        return null;
                    });
                    assertFalse(runtime.fileSettings().enabled());
                } finally {
                    sql(runtime.persistence().database(), "ALTER TABLE desktop_preference DROP CONSTRAINT fixture_file_setting");
                }
                runtime.security().lock();
                await(() -> !named(frame, "fileSettings.enabled").isEnabled());
                runtime.security().unlock(PASSWORD.toCharArray());
                await(() -> named(frame, "fileSettings.enabled").isEnabled());
                await(() -> !DesktopTaskRunner.hasActiveTasks());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            assertFalse(runtime.fileSettings().enabled());
        }
    }

    @Test
    void invalidValueIsReportedWithoutSilentPreferenceRewrite() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            runtime.persistence().preferences().save(FileSettingsService.KEY, "invalid");
            var frame = window(runtime);
            try {
                await(() -> ((JLabel) named(frame, "fileSettings.status")).getText().startsWith("设置未能确认"));
                onEdt(() -> {
                    assertFalse(named(frame, "fileSettings.enabled").isEnabled());
                    assertTrue(named(frame, "fileSettings.reload").isEnabled());
                    return null;
                });
                assertEquals("invalid", runtime.persistence().preferences().find(FileSettingsService.KEY).orElseThrow());
                assertThrows(java.sql.SQLException.class, () -> runtime.fileSettings().enabled());
                sql(runtime.persistence().database(), "DELETE FROM desktop_preference WHERE preference_key='file_delete_ai_explanation'");
                assertTrue(runtime.fileSettings().enabled());
                onEdt(() -> {
                    ((JButton) named(frame, "fileSettings.reload")).doClick(0);
                    return null;
                });
                await(() -> named(frame, "fileSettings.enabled").isEnabled());
                await(() -> !DesktopTaskRunner.hasActiveTasks());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}

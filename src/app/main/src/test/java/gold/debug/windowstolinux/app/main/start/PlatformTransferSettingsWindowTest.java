package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Locale;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.setting.TransferConfig;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTransferSettingsWindowTest {
    @TempDir
    Path root;

    @Test
    void actualSettingsPageSavesRejectsInvalidInputRestoresFailuresAndFollowsPrivacyLock() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var window = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                    FailureReportStore.disabled());
                window.installTransfers(runtime.transfers());
                ((JTabbedPane) named(window, "platform.pages"))
                    .setSelectedComponent(named(window, "platform.transfers"));
                window.setVisible(true);
                return window;
            });
            try {
                await(() -> named(frame, "transfer.settings.save").isEnabled());
                onEdt(() -> {
                    assertEquals("30", ((JTextField) named(frame, "transfer.settings.connect")).getText());
                    assertEquals("300", ((JTextField) named(frame, "transfer.settings.idle")).getText());
                    ((JTextField) named(frame, "transfer.settings.connect")).setText("12");
                    ((JTextField) named(frame, "transfer.settings.idle")).setText("40");
                    ((JButton) named(frame, "transfer.settings.save")).doClick(0);
                    return null;
                });
                await(() -> ((JLabel) named(frame, "transfer.settings.status")).getText().startsWith("已保存"));
                assertEquals(new TransferConfig(12, 40), runtime.transfers().read());
                onEdt(() -> {
                    ((JTextField) named(frame, "transfer.settings.connect")).setText("0");
                    ((JButton) named(frame, "transfer.settings.save")).doClick(0);
                    assertTrue(((JLabel) named(frame, "transfer.settings.status")).getText().contains("1～86400"));
                    return null;
                });
                assertEquals(new TransferConfig(12, 40), runtime.transfers().read());
                sql(runtime.persistence().database(),
                    "ALTER TABLE desktop_preference ADD CONSTRAINT fixture_transfer_save CHECK(preference_value <> '41')");
                try {
                    onEdt(() -> {
                        ((JTextField) named(frame, "transfer.settings.connect")).setText("41");
                        ((JTextField) named(frame, "transfer.settings.idle")).setText("50");
                        ((JButton) named(frame, "transfer.settings.save")).doClick(0);
                        return null;
                    });
                    await(() -> ((JLabel) named(frame, "transfer.settings.status")).getText().startsWith("操作未获确认"));
                    onEdt(() -> {
                        assertEquals("12", ((JTextField) named(frame, "transfer.settings.connect")).getText());
                        assertEquals("40", ((JTextField) named(frame, "transfer.settings.idle")).getText());
                        assertTrue(named(frame, "transfer.settings.save").isEnabled());
                        capture(frame, "platform-transfer-settings.png");
                        return null;
                    });
                    assertEquals(new TransferConfig(12, 40), runtime.transfers().read());
                } finally {
                    sql(runtime.persistence().database(),
                        "ALTER TABLE desktop_preference DROP CONSTRAINT fixture_transfer_save");
                }
                onEdt(() -> {
                    ((JButton) named(frame, "security.lock")).doClick(0);
                    return null;
                });
                await(() -> !named(frame, "transfer.settings.save").isEnabled());
                assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                    runtime.transfers()::read);
                runtime.security().unlock(PASSWORD.toCharArray());
                await(() -> named(frame, "transfer.settings.save").isEnabled());
                assertEquals(new TransferConfig(12, 40), runtime.transfers().read());
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

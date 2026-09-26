package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformLogServiceTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.util.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.db.log.LogRepository;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformLogWindowTest {
    @TempDir
    Path root;
    static int ownerRow(JTable table) {
        for (int i = 0; i < table.getRowCount(); i++)
            if (table.getValueAt(i, 0).equals(OWNER))
                return i;
        throw new AssertionError("Actual Mod log category is absent");
    }

    @Test
    void actualDesktopShowsRecordsCleansHistoryAndMakesStorageFailuresVisible() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
                var logger = runtime.logs(OWNER, VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var handle = logger.register(CATEGORY);
            for (int i = 0; i < 15; i++)
                assertEquals(LogStorage.DATABASE, logger.write(handle, event("界面诊断-" + i)).storage());
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var window = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                        ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                        FailureReportStore.disabled());
                window.installLogs(runtime.logs());
                ((JTabbedPane) named(window, "platform.pages")).setSelectedComponent(named(window, "platform.logs"));
                window.setVisible(true);
                return window;
            });
            try {
                await(() -> named(frame, "logs.clean").isEnabled());
                onEdt(() -> {
                    ((JButton) named(frame, "logs.clean")).doClick(0);
                    return null;
                });
                await(() -> named(frame, "logs.clean").isEnabled()
                        && ((JLabel) named(frame, "logs.status")).getText().startsWith("已完成"));
                onEdt(() -> {
                    var table = (JTable) named(frame, "logs.categories");
                    int row = ownerRow(table);
                    assertEquals("10", table.getValueAt(row, 3));
                    assertEquals("5", table.getValueAt(row, 4));
                    table.setRowSelectionInterval(row, row);
                    ((JButton) named(frame, "logs.inspect")).doClick(0);
                    return null;
                });
                await(() -> ((JTextArea) named(frame, "logs.records")).getText().contains("界面诊断-14"));
                onEdt(() -> {
                    capture(frame, "platform-log-management.png");
                    return null;
                });
                trigger(runtime, "INSERT");
                try {
                    var emergency = logger.write(handle, event("fallback record"));
                    assertEquals(LogStorage.EMERGENCY_FILE, emergency.storage());
                    await(() -> ((JLabel) named(frame, "platform.logIssue")).getText().contains("应急文件"));
                    byte[] original = Files.readAllBytes(file(root, handle));
                    Files.writeString(file(root, handle), "broken tail");
                    try {
                        assertEquals(LogStorage.NOT_SAVED,
                                logger.write(handle, event("lost and not replayed")).storage());
                        await(() -> ((JLabel) named(frame, "platform.logIssue")).getText().contains("日志保存失败"));
                        onEdt(() -> {
                            ((JButton) named(frame, "logs.reload")).doClick(0);
                            return null;
                        });
                        await(() -> ((JLabel) named(frame, "logs.status")).getText().startsWith("部分范围"));
                        onEdt(() -> {
                            var table = (JTable) named(frame, "logs.categories");
                            assertEquals("未核验", table.getValueAt(ownerRow(table), 5));
                            return null;
                        });
                        assertEquals("broken tail", Files.readString(file(root, handle)));
                    } finally {
                        Files.write(file(root, handle), original);
                    }
                } finally {
                    sql(runtime.persistence().database(), "DROP TRIGGER fixture_logs");
                }
                onEdt(() -> {
                    ((JButton) named(frame, "logs.reload")).doClick(0);
                    return null;
                });
                await(() -> ((JLabel) named(frame, "logs.status")).getText().startsWith("已完成"));
                var saved = new LogRepository(runtime.persistence().database()).read(target(runtime, handle));
                assertEquals(11, saved.size());
                assertTrue(saved.stream().noneMatch(r -> r.message().contains("lost and not replayed")));
                onEdt(() -> {
                    ((JButton) named(frame, "security.lock")).doClick(0);
                    return null;
                });
                await(() -> !named(frame, "logs.clean").isEnabled());
                assertThrows(gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.class,
                        runtime.logs()::cleanHistory);
                runtime.security().unlock(PASSWORD.toCharArray());
                await(() -> named(frame, "logs.clean").isEnabled());
                await(() -> !DesktopTaskExecutor.hasActiveTasks());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}

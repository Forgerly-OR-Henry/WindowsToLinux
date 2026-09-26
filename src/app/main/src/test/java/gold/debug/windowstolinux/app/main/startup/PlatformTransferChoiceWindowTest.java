package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformInteractionTest.dialog;
import static gold.debug.windowstolinux.app.main.startup.PlatformServerReferenceTest.completed;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.Locale;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTransferChoiceWindowTest {
    @TempDir
    Path root;

    @Test
    void actualWindowOffersAllThreeChoicesAndKeepsTerminationAsInitialChoice() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var messages = MessageCatalog.forLanguageTag("zh-CN");
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var window = new PlatformFrame(runtime.security(), messages, ThemePalette.light(),
                        new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
                window.setVisible(true);
                return window;
            });
            try (var interaction = new PlatformInteractionService(runtime.tasks(), runtime.security(), OWNER, VERSION,
                    new TaskInteractionPresenter(frame, messages))) {
                var conflict = new TransferConflict(
                        new TransferRequest(root.resolve("已存在 文件.txt"), "/application/data.txt", 100), false, 80, 50);
                String[] labels = {"覆盖", "保留两份", "终止"};
                int index = 0;
                for (var expected : TransferConflictChoice.values()) {
                    var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> success(
                            interaction.transferConflict(conflict, new ServerRef("server"), context)));
                    await(() -> dialog(frame) != null);
                    String label = labels[index++];
                    onEdt(() -> {
                        var window = dialog(frame);
                        assertEquals("终止", window.getRootPane().getDefaultButton().getText());
                        var text = ((JTextArea) named(window, "transfer.conflict.details")).getText();
                        assertTrue(text.contains(conflict.request().localPath().toString()));
                        assertTrue(text.contains(conflict.request().remoteAbsolutePath()));
                        assertTrue(text.contains("80"));
                        assertTrue(text.contains("50"));
                        for (String button : labels)
                            assertTrue(descendants(window).anyMatch(
                                    component -> component instanceof JButton item && button.equals(item.getText())));
                        if (expected == TransferConflictChoice.KEEP_BOTH)
                            capture(window, "platform-transfer-conflict.png");
                        descendants(window)
                                .filter(component -> component instanceof JButton item && label.equals(item.getText()))
                                .map(JButton.class::cast).findFirst().orElseThrow().doClick(0);
                        return null;
                    });
                    assertEquals(expected, completed(task));
                    await(() -> dialog(frame) == null);
                }
                var stall = new TransferStall(new TransferProgress(java.util.UUID.randomUUID(),
                        TransferProgress.Stage.VERIFYING_STAGING, 25, 100), conflict.request(), true,
                        java.time.Duration.ofMinutes(5));
                for (var expected : TransferStallChoice.values()) {
                    var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"),
                            context -> success(interaction.transferStalled(stall, new ServerRef("server"), context)));
                    await(() -> dialog(frame) != null);
                    onEdt(() -> {
                        var window = dialog(frame);
                        assertEquals("终止", window.getRootPane().getDefaultButton().getText());
                        var text = ((JTextArea) named(window, "transfer.stall.details")).getText();
                        assertTrue(text.contains("校验暂存文件"));
                        assertTrue(text.contains("25 / 100 字节"));
                        assertTrue(text.contains("300"));
                        assertTrue(text.contains("不代表文件操作已回滚"));
                        if (expected == TransferStallChoice.RETRY)
                            capture(window, "platform-transfer-stall.png");
                        String label = expected == TransferStallChoice.RETRY ? "重试" : "终止";
                        descendants(window).filter(c -> c instanceof JButton item && label.equals(item.getText()))
                                .map(JButton.class::cast).findFirst().orElseThrow().doClick(0);
                        return null;
                    });
                    assertEquals(expected, completed(task));
                    await(() -> dialog(frame) == null);
                }
            } finally {
                onEdt(() -> {
                    for (Window window : frame.getOwnedWindows())
                        window.dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}

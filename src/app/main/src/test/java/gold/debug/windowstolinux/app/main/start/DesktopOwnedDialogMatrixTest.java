package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.control.DesktopDialogs;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the remaining host-owned business window shells using isolated persistent facts. / 使用隔离持久事实覆盖其余宿主业务窗口壳。 */
class DesktopOwnedDialogMatrixTest {
    @TempDir
    Path root;

    @Test
    void pendingChangesAndRestartProgressFitEveryAppearanceWithoutRestartingOrApplyingAnUndo() throws Exception {
        var ui = new PlatformModManagementTest();
        ui.root = root;
        var setup = new PlatformModIntentsTest();
        setup.root = root;
        ui.runtime = setup.runtime();
        try {
            add(ui.runtime, P, false, Map.of(), null);
            ui.window();
            ui.select(P);
            ui.click("mods.enabled");
            ui.click("mods.save");
            ui.answer(ui.dialog(), true);
            ui.idle();
            assertEquals(1, ui.runtime.modIntents().pendingChanges().size());
            onEdt(() -> {
                ui.frame.dispose();
                return null;
            });
            int windows = 0;
            for (String language : List.of("zh-CN", "en"))
                for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                    for (int zoom : new int[]{100, 125, 150})
                        for (boolean limited : new boolean[]{false, true}) {
                            var messages = MessageCatalog.forLanguageTag(language);
                            ui.frame = onEdt(() -> {
                                DesktopThemeService.install(theme, zoom);
                                var frame = new PlatformFrame(ui.runtime.security(), messages,
                                    theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
                                    new UiEnvironment(Locale.forLanguageTag(language), theme.name(), false), FailureReportStore.disabled());
                                frame.setVisible(true);
                                return frame;
                            });
                            try {
                                ApplicationLauncher.installModManagement(ui.runtime, ui.frame);
                                ui.idle();
                                ui.click("mods.viewChanges");
                                var pending = ui.dialog();
                                String variant = language + "-" + theme + "-" + zoom + (limited ? "-restricted" : "-normal");
                                onEdt(() -> {
                                    assertEquals(messages.text("modui.pendingTitle"), pending.getTitle());
                                    verify(pending, "pending-" + variant, limited);
                                    for (var facts : descendants(pending).filter(JTextArea.class::isInstance).map(JTextArea.class::cast).toList()) {
                                        assertEquals(facts.getWidth(), facts.getPreferredSize().width,
                                            () -> "Facts must be measured at their allocated width: " + variant + geometry(facts));
                                        assertTrue(facts.getHeight() >= facts.getPreferredSize().height,
                                            () -> "Complete pending-change facts must remain readable: " + variant + geometry(facts));
                                        var last = facts.modelToView2D(facts.getDocument().getLength());
                                        assertNotNull(last);
                                        assertTrue(last.getMaxY() <= facts.getHeight() - facts.getInsets().bottom,
                                            () -> "The final facts line must fit: " + variant + geometry(facts));
                                    }
                                    var close = (JButton) named(pending, "mods.changes.close");
                                    assertEquals(messages.text("modui.close"), close.getText());
                                    assertTrue(close.getVisibleRect().height == close.getHeight());
                                    close.doClick(0);
                                    return null;
                                });
                                await(() -> !pending.isDisplayable());
                                var show = PlatformFrame.class.getDeclaredMethod("showRestartProgress");
                                var hide = PlatformFrame.class.getDeclaredMethod("hideRestartProgress");
                                var field = PlatformFrame.class.getDeclaredField("restartProgress");
                                show.setAccessible(true);
                                hide.setAccessible(true);
                                field.setAccessible(true);
                                onEdt(() -> {
                                    show.invoke(ui.frame);
                                    return null;
                                });
                                var progress = onEdt(() -> (JDialog) field.get(ui.frame));
                                await(progress::isShowing);
                                onEdt(() -> {
                                    assertEquals(messages.text("modui.restart.title"), progress.getTitle());
                                    assertTrue(descendants(progress).anyMatch(JProgressBar.class::isInstance));
                                    verify(progress, "restart-" + variant, limited);
                                    hide.invoke(ui.frame);
                                    return null;
                                });
                                await(() -> !progress.isDisplayable());
                                assertEquals(1, ui.runtime.modIntents().pendingChanges().size(), "Preview cannot undo or restart");
                                windows += 2;
                            } finally {
                                onEdt(() -> {
                                    ui.frame.dispose();
                                    return null;
                                });
                            }
                        }
            assertEquals(48, windows);
        } finally {
            ui.cleanup();
            onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                return null;
            });
        }
    }

    private static String geometry(Component component) {
        var result = new StringBuilder();
        for (Component current = component; current != null; current = current.getParent())
            result.append('\n').append(current.getClass().getName()).append(' ').append(current.getName())
                .append(" bounds=").append(current.getBounds()).append(" preferred=").append(current.getPreferredSize());
        return result.toString();
    }

    private static void verify(JDialog dialog, String name, boolean limited) throws Exception {
        Rectangle area = DesktopDialogs.workArea(dialog);
        if (limited) area = new Rectangle(area.x, area.y, Math.min(area.width, 800), Math.min(area.height, 500));
        DesktopDialogs.constrain(dialog, area, dialog.getSize());
        assertTrue(area.contains(dialog.getBounds()), name);
        String output = System.getProperty("desktop.owned.capture");
        if (output == null) return;
        Path directory = Path.of(output);
        Files.createDirectories(directory);
        var bitmap = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            dialog.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(bitmap, "png", directory.resolve(name + ".png").toFile());
    }
}

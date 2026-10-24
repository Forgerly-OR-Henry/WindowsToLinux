package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.Window;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModStorePane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises actual Swing action listeners independently of native pointer delivery. / 独立于原生指针投递验证真实 Swing 操作监听器。 */
class ModStoreDialogLifecycleTest {
    @TempDir
    Path root;

    @Test
    void lockAndPageCloseRevokeOnlyTheCurrentUnsubmittedReviewWithoutDownloading() throws Exception {
        try (var fixture = new ModStoreFixture(root)) {
            var release = fixture.publish("sample.dialog.lifecycle", "1.0.0", Map.of(), Map.of(), Map.of());
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.DARK, 150);
                var owner = new JFrame("Store dialog lifecycle");
                var pane = new ModStorePane(fixture.service(), new DesktopPartFactory(ThemePalette.dark()),
                    new PageMessagePresenter(MessageCatalog.forLanguageTag("en")), () -> fail("No installation expected"));
                owner.add(pane);
                owner.setBounds(30, 30, 1000, 700);
                owner.setVisible(true);
                pane.setLocked(false);
                return owner;
            });
            var pane = (ModStorePane) named(frame, "mods.store");
            try {
                select(pane, release.owner());
                JDialog original = open(frame);
                onEdt(() -> {
                    pane.setLocked(true);
                    return null;
                });
                await(() -> !original.isDisplayable());
                assertEquals(0, fixture.requests(release));
                assertEquals(0, onEdt(() -> ((JList<?>) named(pane, "store.results")).getModel().getSize()));
                onEdt(() -> {
                    pane.setLocked(false);
                    return null;
                });
                select(pane, release.owner());
                JDialog replacement = open(frame);
                assertNotSame(original, replacement);
                onEdt(() -> {
                    pane.close();
                    return null;
                });
                await(() -> !replacement.isDisplayable());
                assertEquals(0, fixture.requests(release));
                assertFalse(onEdt(() -> named(pane, "store.install").isEnabled()));
                assertNull(System.getProperty(ModStoreFixture.AUTHOR_MARK));
            } finally {
                onEdt(() -> {
                    pane.close();
                    for (Window child : frame.getOwnedWindows()) child.dispose();
                    frame.dispose();
                    DesktopThemeService.install(ThemeMode.LIGHT, 100);
                    return null;
                });
            }
        }
    }

    private static void select(ModStorePane pane, String owner) throws Exception {
        await(() -> ((JList<?>) named(pane, "store.results")).getModel().getSize() > 0);
        onEdt(() -> {
            pane.selectOwner(owner);
            return null;
        });
        await(() -> named(pane, "store.install").isEnabled());
    }

    private static JDialog open(JFrame owner) throws Exception {
        SwingUtilities.invokeLater(() -> ((JButton) named(owner, "store.install")).doClick(0));
        await(() -> Arrays.stream(owner.getOwnedWindows()).anyMatch(window -> window.isShowing()
            && "store.confirmation".equals(window.getName())));
        return onEdt(() -> Arrays.stream(owner.getOwnedWindows()).filter(window -> window.isShowing()
            && "store.confirmation".equals(window.getName())).map(JDialog.class::cast).findFirst().orElseThrow());
    }
}

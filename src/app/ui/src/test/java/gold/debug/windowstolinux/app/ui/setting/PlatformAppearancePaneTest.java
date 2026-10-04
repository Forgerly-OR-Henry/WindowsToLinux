package gold.debug.windowstolinux.app.ui.setting;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import org.junit.jupiter.api.Test;

class PlatformAppearancePaneTest {
    @Test
    void zoomDraftAppliesOnlyAfterSuccessfulSaveAndRespectsTheLock() throws Exception {
        var fail = new AtomicBoolean(true);
        var applied = new AtomicInteger();
        var saved = new AtomicReference<DesktopDisplayConfig>();
        var pane = new AtomicReference<PlatformAppearancePane>();
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            pane.set(new PlatformAppearancePane(new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT, 100), requested -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                if (fail.get()) throw new IOException("Appearance save fixture");
                saved.set(requested);
            }, requested -> {
                applied.incrementAndGet();
                DesktopThemeService.apply(requested.themeMode(), requested.scalePercent());
            }, new DesktopPartFactory(ThemePalette.light()), new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"))));
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                ((JComboBox<?>) named(pane.get(), "appearance.scale")).setSelectedItem(200);
                ((javax.swing.JCheckBox) named(pane.get(), "appearance.restoreLastPage")).setSelected(true);
                assertEquals(1f, com.formdev.flatlaf.util.UIScale.getZoomFactor(), "A draft must not zoom the window");
                ((JButton) named(pane.get(), "appearance.save")).doClick(0);
                assertFalse(named(pane.get(), "appearance.scale").isEnabled(), "Pending save freezes scale");
            });
            idle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0, applied.get());
                assertEquals(1f, com.formdev.flatlaf.util.UIScale.getZoomFactor());
                assertEquals(200, ((JComboBox<?>) named(pane.get(), "appearance.scale")).getSelectedItem());
                pane.get().setLocked(true);
                assertFalse(named(pane.get(), "appearance.scale").isEnabled());
                assertFalse(named(pane.get(), "appearance.save").isEnabled());
                assertFalse(named(pane.get(), "appearance.restoreLastPage").isEnabled());
                pane.get().setLocked(false);
                fail.set(false);
                ((JButton) named(pane.get(), "appearance.save")).doClick(0);
            });
            idle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, applied.get());
                assertEquals(200, saved.get().scalePercent());
                assertTrue(saved.get().restoreLastPage());
                assertEquals(2f, com.formdev.flatlaf.util.UIScale.getZoomFactor());
                assertTrue(named(pane.get(), "appearance.scale").isEnabled());
            });
        } finally {
            idle();
            SwingUtilities.invokeAndWait(() -> {
                pane.get().close();
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
            });
        }
    }

    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container)
            for (var child : container.getComponents()) {
                Component found = named(child, name);
                if (found != null) return found;
            }
        return null;
    }

    private static void idle() throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (DesktopTaskRunner.hasActiveTasks() && System.nanoTime() < deadline) Thread.sleep(20);
        SwingUtilities.invokeAndWait(() -> assertFalse(DesktopTaskRunner.hasActiveTasks()));
    }
}

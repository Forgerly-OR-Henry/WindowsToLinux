package gold.debug.windowstolinux.app.ui.setting;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

class PlatformMotionPreferenceTest {
    static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container parent) for (var child : parent.getComponents()) {
            var value = named(child, name);
            if (value != null) return value;
        }
        return null;
    }

    static void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            Thread.sleep(10);
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        while (DesktopTaskRunner.hasActiveTasks() && System.nanoTime() < deadline);
        assertFalse(DesktopTaskRunner.hasActiveTasks());
    }

    @Test
    void latestMotionChoiceSavesSeparatelyEvenWhenEditorCloses() throws Exception {
        var first = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = Collections.synchronizedList(new ArrayList<Boolean>());
        var formWrites = new AtomicInteger();
        var pane = new AtomicReference<PlatformAppearancePane>();
        boolean previous = DesktopMotion.preference();
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            pane.set(new PlatformAppearancePane(DesktopDisplayConfig.defaults(), new PlatformAppearancePane.Preferences() {
                public void save(DesktopDisplayConfig config) {
                    formWrites.incrementAndGet();
                }

                public void saveMotion(boolean enabled) throws Exception {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    writes.add(enabled);
                    if (writes.size() == 1) {
                        first.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                }
            }, ignored -> fail("Other drafts must not apply"), new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"))));
            ((JComboBox<?>) named(pane.get(), "appearance.theme")).setSelectedItem(ThemeMode.DARK);
            ((JCheckBox) named(pane.get(), "appearance.animation")).doClick(0);
            assertFalse(DesktopMotion.preference());
        });
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var toggle = (JCheckBox) named(pane.get(), "appearance.animation");
                toggle.doClick(0);
                toggle.doClick(0);
                toggle.doClick(0);
                assertTrue(DesktopMotion.preference());
                pane.get().close();
            });
            release.countDown();
            idle();
            assertEquals(List.of(false, true), writes);
            assertEquals(0, formWrites.get());
        } finally {
            release.countDown();
            idle();
            SwingUtilities.invokeAndWait(() -> {
                pane.get().close();
                DesktopMotion.preference(previous);
            });
        }
    }

    @Test
    void defaultsAndInvalidStoredValuesAreExplicit() {
        assertTrue(DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "100", null, "false", Locale.ENGLISH).animationEnabled());
        assertFalse(DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "150", "false", "true", Locale.ENGLISH).animationEnabled());
        assertThrows(IllegalArgumentException.class, () -> DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "100", "invalid", "false", Locale.ENGLISH));
    }

    @Test
    void failedSaveKeepsSessionChoiceAndReportsUnsaved() throws Exception {
        boolean previous = DesktopMotion.preference();
        var pane = new AtomicReference<PlatformAppearancePane>();
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            pane.set(new PlatformAppearancePane(DesktopDisplayConfig.defaults(), new PlatformAppearancePane.Preferences() {
                public void save(DesktopDisplayConfig value) {
                    fail("Full draft must not save");
                }

                public void saveMotion(boolean value) throws Exception {
                    throw new java.io.IOException("fixture storage failure");
                }
            }, ignored -> fail("Full draft must not apply"), new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"))));
            ((JCheckBox) named(pane.get(), "appearance.animation")).doClick(0);
        });
        try {
            idle();
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(DesktopMotion.preference());
                assertFalse(((JCheckBox) named(pane.get(), "appearance.animation")).isSelected());
                assertEquals(MessageCatalog.forLanguageTag("zh-CN").text("appearance.animation.notSaved"),
                    ((JLabel) named(pane.get(), "appearance.status")).getText());
                assertFalse(((JLabel) named(pane.get(), "appearance.status")).getToolTipText().isBlank());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                pane.get().close();
                DesktopMotion.preference(previous);
            });
        }
    }
}

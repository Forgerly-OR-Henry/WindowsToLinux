package gold.debug.windowstolinux.app.ui.setting;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import org.junit.jupiter.api.Test;

class NavigationOrderPaneTest {
    @Test
    void clearedSelectionSurvivesReloadWithoutSavingAndEmptyInventoryKeepsItsScroll() throws Exception {
        var saved = new AtomicReference<>(List.of("platform.servers", "platform.models"));
        var writes = new AtomicInteger();
        var pane = edt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var root = new NavigationOrderPane(new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("en"), FailureReportStore.disabled()));
            root.section("navigation.platform", saved::get, List::copyOf, ignored -> List.of(), value -> value,
                (expected, requested) -> {
                    writes.incrementAndGet();
                    return requested;
                }, ignored -> {
                });
            root.setLocked(false);
            return root;
        });
        try {
            var list = edt(() -> (JList<?>) named(pane, "navigation.platform.list"));
            await(() -> list.isEnabled() && list.getModel().getSize() == 2);
            edt(() -> {
                list.setSelectedIndex(0);
                list.clearSelection();
                assertFalse(named(pane, "navigation.platform.up").isEnabled());
                assertFalse(named(pane, "navigation.platform.down").isEnabled());
                pane.refresh();
                return null;
            });
            await(list::isEnabled);
            edt(() -> {
                assertEquals(-1, list.getSelectedIndex());
                saved.set(List.of());
                pane.refresh();
                return null;
            });
            await(() -> list.isEnabled() && list.getModel().getSize() == 0);
            edt(() -> {
                var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
                assertTrue(scroll.isVisible());
                assertTrue(named(pane, "navigation.platform.empty").isVisible());
                assertEquals(0, writes.get());
                return null;
            });
        } finally {
            edt(() -> {
                pane.close();
                return null;
            });
            await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.hasActiveTasks());
        }
    }

    @Test
    void buttonsAndAltCountLocallyPreserveHiddenSlotsAndFlushOnLeave() throws Exception {
        var initial = List.of("platform.servers", "hidden", "platform.models", "tasks.title");
        var saved = new AtomicReference<>(initial);
        var writes = new AtomicInteger();
        var pane = edt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var root = new NavigationOrderPane(new DesktopPartFactory(ThemePalette.light()),
                new PageMessagePresenter(MessageCatalog.forLanguageTag("en"), FailureReportStore.disabled()));
            root.section("navigation.platform", saved::get, all -> all.stream().filter(id -> !id.equals("hidden")).toList(),
                ignored -> List.of("platform.servers", "platform.models", "tasks.title"), value -> value,
                (expected, visible) -> {
                    assertEquals(saved.get(), expected);
                    var full = gold.debug.windowstolinux.app.db.setting.NavigationPreferenceStore.reorderVisible(expected, visible);
                    writes.incrementAndGet();
                    saved.set(full);
                    return full;
                }, ignored -> {
                });
            root.setLocked(false);
            return root;
        });
        try {
            var list = edt(() -> (JList<?>) named(pane, "navigation.platform.list"));
            await(list::isEnabled);
            edt(() -> {
                list.setSelectedIndex(0);
                ((JButton) named(pane, "navigation.platform.down")).doClick(0);
                assertEquals(initial, saved.get());
                list.getActionMap().get("alt DOWN").actionPerformed(null);
                assertEquals(initial, saved.get());
                list.getActionMap().get("alt UP").actionPerformed(null);
                return null;
            });
            await(() -> writes.get() == 1 && list.isEnabled());
            assertEquals(List.of("platform.models", "hidden", "platform.servers", "tasks.title"), saved.get());
            edt(() -> {
                ((JButton) named(pane, "navigation.platform.reset")).doClick(0);
                pane.setLocked(true);
                return null;
            });
            assertEquals(1, writes.get());
            assertTrue(edt(pane::hasPendingEdits));
            edt(() -> {
                pane.refresh();
                return null;
            });
            assertEquals(1, writes.get());
            edt(() -> {
                pane.setLocked(false);
                return null;
            });
            await(() -> writes.get() == 2 && list.isEnabled());
            assertTrue(edt(pane::saveBeforeLeave).toCompletableFuture().get(6, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(initial, saved.get());
            assertEquals(2, writes.get());
        } finally {
            edt(() -> {
                pane.close();
                return null;
            });
        }
    }

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T edt(java.util.concurrent.Callable<T> action) throws Exception {
        var task = new java.util.concurrent.FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (edt(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        assertTrue(edt(condition::getAsBoolean));
    }
}

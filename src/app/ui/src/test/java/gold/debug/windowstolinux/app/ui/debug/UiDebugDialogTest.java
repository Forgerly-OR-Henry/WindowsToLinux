package gold.debug.windowstolinux.app.ui.debug;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;

class UiDebugDialogTest {
    @Test
    void clearedPreviewStaysUnselectedAfterMetadataAndLocaleRefreshWithoutOpeningPages() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var created = new AtomicInteger();
        var dialog = new AtomicReference<UiDebugDialog>();
        var scope = new AtomicReference<ModPageRegistry.PageScope>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    DesktopThemeService.install(ThemeMode.LIGHT);
                    var host = new ModPageRegistry.PageHost() {
                        public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                        }

                        public void focus(ModPageRegistry.PageKey key) {
                        }

                        public void remove(ModPageRegistry.PageKey key) {
                        }

                        public void environment(UiEnvironment environment) {
                        }
                    };
                    var registry = new ModPageRegistry(host, new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
                    registry.installPreviews(host);
                    scope.set(registry.begin("example.preview", false));
                    scope.get().registerPreview(new PageSpec("first", "fixture.first", "First preview", true), () -> {
                        created.incrementAndGet();
                        return new ModPage() {
                            public JComponent create(UiEnvironment environment) {
                                return new JPanel();
                            }

                            public void environmentChanged(UiEnvironment environment) {
                            }

                            public void close() {
                            }
                        };
                    });
                    scope.get().commit();
                    dialog.set(new UiDebugDialog(null, registry, new DesktopPartFactory(ThemePalette.light()),
                        new PageMessagePresenter(MessageCatalog.forLanguageTag("en"))));
                    var list = (JList<?>) named(dialog.get(), "debug.previews");
                    var open = (JButton) named(dialog.get(), "debug.open");
                    assertTrue(open.isEnabled());
                    list.clearSelection();
                    assertFalse(open.isEnabled());
                    open.doClick();
                    dialog.get().localize();
                    assertNull(list.getSelectedValue());
                    scope.get().registerPreview(new PageSpec("second", "fixture.second", "Second preview", true), () -> {
                        throw new AssertionError("Metadata refresh must not construct a preview");
                    });
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            SwingUtilities.invokeAndWait(() -> {
                var list = (JList<?>) named(dialog.get(), "debug.previews");
                assertEquals(2, list.getModel().getSize());
                assertNull(list.getSelectedValue());
                assertFalse(named(dialog.get(), "debug.open").isEnabled());
                assertEquals(0, created.get());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog.get() != null) dialog.get().dispose();
                if (scope.get() != null)
                    try {
                        scope.get().close();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
            });
        }
    }

    private static Component named(Container parent, String name) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                var found = named(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}

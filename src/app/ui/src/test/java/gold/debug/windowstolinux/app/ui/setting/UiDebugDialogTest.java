package gold.debug.windowstolinux.app.ui.setting;

import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class UiDebugDialogTest {
    @Test void previewFacadeHasNoWriteOrTransportOperations() throws Exception {
        var preview = new UiPreviewService(new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
        Set<String> reads = Set.of("listServerProfiles", "listServerSummaries", "findServerProfile", "listAiConfigurations",
                "listApplications", "listManagedApplicationSummaries", "scanApplications", "invokeAiRole");
        for (var method : UiPreviewService.Facade.class.getMethods()) {
            if (reads.contains(method.getName())) continue;
            Object[] arguments = new Object[method.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) if (method.getParameterTypes()[i] == boolean.class) arguments[i] = false;
            var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(preview.facade, arguments), method.getName());
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        }
        assertEquals(2, preview.facade.scanApplications("anything", new char[0], value -> fail("No SSH trust request expected")).candidates().size());
        assertTrue(preview.facade.invokeAiRole(null, new char[0]).isEmpty());
    }

    @Test void everyPreviewOpensAndClosesInBothLanguagesAndThemes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (String locale : List.of("zh-CN", "en-US")) {
            for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK)) {
                SwingUtilities.invokeAndWait(() -> checkPreviews(locale, theme));
            }
        }
    }

    private static void checkPreviews(String locale, ThemeMode theme) {
        DesktopThemeService.install(theme);
        var catalog = MessageCatalog.forLanguageTag(locale);
        var navigated = new java.util.ArrayList<String>();
        UiDebugDialog gallery = new UiDebugDialog(null,
                new DesktopComponentFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light()), catalog, navigated::add);
        try {
            gallery.setVisible(true);
            for (String page : List.of("deployment", "components", "servers", "applications", "backup", "ai", "settings")) {
                descendants(gallery).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> button.getText().equals(catalog.text("nav." + page))).findFirst().orElseThrow().doClick(0);
            }
            assertEquals(List.of("deployment", "components", "servers", "applications", "backup", "ai", "settings"), navigated);
            render(gallery, locale + "-" + theme + "-gallery");
            JList<?> list = descendants(gallery).filter(JList.class::isInstance).map(JList.class::cast).findFirst().orElseThrow();
            assertTrue(list.getModel().getSize() >= 35, "Keep process-only dialogs reachable");
            for (int i = 0; i < list.getModel().getSize(); i++) {
                UiDebugDialog.Preview entry = (UiDebugDialog.Preview) list.getModel().getElementAt(i);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                boolean[] opened = {false};
                Timer close = new Timer(180, event -> {
                    try {
                        Window window = Stream.of(gallery.getOwnedWindows()).filter(Window::isShowing).findFirst().orElseThrow();
                        opened[0] = true;
                        assertTrue(window.getWidth() > 100 && window.getHeight() > 80, entry.id());
                        if (Set.of("inputs", "system.true", "db.replace").contains(entry.id())) render(window, locale + "-" + theme + "-" + entry.id());
                    } catch (Throwable problem) { failure.set(problem); }
                    finally {
                        for (Window window : gallery.getOwnedWindows()) if (window.isShowing()) {
                            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)); window.dispose();
                        }
                    }
                });
                close.setRepeats(false); close.start();
                try { entry.open().run(); }
                finally { close.stop(); }
                if (failure.get() != null) throw new AssertionError(entry.id(), failure.get());
                assertTrue(opened[0], entry.id() + " did not open");
                assertTrue(Stream.of(gallery.getOwnedWindows()).noneMatch(Window::isShowing));
            }
        } finally { gallery.dispose(); }
    }

    private static Stream<Component> descendants(Container parent) {
        return Stream.of(parent.getComponents()).flatMap(child -> child instanceof Container nested
                ? Stream.concat(Stream.of(child), descendants(nested)) : Stream.of(child));
    }

    private static void render(Window window, String name) {
        try {
            Path directory = Path.of("target", "visual-checks", "ui-debug"); Files.createDirectories(directory);
            BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { window.paint(graphics); } finally { graphics.dispose(); }
            javax.imageio.ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}

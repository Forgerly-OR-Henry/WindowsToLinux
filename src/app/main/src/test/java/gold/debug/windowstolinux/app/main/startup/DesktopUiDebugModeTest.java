package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javax.swing.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver.RunMode;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.setting.UiDebugDialog;
import gold.debug.windowstolinux.app.ui.shell.DesktopFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUiDebugModeTest {
    @TempDir
    Path directory;

    @Test
    void startupAndAppearanceRebuildExposeDebugOnlyInClassMode() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (DesktopPersistence database = DesktopPersistence.open(directory)) {
            for (RunMode mode : RunMode.values()) {
                SwingUtilities.invokeAndWait(() -> {
                    DesktopWindowController controller = new DesktopWindowController(database, null,
                            new DesktopDisplayConfiguration("zh-CN", ThemeMode.LIGHT), FailureReportStore.disabled(),
                            mode);
                    try {
                        controller.showInitialWindow();
                        for (String locale : List.of("zh-CN", "en-US")) {
                            DesktopFrame frame = field(controller, "frame", DesktopFrame.class);
                            if (locale.equals("en-US")) {
                                var apply = DesktopWindowController.class.getDeclaredMethod("applyAppearance",
                                        DesktopFrame.class, DesktopDisplayConfiguration.class);
                                apply.setAccessible(true);
                                apply.invoke(controller, frame,
                                        new DesktopDisplayConfiguration(locale, ThemeMode.DARK));
                                assertFalse(frame.isDisplayable());
                                assertTrue(Stream.of(frame.getOwnedWindows()).noneMatch(Window::isDisplayable));
                                frame = field(controller, "frame", DesktopFrame.class);
                            }
                            var catalog = MessageCatalog.forLanguageTag(locale);
                            descendants(frame).filter(JButton.class::isInstance).map(JButton.class::cast)
                                    .filter(button -> catalog.text("nav.settings").equals(button.getToolTipText()))
                                    .findFirst().orElseThrow().doClick(0);
                            descendants(frame).filter(AdvancedOptionsPane.class::isInstance)
                                    .map(AdvancedOptionsPane.class::cast).filter(Component::isShowing).findFirst()
                                    .orElseThrow().setExpanded(true);
                            var buttons = Stream
                                    .concat(descendants(frame),
                                            Stream.of(frame.getOwnedWindows())
                                                    .flatMap(DesktopUiDebugModeTest::descendants))
                                    .filter(JButton.class::isInstance).map(JButton.class::cast)
                                    .filter(button -> "settings.uiDebug".equals(button.getName())).toList();
                            assertEquals(mode == RunMode.RUN_CLASS ? 1 : 0, buttons.size(), mode.name());
                            if (!buttons.isEmpty()) {
                                buttons.getFirst().doClick(0);
                                buttons.getFirst().doClick(0);
                                assertEquals(1,
                                        Stream.of(frame.getOwnedWindows())
                                                .filter(window -> window instanceof UiDebugDialog && window.isShowing())
                                                .count());
                            }
                        }
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    } finally {
                        field(controller, "systemThemeTimer", Timer.class).stop();
                        DesktopFrame frame = field(controller, "frame", DesktopFrame.class);
                        if (frame != null)
                            frame.dispose();
                    }
                });
            }
        }
    }

    private static <T> T field(Object instance, String name, Class<T> type) {
        try {
            var field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(instance));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Stream<Component> descendants(Container parent) {
        return Stream.of(parent.getComponents())
                .flatMap(child -> child instanceof Container nested
                        ? Stream.concat(Stream.of(child), descendants(nested))
                        : Stream.of(child));
    }
}

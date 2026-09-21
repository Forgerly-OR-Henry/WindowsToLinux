package gold.debug.windowstolinux.app.ui.deployment.automatic;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.component.ToggleSwitch;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentInputDialogTest {
    @Test void databaseReplacementRequiresThreeExplicitSwitchesAndResetsEachRequest() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
            var input = new DeploymentInputDialog(owner, null, new DesktopComponentFactory(ThemePalette.light()), messages);
            var details = Map.of("server", "preview", "instance", "main", "current", "16", "required", "17", "target", "17", "data", "/preview");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            int[] phase = {0};
            Timer actions = new Timer(100, event -> {
                try {
                    Window window = Stream.of(owner.getOwnedWindows()).filter(Window::isShowing).findFirst().orElseThrow();
                    JOptionPane pane = descendants(window).filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast).findFirst().orElseThrow();
                    var switches = descendants(window).filter(ToggleSwitch.class::isInstance).map(ToggleSwitch.class::cast).toList();
                    switch (phase[0]++) {
                        case 0 -> {
                            assertEquals(3, switches.size()); assertTrue(switches.stream().noneMatch(AbstractButton::isSelected));
                            switches.get(0).doClick(0); switches.get(1).doClick(0); pane.setValue(JOptionPane.OK_OPTION);
                        }
                        case 1 -> {
                            assertTrue(switches.isEmpty(), "Two confirmations must show the missing-confirmation message");
                            assertEquals(messages.text("db.replace.checkAll"), pane.getMessage()); pane.setValue(JOptionPane.OK_OPTION);
                        }
                        case 2 -> {
                            assertEquals(3, switches.size()); assertFalse(switches.get(2).isSelected());
                            switches.get(2).doClick(0); pane.setValue(JOptionPane.OK_OPTION);
                        }
                        case 3 -> {
                            assertEquals(3, switches.size()); assertTrue(switches.stream().noneMatch(AbstractButton::isSelected));
                            pane.setValue(JOptionPane.CANCEL_OPTION);
                        }
                        default -> fail("Unexpected extra confirmation");
                    }
                } catch (Throwable problem) {
                    failure.set(problem);
                    for (Window window : owner.getOwnedWindows()) window.dispose();
                    ((Timer) event.getSource()).stop();
                }
            });
            try {
                actions.start(); assertTrue(input.confirmDatabaseReplacement(details));
                assertFalse(input.confirmDatabaseReplacement(details));
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(4, phase[0]);
            } finally { actions.stop(); owner.dispose(); }
        });
    }

    private static Stream<Component> descendants(Container parent) {
        return Stream.of(parent.getComponents()).flatMap(child -> child instanceof Container nested
                ? Stream.concat(Stream.of(child), descendants(nested)) : Stream.of(child));
    }

    @Test void inputFailuresRemainFailuresAndExplicitCancellationRemainsCancellation() throws Exception {
        var dispatch = DeploymentInputDialog.class.getDeclaredMethod("onEdt", Callable.class); dispatch.setAccessible(true);
        for (RuntimeException expected : new RuntimeException[]{new IllegalArgumentException("invalid field"), new CancellationException()}) {
            var failure = assertThrows(InvocationTargetException.class,
                    () -> dispatch.invoke(null, (Callable<Object>) () -> { throw expected; }));
            assertSame(expected, failure.getCause());
        }
    }
}

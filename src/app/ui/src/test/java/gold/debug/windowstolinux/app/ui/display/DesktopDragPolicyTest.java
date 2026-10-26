package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;

class DesktopDragPolicyTest {
    @Test
    void globalOffClearsAnActiveReturnWithoutSubmittingAnOrder() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var writes = new AtomicInteger();
        var list = edt(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            var model = new DefaultListModel<String>();
            model.addElement("First");
            model.addElement("Second");
            model.addElement("Third");
            var value = new DesktopCardList<String>(model, 116);
            new gold.debug.windowstolinux.app.ui.control.DesktopPartFactory(ThemePalette.light()).cardList(value, true);
            value.reorder(order -> writes.incrementAndGet(), () -> true);
            return value;
        });
        var frame = edt(() -> {
            var window = new JFrame("Drag policy");
            window.setContentPane(new JScrollPane(list));
            window.setSize(700, 500);
            window.setVisible(true);
            window.validate();
            return window;
        });
        try {
            edt(() -> {
                Rectangle handle = list.handleBounds(0);
                Point from = new Point(handle.x + handle.width / 2, handle.y + handle.height / 2);
                Point to = new Point(from.x, from.y + list.getFixedCellHeight());
                dispatch(list, MouseEvent.MOUSE_PRESSED, from, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                dispatch(list, MouseEvent.MOUSE_DRAGGED, to, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                assertEquals(Boolean.TRUE, list.getClientProperty("desktop.cardDragActive"));
                list.cancelInteraction();
                DesktopMotion.preference(false);
                assertEquals(0, DesktopMotion.runningCount());
                assertEquals(0, writes.get());
                assertNotEquals(Boolean.TRUE, list.getClientProperty("desktop.cardDragActive"));
                assertEquals("First", list.getModel().getElementAt(0));
                return null;
            });
        } finally {
            edt(() -> {
                frame.dispose();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                return null;
            });
        }
    }

    private static void dispatch(Component component, int id, Point point, int modifiers, int button) {
        Point screen = component.getLocationOnScreen();
        screen.translate(point.x, point.y);
        component.dispatchEvent(new MouseEvent(component, id, System.currentTimeMillis(), modifiers, point.x, point.y,
            screen.x, screen.y, 1, false, button));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}

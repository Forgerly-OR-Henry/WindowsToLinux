package gold.debug.windowstolinux.app.ui.display;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.Arrays;
import javax.swing.JList;
import javax.swing.event.MouseInputAdapter;

import gold.debug.windowstolinux.app.ui.control.DesktopCardList;

/** Records fixture gesture delivery without changing input or component state. / 记录夹具手势投递，不改变输入或控件状态。 */
public final class DesktopCardDragDiagnostics implements AutoCloseable {
    private final JList<?> list;

    private final StringBuilder events = new StringBuilder();

    private final MouseInputAdapter mouse = new MouseInputAdapter() {
        @Override
        public void mousePressed(MouseEvent event) {
            record("press " + event.getPoint());
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            record("drag " + event.getPoint());
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            record("release " + event.getPoint());
        }
    };

    private final FocusAdapter focus = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent event) {
            record("focusLost");
        }

        @Override
        public void focusGained(FocusEvent event) {
            record("focusGained");
        }
    };

    private final ComponentAdapter geometry = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            record("resized " + list.getSize());
        }
    };

    public DesktopCardDragDiagnostics(JList<?> list) {
        this.list = list;
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        list.addFocusListener(focus);
        list.addComponentListener(geometry);
    }

    private void record(String event) {
        events.append(event).append(" pressed=").append(field("pressed"))
            .append(" active=").append(list.getClientProperty("desktop.cardDragActive")).append(';');
    }

    private Object field(String name) {
        try {
            Field controller = DesktopCardList.class.getDeclaredField("drag");
            controller.setAccessible(true);
            Object drag = controller.get(list);
            Field field = DesktopCardDrag.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(drag);
            return "snapshot".equals(name) ? value == null ? "null" : "present" : value;
        } catch (ReflectiveOperationException failure) {
            return failure.getClass().getSimpleName();
        }
    }

    public String describe() {
        return "events=" + events + " enabled=" + list.isEnabled()
            + " admitted=" + ((DesktopCardList<?>) list).canReorder()
            + " showing=" + list.isShowing() + " focus=" + list.isFocusOwner()
            + " visible=" + list.getVisibleRect() + " selected=" + list.getSelectedIndex()
            + " source=" + field("source") + " pressed=" + field("pressed")
            + " dragging=" + field("dragging") + " snapshot=" + field("snapshot")
            + " pressPoint=" + field("pressedAt") + " pointer=" + field("pointerOnScreen")
            + " listeners=" + Arrays.stream(list.getMouseListeners()).map(value -> value.getClass().getName()).toList();
    }

    @Override
    public void close() {
        list.removeMouseListener(mouse);
        list.removeMouseMotionListener(mouse);
        list.removeFocusListener(focus);
        list.removeComponentListener(geometry);
    }
}

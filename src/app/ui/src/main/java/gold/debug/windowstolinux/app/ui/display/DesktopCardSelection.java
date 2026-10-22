package gold.debug.windowstolinux.app.ui.display;

import java.awt.Point;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.TableModel;

import com.formdev.flatlaf.util.UIScale;

/** Toggles only completed pointer clicks while retaining native selection and drag delegates. / 仅切换已完成的指针点击，保留原选择及拖动委托。 */
final class DesktopCardSelection extends MouseInputAdapter implements AutoCloseable {
    private static final int MODIFIERS = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK
        | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;

    private final JComponent view;

    private final MouseInputListener original;

    private Point pressedAt;

    private Object pressedModel, pressedValue;

    private int pressedRow = -1;

    private boolean deselect, moved, ignoredPress;

    private Object observedModel;

    private final FocusListener focus = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent event) {
            cancel();
        }
    };

    private final KeyListener keys = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) cancel();
        }
    };

    private final ComponentListener geometry = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            cancel();
        }
    };

    private final ListDataListener listChanges = new ListDataListener() {
        public void intervalAdded(ListDataEvent event) {
            cancel();
        }

        public void intervalRemoved(ListDataEvent event) {
            cancel();
        }

        public void contentsChanged(ListDataEvent event) {
            cancel();
        }
    };

    private final TableModelListener tableChanges = event -> cancel();

    private final PropertyChangeListener properties = event -> {
        switch (event.getPropertyName()) {
            case "model" -> {
                cancel();
                observeModel();
            }
            case "enabled", "selectionModel", "rowSorter", "name", "List.isFileList", DesktopCardSupport.CARDS ->
                cancel();
            default -> {
            }
        }
    };

    DesktopCardSelection(JComponent view, MouseInputListener original) {
        this.view = view;
        this.original = original;
    }

    void install() {
        view.addFocusListener(focus);
        view.addKeyListener(keys);
        view.addComponentListener(geometry);
        view.addPropertyChangeListener(properties);
        observeModel();
    }

    private Object model() {
        return view instanceof JList<?> list ? list.getModel() : ((JTable) view).getModel();
    }

    private Object value(int row) {
        return view instanceof JList<?> list ? list.getModel().getElementAt(row) : ((JTable) view).convertRowIndexToModel(row);
    }

    private boolean onlySelected(int row) {
        if (view instanceof JList<?> list) return list.getSelectedIndices().length == 1 && list.isSelectedIndex(row);
        var table = (JTable) view;
        return table.getSelectedRowCount() == 1 && table.isRowSelected(row);
    }

    private void observeModel() {
        unobserveModel();
        observedModel = model();
        if (observedModel instanceof ListModel<?> list) list.addListDataListener(listChanges);
        else if (observedModel instanceof TableModel table) table.addTableModelListener(tableChanges);
    }

    private void unobserveModel() {
        if (observedModel instanceof ListModel<?> list) list.removeListDataListener(listChanges);
        else if (observedModel instanceof TableModel table) table.removeTableModelListener(tableChanges);
        observedModel = null;
    }

    private void cancel() {
        pressedRow = -1;
        pressedAt = null;
        pressedModel = pressedValue = null;
        deselect = moved = false;
    }

    @Override
    public void mousePressed(MouseEvent event) {
        cancel();
        ignoredPress = false;
        if (view.isEnabled() && DesktopCardSupport.isCardView(view) && SwingUtilities.isLeftMouseButton(event)
            && (event.getModifiersEx() & MODIFIERS) == 0) {
            int row = DesktopCardSupport.rowAt(view, event.getPoint());
            if (row < 0) {
                ignoredPress = true;
                return;
            }
            pressedRow = row;
            pressedAt = event.getPoint();
            pressedModel = model();
            pressedValue = value(row);
            deselect = onlySelected(row);
        }
        original.mousePressed(event);
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        if (pressedAt != null && pressedAt.distance(event.getPoint()) >= UIScale.scale(5)) moved = true;
        if (!ignoredPress && !Boolean.TRUE.equals(view.getClientProperty("desktop.cardDragActive")))
            original.mouseDragged(event);
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        int row = pressedRow;
        Object model = pressedModel, value = pressedValue;
        boolean clear = deselect && !moved && row >= 0 && SwingUtilities.isLeftMouseButton(event)
            && (event.getModifiersEx() & MODIFIERS) == 0 && view.isEnabled() && DesktopCardSupport.isCardView(view)
            && DesktopCardSupport.rowAt(view, event.getPoint()) == row && model() == model
            && Objects.equals(value(row), value) && onlySelected(row)
            && !Boolean.TRUE.equals(view.getClientProperty("desktop.cardDragActive"));
        boolean ignored = ignoredPress;
        ignoredPress = false;
        try {
            if (ignored) return;
            original.mouseReleased(event);
            if (clear && pressedRow == row && view.isEnabled() && model() == model
                && DesktopCardSupport.rowAt(view, event.getPoint()) == row
                && Objects.equals(value(row), value) && onlySelected(row)) {
                if (view instanceof JList<?> list) list.clearSelection();
                else ((JTable) view).clearSelection();
            }
        } finally {
            cancel();
        }
    }

    @Override
    public void mouseClicked(MouseEvent event) {
        original.mouseClicked(event);
    }

    @Override
    public void mouseEntered(MouseEvent event) {
        original.mouseEntered(event);
    }

    @Override
    public void mouseExited(MouseEvent event) {
        original.mouseExited(event);
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        original.mouseMoved(event);
    }

    @Override
    public void close() {
        cancel();
        ignoredPress = false;
        unobserveModel();
        view.removeFocusListener(focus);
        view.removeKeyListener(keys);
        view.removeComponentListener(geometry);
        view.removePropertyChangeListener(properties);
    }
}

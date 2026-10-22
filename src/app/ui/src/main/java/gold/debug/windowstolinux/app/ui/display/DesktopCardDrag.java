package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.TableModel;

import com.formdev.flatlaf.util.UIScale;

/** One handle gesture, preview and animation implementation for host card inventories. / 宿主卡片清单共用的手柄手势、预览及动画实现。 */
public final class DesktopCardDrag<E> implements AutoCloseable {
    public interface Rows<E> {
        JComponent view();

        List<E> values();

        Rectangle bounds(int row);

        Component render(E value, int row, boolean selected);

        boolean selected(int row);

        void selectDragged(int row);
    }

    private final Rows<E> rows;

    private final JComponent view;

    private Consumer<List<E>> reordered;

    private BooleanSupplier admitted = () -> false;

    private List<E> snapshot;

    private double[] positions, velocities;

    private final Map<Integer, BufferedImage> images = new HashMap<>();

    private final CellRendererPane painting = new CellRendererPane();

    private final DesktopMotion motion;

    private int source = -1, destination, grab;

    private Point pressedAt, pointerOnScreen;

    private boolean pressed, dragging, returning, closed;

    private Object observedModel;

    private RowSorter<?> observedSorter;

    private final RowSorterListener sorting = event -> clear();

    private final ListDataListener listChanges = new ListDataListener() {
        public void intervalAdded(ListDataEvent event) {
            clear();
        }

        public void intervalRemoved(ListDataEvent event) {
            clear();
        }

        public void contentsChanged(ListDataEvent event) {
            clear();
        }
    };

    private final TableModelListener tableChanges = event -> clear();

    private final PropertyChangeListener properties = this::propertyChanged;

    private void propertyChanged(PropertyChangeEvent event) {
        switch (event.getPropertyName()) {
            case "model" -> {
                clear();
                observeModel();
            }
            case "rowSorter" -> {
                clear();
                observeSorter();
            }
            case "UI", DesktopListCard.DRAGGABLE -> clear();
            case "enabled" -> {
                if (!view.isEnabled()) cancelInteraction();
            }
            default -> {
            }
        }
    }

    private final FocusListener focus = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent event) {
            if (pressed) cancelInteraction();
        }
    };

    private final ComponentListener geometry = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            clear();
        }
    };

    private final HierarchyListener lifetime = this::hierarchyChanged;

    private void hierarchyChanged(HierarchyEvent event) {
        if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && !view.isDisplayable()) clear();
    }

    private final KeyListener keys = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) cancelInteraction();
        }
    };

    private final MouseInputAdapter mouse = new MouseInputAdapter() {
        @Override
        public void mousePressed(MouseEvent event) {
            int modifiers = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;
            if (!SwingUtilities.isLeftMouseButton(event) || (event.getModifiersEx() & modifiers) != 0 || !canReorder())
                return;
            int index = DesktopCardSupport.rowAt(view, event.getPoint());
            if (index < 0 || !handleBounds(index).contains(event.getPoint())) return;
            clear();
            pressed = true;
            pressedAt = event.getPoint();
            pointerOnScreen = event.getLocationOnScreen();
            source = destination = index;
            grab = event.getY() - rows.bounds(index).y;
            view.requestFocusInWindow();
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (!pressed) return;
            if (!canReorder()) {
                cancelInteraction();
                return;
            }
            pointerOnScreen = event.getLocationOnScreen();
            if (!dragging && pressedAt.distance(event.getPoint()) < UIScale.scale(5)) return;
            if (!dragging) begin();
            if (!dragging || snapshot == null) return;
            movePointer(event.getPoint());
            motion.start();
            view.repaint();
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            if (!pressed && !dragging) return;
            boolean accept = dragging && canReorder() && view.getVisibleRect().contains(event.getPoint())
                && rows.values().equals(snapshot);
            pressed = dragging = false;
            view.putClientProperty("desktop.cardDragActive", false);
            if (snapshot == null) return;
            if (!accept || source == destination) {
                cancelInteraction();
                return;
            }
            var requested = new ArrayList<>(snapshot);
            requested.add(destination, requested.remove(source));
            motion.start();
            reordered.accept(List.copyOf(requested));
        }
    };

    public DesktopCardDrag(Rows<E> rows) {
        this.rows = rows;
        view = rows.view();
        motion = new DesktopMotion(this::frame);
        view.add(painting);
        view.addMouseListener(mouse);
        view.addMouseMotionListener(mouse);
        view.addFocusListener(focus);
        view.addComponentListener(geometry);
        view.addHierarchyListener(lifetime);
        view.addKeyListener(keys);
        view.addPropertyChangeListener(properties);
        observeModel();
        observeSorter();
    }

    public void reorder(Consumer<List<E>> reordered, BooleanSupplier admitted) {
        clear();
        this.reordered = reordered;
        this.admitted = admitted;
    }

    public boolean canReorder() {
        return !closed && DesktopCardSupport.isCardView(view) && DesktopListCard.isDraggable(view)
            && reordered != null && view.isEnabled() && admitted.getAsBoolean();
    }

    public Rectangle handleBounds(int index) {
        var values = rows.values();
        if (!DesktopCardSupport.isCardView(view) || !DesktopListCard.isDraggable(view)
            || index < 0 || index >= values.size()) return new Rectangle();
        var bounds = rows.bounds(index);
        if (bounds == null) return new Rectangle();
        var content = rows.render(values.get(index), index, rows.selected(index));
        var handle = DesktopListCard.handleBounds(content, bounds.width, bounds.height);
        handle.translate(bounds.x, bounds.y);
        return handle;
    }

    private void observeModel() {
        unobserveModel();
        observedModel = view instanceof JList<?> list ? list.getModel() : ((JTable) view).getModel();
        if (observedModel instanceof ListModel<?> list) list.addListDataListener(listChanges);
        else ((TableModel) observedModel).addTableModelListener(tableChanges);
    }

    private void unobserveModel() {
        if (observedModel instanceof ListModel<?> list) list.removeListDataListener(listChanges);
        else if (observedModel instanceof TableModel table) table.removeTableModelListener(tableChanges);
        observedModel = null;
    }

    private void observeSorter() {
        if (observedSorter != null) observedSorter.removeRowSorterListener(sorting);
        observedSorter = view instanceof JTable table ? table.getRowSorter() : null;
        if (observedSorter != null) observedSorter.addRowSorterListener(sorting);
    }

    private void begin() {
        snapshot = List.copyOf(rows.values());
        positions = new double[snapshot.size()];
        velocities = new double[snapshot.size()];
        for (int i = 0; i < positions.length; i++) positions[i] = rows.bounds(i).y;
        dragging = true;
        view.putClientProperty("desktop.cardDragActive", true);
        rows.selectDragged(source);
    }

    public void cancelInteraction() {
        pressed = dragging = false;
        view.putClientProperty("desktop.cardDragActive", false);
        if (snapshot == null) return;
        returning = true;
        motion.start();
    }

    public void clear() {
        motion.close();
        snapshot = null;
        pressed = dragging = returning = false;
        images.clear();
        view.putClientProperty("desktop.cardDragActive", false);
        view.repaint();
    }

    private void movePointer(Point pointer) {
        int height = rows.bounds(source).height;
        positions[source] = Math.max(rows.bounds(0).y, Math.min(view.getHeight() - height, pointer.y - grab));
        destination = Math.max(0, Math.min(snapshot.size() - 1,
            (int) ((positions[source] - rows.bounds(0).y + height / 2d) / height)));
    }

    private void frame(double seconds) {
        if (snapshot == null) {
            motion.close();
            return;
        }
        if (dragging) {
            if (!canReorder()) {
                cancelInteraction();
                return;
            }
            Point point = new Point(pointerOnScreen);
            SwingUtilities.convertPointFromScreen(point, view);
            var visible = view.getVisibleRect();
            if (point.x >= visible.x && point.x < visible.x + visible.width) {
                int direction = point.y < visible.y + UIScale.scale(24) ? -1
                    : point.y > visible.y + visible.height - UIScale.scale(24) ? 1 : 0;
                if (direction != 0 && view.getParent() instanceof JViewport viewport) {
                    Point position = viewport.getViewPosition();
                    position.y = Math.max(0, Math.min(view.getHeight() - visible.height, position.y + direction * UIScale.scale(6)));
                    viewport.setViewPosition(position);
                    SwingUtilities.convertPointFromScreen(point = new Point(pointerOnScreen), view);
                }
            }
            movePointer(point);
        }
        boolean settled = true;
        for (int i = 0; i < positions.length; i++) {
            if (dragging && i == source) continue;
            int slot = returning ? i : i == source ? destination
                : source < destination && i > source && i <= destination ? i - 1
                : source > destination && i >= destination && i < source ? i + 1 : i;
            double target = rows.bounds(slot).y;
            var next = DesktopMotion.step(positions[i], velocities[i], target, seconds);
            positions[i] = next.value();
            velocities[i] = next.velocity();
            settled &= Math.abs(next.value() - target) < .1 && Math.abs(next.velocity()) < .5;
        }
        view.repaint();
        if (!dragging && settled) {
            motion.close();
            if (returning) clear();
        }
    }

    /** Paints a preview only; the original data model is never reordered here. / 只绘制预览，绝不在此重排原数据模型。 */
    public boolean paint(Graphics graphics) {
        if (snapshot == null) return false;
        var paint = (Graphics2D) graphics.create();
        try {
            paint.setColor(view.getBackground());
            paint.fillRect(0, 0, view.getWidth(), view.getHeight());
            for (int i = 0; i < snapshot.size(); i++) if (i != source) paintCard(paint, i);
            paintCard(paint, source);
        } finally {
            paint.dispose();
        }
        return true;
    }

    private void paintCard(Graphics2D graphics, int index) {
        var bounds = rows.bounds(index);
        int y = (int) Math.round(positions[index]);
        var clip = graphics.getClipBounds();
        if (clip != null && !clip.intersects(bounds.x, y, bounds.width, bounds.height)) return;
        var image = images.computeIfAbsent(index, key -> {
            var row = rows.render(snapshot.get(key), key, rows.selected(key));
            var bitmap = new BufferedImage(Math.max(1, bounds.width), bounds.height, BufferedImage.TYPE_INT_ARGB);
            var paint = bitmap.createGraphics();
            try {
                painting.paintComponent(paint, row, view, 0, 0, bounds.width, bounds.height, true);
            } finally {
                paint.dispose();
            }
            return bitmap;
        });
        graphics.drawImage(image, bounds.x, y, null);
    }

    @Override
    public void close() {
        if (closed) return;
        clear();
        closed = true;
        unobserveModel();
        if (observedSorter != null) observedSorter.removeRowSorterListener(sorting);
        observedSorter = null;
        view.removeMouseListener(mouse);
        view.removeMouseMotionListener(mouse);
        view.removeFocusListener(focus);
        view.removeComponentListener(geometry);
        view.removeHierarchyListener(lifetime);
        view.removeKeyListener(keys);
        view.removePropertyChangeListener(properties);
        view.remove(painting);
    }
}

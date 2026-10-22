package gold.debug.windowstolinux.app.ui.control;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.display.DesktopMotion;

/** Local card reordering with a floating card and animated neighboring rows. / 浮动卡片及相邻行动画的本地卡片排序。
 * @param <E> original row value type / 原行值类型 */
public final class DesktopCardList<E> extends JList<E> {
    /** Logical row height, original persistence callback and operation gate. / 逻辑行高、原持久化回调及操作门禁。 */
    private final int rowHeight;

    /** Confirmed immutable order callback. / 已确认不可变顺序的回调。 */
    private Consumer<List<E>> reordered;

    /** Current security and operation admission. / 当前安全及操作准入。 */
    private BooleanSupplier admitted = () -> false;

    /** Original immutable drag order and its visual positions. / 原不可变拖动顺序及其视觉位置。 */
    private List<E> snapshot;

    /** Preview row positions and spring velocities. / 预览行位置及弹簧速度。 */
    private double[] positions, velocities;

    /** Renderer snapshots for the current preview only. / 仅供当前预览使用的渲染快照。 */
    private final Map<Integer, BufferedImage> images = new HashMap<>();

    /** Paints original cell renderers into snapshots. / 将原单元格渲染器绘入快照。 */
    private final CellRendererPane painting = new CellRendererPane();

    /** Owned preview clock stopped on model or lifetime changes. / 模型或寿命变化时停止的所属预览时钟。 */
    private DesktopMotion motion;

    /** Original row, candidate destination and pointer grab offset. / 原行、候选目标及指针抓取偏移。 */
    private int source = -1, destination, grab;

    /** Gesture origin and latest screen pointer position. / 手势起点及最新屏幕指针位置。 */
    private Point pressedAt, pointerOnScreen;

    /** Gesture admission, active drag and cancelled-preview return state. / 手势准入、活动拖动及取消预览的归位状态。 */
    private boolean pressed, dragging, returning;

    /** Cancels previews when the original model changes. / 原模型变化时取消预览。 */
    private final ListDataListener changes = new ListDataListener() {
        /** Discards the drag preview after model insertion. / 模型插入后丢弃拖动预览。
         * @param event original event notification / 原事件通知
         */
        public void intervalAdded(ListDataEvent event) {
            clear();
        }

        /** Discards the drag preview after model removal. / 模型删除后丢弃拖动预览。
         * @param event original event notification / 原事件通知
         */
        public void intervalRemoved(ListDataEvent event) {
            clear();
        }

        /** Discards the preview after original model values change. / 原模型值变化后丢弃预览。
         * @param event original event notification / 原事件通知
         */
        public void contentsChanged(ListDataEvent event) {
            clear();
        }
    };

    /** Builds a width-following card list. / 创建随宽度调整的卡片清单。
     * @param model original data model / 原数据模型
     * @param rowHeight logical row height / 逻辑行高 */
    public DesktopCardList(ListModel<E> model, int rowHeight) {
        super(model);
        this.rowHeight = Math.max(56, rowHeight);
        setFixedCellHeight(UIScale.scale(this.rowHeight));
        getModel().addListDataListener(changes);
        add(painting);
        motion = new DesktopMotion(this::frame);
        var mouse = new MouseInputAdapter() {
            /** Captures an admitted row and the pointer grab offset. / 捕获已准入行及指针抓取偏移。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || !canReorder()) return;
                int index = locationToIndex(event.getPoint());
                if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) return;
                clear();
                pressed = true;
                pressedAt = event.getPoint();
                pointerOnScreen = event.getLocationOnScreen();
                source = destination = index;
                grab = event.getY() - getCellBounds(index, index).y;
                setSelectedIndex(index);
                requestFocusInWindow();
            }

            /** Moves the preview after crossing the drag threshold. / 跨过拖动阈值后移动预览。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void mouseDragged(MouseEvent event) {
                if (!pressed || !canReorder()) {
                    cancelInteraction();
                    return;
                }
                pointerOnScreen = event.getLocationOnScreen();
                if (!dragging && pressedAt.distance(event.getPoint()) < UIScale.scale(5)) return;
                if (!dragging) begin();
                setSelectedIndex(source);
                movePointer(event.getPoint());
                motion.start();
                repaint();
            }

            /** Requests the original order write only for an admitted unchanged model. / 仅对已准入且未变的模型请求原排序写入。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void mouseReleased(MouseEvent event) {
                boolean accept = dragging && canReorder() && getVisibleRect().contains(event.getPoint())
                    && values().equals(snapshot);
                pressed = dragging = false;
                putClientProperty("desktop.cardDragActive", false);
                if (snapshot == null) return;
                if (!accept || source == destination) {
                    cancelInteraction();
                    return;
                }
                var requested = new ArrayList<>(snapshot);
                E item = requested.remove(source);
                requested.add(destination, item);
                motion.start();
                reordered.accept(List.copyOf(requested));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancel.card.drag");
        getActionMap().put("cancel.card.drag", new AbstractAction() {
            /** Cancels the unconfirmed card gesture. / 取消未确认卡片手势。
             * @param event original event notification / 原事件通知
             */
            public void actionPerformed(ActionEvent event) {
                cancelInteraction();
            }
        });
        addFocusListener(new FocusAdapter() {
            /** Cancels a gesture leaving the owning list. / 取消离开所属清单的手势。
             * @param event original event notification / 原事件通知
             */
            public void focusLost(FocusEvent event) {
                if (pressed) cancelInteraction();
            }
        });
        addComponentListener(new ComponentAdapter() {
            /** Invalidates renderer snapshots and the active gesture. / 作废渲染快照及活动手势。
             * @param event original event notification / 原事件通知
             */
            public void componentResized(ComponentEvent event) {
                images.clear();
                if (pressed) cancelInteraction();
            }
        });
    }

    /** Attaches only the existing order operation; membership never transfers across lists. / 只接入既有排序操作，成员不会跨列表转移。
     * @param reordered original order callback / 原排序回调
     * @param admitted current lock and operation gate / 当前锁定及操作门禁 */
    public void reorder(Consumer<List<E>> reordered, BooleanSupplier admitted) {
        this.reordered = reordered;
        this.admitted = admitted;
    }

    /** Returns current gesture admission. / 返回当前手势准入状态。
     * @return whether this list accepts order changes / 此清单是否接收排序变化 */
    public boolean canReorder() {
        return reordered != null && isEnabled() && admitted.getAsBoolean();
    }

    /** Cancels an in-flight gesture or unconfirmed preview. / 取消执行中的手势或未确认预览。 */
    public void cancelInteraction() {
        pressed = dragging = false;
        putClientProperty("desktop.cardDragActive", false);
        if (snapshot == null) return;
        returning = true;
        motion.start();
    }

    /** Captures the original model without changing saved order. / 捕获原模型，不改变已保存顺序。 */
    private void begin() {
        snapshot = values();
        positions = new double[snapshot.size()];
        velocities = new double[snapshot.size()];
        for (int i = 0; i < positions.length; i++) positions[i] = i * getFixedCellHeight();
        dragging = true;
        putClientProperty("desktop.cardDragActive", true);
    }

    /** Returns a stable snapshot of the current original model. / 返回当前原模型的稳定快照。
     * @return immutable current values / 不可变当前值 */
    private List<E> values() {
        var values = new ArrayList<E>();
        for (int i = 0; i < getModel().getSize(); i++) values.add(getModel().getElementAt(i));
        return List.copyOf(values);
    }

    /** Follows the pointer and reserves a destination slot. / 跟随指针并预留目标位置。
     * @param pointer pointer in list coordinates / 列表坐标中的指针 */
    private void movePointer(Point pointer) {
        int height = getFixedCellHeight();
        positions[source] = Math.max(0, Math.min(getHeight() - height, pointer.y - grab));
        destination = Math.max(0, Math.min(snapshot.size() - 1, (int) ((positions[source] + height / 2d) / height)));
    }

    /** Advances neighbors and the drop settling position on the shared clock. / 在共用时钟推进邻居及放置归位位置。
     * @param seconds real elapsed time / 实际经过时间 */
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
            SwingUtilities.convertPointFromScreen(point, this);
            var visible = getVisibleRect();
            if (point.x >= visible.x && point.x < visible.x + visible.width) {
                int direction = point.y < visible.y + UIScale.scale(24) ? -1
                    : point.y > visible.y + visible.height - UIScale.scale(24) ? 1 : 0;
                if (direction != 0 && getParent() instanceof JViewport viewport) {
                    Point position = viewport.getViewPosition();
                    position.y = Math.max(0, Math.min(getHeight() - visible.height, position.y + direction * UIScale.scale(6)));
                    viewport.setViewPosition(position);
                    SwingUtilities.convertPointFromScreen(point = new Point(pointerOnScreen), this);
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
            var next = DesktopMotion.step(positions[i], velocities[i], slot * getFixedCellHeight(), seconds);
            positions[i] = next.value();
            velocities[i] = next.velocity();
            settled &= Math.abs(next.value() - slot * getFixedCellHeight()) < .1 && Math.abs(next.velocity()) < .5;
        }
        repaint();
        if (!dragging && settled) {
            motion.close();
            if (returning) clear();
        }
    }

    /** Paints cached actual card renderers, with the dragged card above its neighbors. / 绘制缓存的实际卡片渲染器，拖动卡片位于邻居上方。
     * @param graphics current paint context / 当前绘制上下文 */
    @Override
    protected void paintComponent(Graphics graphics) {
        if (snapshot == null) {
            super.paintComponent(graphics);
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            for (int i = 0; i < snapshot.size(); i++) if (i != source) paintCard(g, i);
            paintCard(g, source);
        } finally {
            g.dispose();
        }
    }

    /** Draws only cards touching the current paint clip. / 仅绘制触及当前绘制裁剪区的卡片。
     * @param graphics paint context / 绘制上下文
     * @param index original card index / 原卡片索引 */
    private void paintCard(Graphics2D graphics, int index) {
        int y = (int) Math.round(positions[index]), height = getFixedCellHeight();
        var clip = graphics.getClipBounds();
        if (clip != null && !clip.intersects(0, y, getWidth(), height)) return;
        var image = images.computeIfAbsent(index, key -> {
            var row = getCellRenderer().getListCellRendererComponent(this, snapshot.get(key), key, key == source, false);
            var bitmap = new BufferedImage(Math.max(1, getWidth()), height, BufferedImage.TYPE_INT_ARGB);
            var paint = bitmap.createGraphics();
            try {
                painting.paintComponent(paint, row, this, 0, 0, getWidth(), height, true);
            } finally {
                paint.dispose();
            }
            return bitmap;
        });
        graphics.drawImage(image, 0, y, null);
    }

    /** Clears snapshots on original model, theme or lifetime changes. / 原模型、主题或寿命变化时清理快照。 */
    private void clear() {
        if (motion != null) motion.close();
        snapshot = null;
        pressed = dragging = returning = false;
        if (images != null) images.clear();
        putClientProperty("desktop.cardDragActive", false);
        repaint();
    }

    /** Preserves model ownership while invalidating old gestures. / 保留模型归属，同时作废旧手势。
     * @param model original replacement model / 原替换模型 */
    @Override
    public void setModel(ListModel<E> model) {
        if (changes != null) {
            getModel().removeListDataListener(changes);
            clear();
        }
        super.setModel(model);
        if (changes != null) model.addListDataListener(changes);
    }

    /** Rejects gestures crossing a disabled state. / 拒绝跨禁用状态的手势。
     * @param enabled current control state / 当前控件状态 */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if ((!enabled && pressed) || (enabled && snapshot != null && !pressed)) cancelInteraction();
    }

    /** Rescales actual rows and discards obsolete renderer images. / 重新缩放实际行，并丢弃旧渲染图。 */
    @Override
    public void updateUI() {
        clear();
        super.updateUI();
        if (rowHeight > 0) setFixedCellHeight(UIScale.scale(rowHeight));
    }

    /** Follows its viewport width. / 跟随视口宽度。
     * @return always true / 始终为真 */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** Stops animation when the owning view is removed. / 所属视图移除时停止动画。 */
    @Override
    public void removeNotify() {
        clear();
        super.removeNotify();
    }
}

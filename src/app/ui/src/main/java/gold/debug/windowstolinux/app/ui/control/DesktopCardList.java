package gold.debug.windowstolinux.app.ui.control;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JList;
import javax.swing.JComponent;
import javax.swing.ListModel;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.display.DesktopCardDrag;
import gold.debug.windowstolinux.app.ui.display.DesktopListCard;

/** Width-following inventory using the shared handle drag engine. / 随宽度变化并使用共用手柄拖动引擎的清单。 */
public final class DesktopCardList<E> extends JList<E> {
    private final int rowHeight;

    private final DesktopCardDrag<E> drag;

    public DesktopCardList(ListModel<E> model, int rowHeight) {
        super(model);
        this.rowHeight = Math.max(56, rowHeight);
        setFixedCellHeight(UIScale.scale(this.rowHeight));
        drag = new DesktopCardDrag<>(new DesktopCardDrag.Rows<>() {
            public JComponent view() {
                return DesktopCardList.this;
            }

            public List<E> values() {
                var values = new ArrayList<E>();
                for (int i = 0; i < getModel().getSize(); i++) values.add(getModel().getElementAt(i));
                return List.copyOf(values);
            }

            public Rectangle bounds(int row) {
                return getCellBounds(row, row);
            }

            public Component render(E value, int row, boolean selected) {
                var content = getCellRenderer().getListCellRendererComponent(DesktopCardList.this, value, row, selected, false);
                return DesktopListCard.row(content, selected, DesktopListCard.isDraggable(DesktopCardList.this),
                    DesktopCardList.this);
            }

            public boolean selected(int row) {
                return isSelectedIndex(row);
            }

            public void selectDragged(int row) {
                setSelectedIndex(row);
            }
        });
    }

    public void reorder(Consumer<List<E>> reordered, BooleanSupplier admitted) {
        drag.reorder(reordered, admitted);
    }

    public boolean canReorder() {
        return drag != null && drag.canReorder();
    }

    public Rectangle handleBounds(int row) {
        return drag == null ? new Rectangle() : drag.handleBounds(row);
    }

    public void cancelInteraction() {
        if (drag != null) drag.cancelInteraction();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (drag == null || !drag.paint(graphics)) super.paintComponent(graphics);
    }

    @Override
    public void updateUI() {
        if (drag != null) drag.clear();
        super.updateUI();
        if (rowHeight > 0) setFixedCellHeight(UIScale.scale(rowHeight));
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public void removeNotify() {
        if (drag != null) drag.clear();
        super.removeNotify();
    }
}

package gold.debug.windowstolinux.app.ui.control;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JTable;

import gold.debug.windowstolinux.app.ui.display.DesktopCardDrag;
import gold.debug.windowstolinux.app.ui.display.DesktopListCard;

/** Preserves table models and selection while adapting identity order to the shared card engine. / 保留表格模型和选择，将身份顺序接入共用卡片引擎。 */
public final class DesktopCardTableOrder<E> implements AutoCloseable {
    public static final String PROPERTY = "desktop.inventory.tableOrder";

    private final JTable table;

    private final DesktopCardDrag<E> drag;

    public DesktopCardTableOrder(JTable table, Supplier<List<E>> currentOrder,
                                 Consumer<List<E>> reordered, BooleanSupplier admitted) {
        if (table.getClientProperty(PROPERTY) != null) throw new IllegalStateException("Table order is already bound");
        this.table = table;
        drag = new DesktopCardDrag<>(new DesktopCardDrag.Rows<>() {
            public JComponent view() {
                return table;
            }

            public List<E> values() {
                return List.copyOf(currentOrder.get());
            }

            public Rectangle bounds(int row) {
                if (row < 0 || row >= table.getRowCount() || table.getColumnCount() == 0) return null;
                var bounds = table.getCellRect(row, 0, true);
                bounds.x = 0;
                bounds.width = table.getWidth();
                return bounds;
            }

            public Component render(E value, int row, boolean selected) {
                var content = table.getCellRenderer(row, 0).getTableCellRendererComponent(table,
                    table.getValueAt(row, 0), selected, false, row, 0);
                return DesktopListCard.row(content, selected, DesktopListCard.isDraggable(table), table);
            }

            public boolean selected(int row) {
                return table.isRowSelected(row);
            }

            public void selectDragged(int row) {
            }
        });
        drag.reorder(reordered, () -> currentOrder.get().size() == table.getRowCount() && admitted.getAsBoolean());
        table.putClientProperty(PROPERTY, this);
    }

    public Rectangle handleBounds(int row) {
        return drag.handleBounds(row);
    }

    public boolean canReorder() {
        return drag.canReorder();
    }

    public void cancelInteraction() {
        drag.cancelInteraction();
    }

    public boolean paint(Graphics graphics) {
        return drag.paint(graphics);
    }

    @Override
    public void close() {
        drag.close();
        if (table.getClientProperty(PROPERTY) == this) table.putClientProperty(PROPERTY, null);
    }
}

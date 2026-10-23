package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import javax.swing.*;
import javax.swing.event.MouseInputListener;
import javax.swing.plaf.ComponentUI;
import javax.swing.table.TableCellRenderer;

import gold.debug.windowstolinux.app.ui.control.DesktopCardTableOrder;

import com.formdev.flatlaf.ui.FlatTableUI;
import com.formdev.flatlaf.util.UIScale;

/** Adds row selection markers without replacing table columns, editors or drag handling. / 添加行选择标记，不替换表格列、编辑器或拖放。 */
public final class DesktopTableUi extends FlatTableUI {
    private DesktopCardSelection selection;

    /** Creates the host table delegate. / 创建宿主表格绘制器。
     * @param component original table / 原表格
     * @return owned delegate / 所属绘制器 */
    public static ComponentUI createUI(JComponent component) {
        return new DesktopTableUi();
    }

    @Override
    public void installUI(JComponent component) {
        super.installUI(component);
        selection.install();
        decorate(table);
    }

    @Override
    protected MouseInputListener createMouseInputListener() {
        selection = new DesktopCardSelection(table, super.createMouseInputListener());
        return selection;
    }

    @Override
    public void uninstallUI(JComponent component) {
        if (selection != null) selection.close();
        for (var column : java.util.Collections.list(table.getColumnModel().getColumns())) {
            if (column.getCellRenderer() instanceof Renderer renderer) column.setCellRenderer(renderer.original);
        }
        super.uninstallUI(component);
        selection = null;
    }

    /** Preserves explicit/default renderers and keeps card wrapping idempotent. / 保留显式或默认渲染器，卡片包装保持幂等。 */
    public static void decorate(JTable table) {
        if (!DesktopCardSupport.isCardView(table)) return;
        for (var column : java.util.Collections.list(table.getColumnModel().getColumns())) {
            if (!(column.getCellRenderer() instanceof Renderer))
                column.setCellRenderer(new Renderer(column.getCellRenderer()));
        }
    }

    private record Renderer(TableCellRenderer original) implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            var renderer = original == null ? table.getDefaultRenderer(table.getColumnClass(column)) : original;
            Component content = renderer.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (!DesktopCardSupport.isCardView(table)) return content;
            var card = DesktopListCard.row(content, selected, DesktopListCard.isDraggable(table), table);
            card.setSize(table.getCellRect(row, column, false).getSize());
            return card;
        }
    }

    /** Paints all selected-row markers over the native table. / 在原生表格上绘制所有选中行标记。
     * @param graphics current context / 当前上下文
     * @param component original table / 原表格 */
    @Override
    public void paint(Graphics graphics, JComponent component) {
        decorate(table);
        if (table.getClientProperty(DesktopCardTableOrder.PROPERTY) instanceof DesktopCardTableOrder<?> order
            && order.paint(graphics)) return;
        super.paint(graphics, component);
        if (Boolean.TRUE.equals(table.getClientProperty("desktop.inventory.cards")) || table.getColumnCount() == 0
            || SwingUtilities.getAncestorOfClass(JFileChooser.class, table) != null) return;
        var g = (Graphics2D) graphics.create();
        try {
            g.setColor(UIManager.getColor("Component.focusColor"));
            var clip = g.getClipBounds();
            for (int row : table.getSelectedRows()) {
                var bounds = table.getCellRect(row, 0, true);
                if (clip != null && !clip.intersects(bounds)) continue;
                int inset = UIScale.scale(3), width = UIScale.scale(4);
                g.fillRoundRect(bounds.x, bounds.y + inset, width, Math.max(0, bounds.height - 2 * inset), width, width);
            }
        } finally {
            g.dispose();
        }
    }
}

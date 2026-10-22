package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatTableUI;
import com.formdev.flatlaf.util.UIScale;

/** Adds row selection markers without replacing table columns, editors or drag handling. / 添加行选择标记，不替换表格列、编辑器或拖放。 */
public final class DesktopTableUi extends FlatTableUI {
    /** Creates the host table delegate. / 创建宿主表格绘制器。
     * @param component original table / 原表格
     * @return owned delegate / 所属绘制器 */
    public static ComponentUI createUI(JComponent component) {
        return new DesktopTableUi();
    }

    /** Paints all selected-row markers over the native table. / 在原生表格上绘制所有选中行标记。
     * @param graphics current context / 当前上下文
     * @param component original table / 原表格 */
    @Override
    public void paint(Graphics graphics, JComponent component) {
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

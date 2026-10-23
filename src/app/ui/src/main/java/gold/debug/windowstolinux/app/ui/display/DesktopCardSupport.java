package gold.debug.windowstolinux.app.ui.display;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;

/** Shared admission and geometry for object cards. / 对象卡片共用的适用范围及几何规则。 */
final class DesktopCardSupport {
    static final String CARDS = "desktop.inventory.cards";

    static final String SCROLLBAR = "desktop.inventory.scrollbar";

    static final int SCROLLBAR_WIDTH = 12;

    static final int THUMB_WIDTH = 6;

    static final int VIEWPORT_GAP = 12;

    private DesktopCardSupport() {
    }

    /** Keeps system pickers and combo popups outside inventory interaction. / 系统选择器及下拉弹层不使用清单交互。 */
    static boolean isCardList(JList<?> list) {
        return !"ComboBox.list".equals(list.getName())
            && !Boolean.TRUE.equals(list.getClientProperty("List.isFileList"))
            && SwingUtilities.getAncestorOfClass(JFileChooser.class, list) == null;
    }

    /** Recognizes only direct inventory views, never their enclosing page. / 仅识别直接清单视图，不识别外层页面。 */
    static boolean isCardView(Component view) {
        if (view instanceof JList<?> list) return isCardList(list);
        return view instanceof JTable table && Boolean.TRUE.equals(table.getClientProperty(CARDS))
            && SwingUtilities.getAncestorOfClass(JFileChooser.class, table) == null;
    }

    /** Excludes the inter-card spacing and unused viewport area. / 排除卡片间距及视口空白区域。 */
    static int rowAt(JComponent view, Point point) {
        int row;
        Rectangle bounds;
        if (view instanceof JList<?> list) {
            row = list.locationToIndex(point);
            bounds = row < 0 ? null : list.getCellBounds(row, row);
        } else if (view instanceof JTable table) {
            row = table.rowAtPoint(point);
            bounds = row < 0 || table.getColumnCount() == 0 ? null : table.getCellRect(row, 0, true);
            if (bounds != null) bounds.width = table.getWidth();
        } else return -1;
        if (bounds == null || !bounds.contains(point)) return -1;
        int gap = UIScale.scale(9);
        return point.y >= bounds.y + gap && point.y < bounds.y + bounds.height - gap ? row : -1;
    }

    static boolean onHandle(JComponent view, int row, Point point) {
        if (!DesktopListCard.isDraggable(view) || row < 0) return false;
        Component content;
        Rectangle bounds;
        if (view instanceof JList<?> list) {
            bounds = list.getCellBounds(row, row);
            content = listRow(list, row);
        } else {
            var table = (JTable) view;
            bounds = table.getCellRect(row, 0, true);
            bounds.x = 0;
            bounds.width = table.getWidth();
            content = table.getCellRenderer(row, 0).getTableCellRendererComponent(table,
                table.getValueAt(row, 0), table.isRowSelected(row), false, row, 0);
        }
        content = DesktopListCard.row(content, view instanceof JList<?> list ? list.isSelectedIndex(row)
            : ((JTable) view).isRowSelected(row), true);
        var handle = DesktopListCard.handleBounds(content, bounds.width, bounds.height);
        handle.translate(bounds.x, bounds.y);
        return handle.contains(point);
    }

    private static <E> Component listRow(JList<E> list, int row) {
        return list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(row),
            row, list.isSelectedIndex(row), false);
    }
}

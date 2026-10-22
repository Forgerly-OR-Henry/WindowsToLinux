package gold.debug.windowstolinux.app.ui.display;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesktopCardHandleTest {
    @Test
    void tableHandleUsesOwnerHintWhileTheCardBodyRetainsItsMetadataTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var table = new JTable(1, 1);
            table.setRowHeight(UIScale.scale(90));
            table.setIntercellSpacing(new java.awt.Dimension(0, 0));
            table.setSize(400, UIScale.scale(90));
            table.doLayout();
            table.setToolTipText("当前筛选状态不能排序");
            table.getColumnModel().getColumn(0).setCellRenderer((view, value, selected, focused, row, column) -> {
                var label = new JLabel("服务器");
                label.setToolTipText("服务器原始信息");
                return label;
            });
            var parts = new DesktopPartFactory(ThemePalette.light());
            parts.cardList(table, true);
            var rendered = table.getCellRenderer(0, 0).getTableCellRendererComponent(table, null, false, false, 0, 0);
            var handle = DesktopListCard.handleBounds(rendered, table.getWidth(), table.getRowHeight());
            assertEquals("当前筛选状态不能排序", tip(table, center(handle)));
            assertEquals("服务器原始信息", tip(table, new Point(200, table.getRowHeight() / 2)));
            parts.cardList(table, false);
            assertEquals("服务器原始信息", tip(table, center(handle)));
        });
    }

    @Test
    void theOwnerFlagAloneControlsTheHandleAndRepeatedWrappingRemovesItsSlot() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                for (int scale : new int[]{100, 150}) {
                    DesktopThemeService.install(theme, scale);
                    var content = new JLabel("原始内容");
                    var row = DesktopListCard.row(content, false, false);
                    assertTrue(DesktopListCard.handleBounds(row, 500, UIScale.scale(90)).isEmpty());
                    int left = SwingUtilities.convertPoint(content, 0, 0, row).x;
                    assertSame(row, DesktopListCard.row(row, true, true));
                    var handle = DesktopListCard.handleBounds(row, 500, UIScale.scale(90));
                    assertFalse(handle.isEmpty());
                    assertEquals(UIScale.scale(16), handle.width);
                    assertEquals(UIScale.scale(16), handle.height);
                    assertTrue(SwingUtilities.convertPoint(content, 0, 0, row).x > left);
                    assertSame(row, DesktopListCard.row(row, false, false));
                    assertTrue(DesktopListCard.handleBounds(row, 500, UIScale.scale(90)).isEmpty());
                    assertEquals(left, SwingUtilities.convertPoint(content, 0, 0, row).x);

                    var list = list();
                    list.reorder(ignored -> {
                    }, () -> true);
                    assertFalse(list.canReorder(), "Binding persistence must not opt an owner into dragging");
                    assertTrue(list.handleBounds(0).isEmpty());
                    var parts = new DesktopPartFactory(ThemePalette.light());
                    parts.cardList(list, true);
                    assertTrue(list.canReorder());
                    assertFalse(list.handleBounds(0).isEmpty());
                    parts.cardList(list, false);
                    assertFalse(list.canReorder());
                    assertTrue(list.handleBounds(0).isEmpty());
                }
            }
        });
    }

    @Test
    void shortHandleClicksPreserveSelectionAndBodyDragsNeverSubmitAnOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var list = list();
            var writes = new AtomicInteger();
            var parts = new DesktopPartFactory(ThemePalette.light());
            parts.cardList(list, true);
            list.reorder(ignored -> writes.incrementAndGet(), () -> true);
            for (int pass = 0; pass < 3; pass++) {
                SwingUtilities.updateComponentTreeUI(list);
                list.clearSelection();
                click(list, center(list.handleBounds(0)));
                assertEquals(-1, list.getSelectedIndex());
                list.setSelectedIndex(1);
                click(list, center(list.handleBounds(0)));
                assertEquals(1, list.getSelectedIndex());
                var body = new Point(200, list.getCellBounds(0, 0).height / 2);
                click(list, body);
                assertEquals(0, list.getSelectedIndex());
                click(list, body);
                assertEquals(-1, list.getSelectedIndex());
                dispatch(list, MouseEvent.MOUSE_PRESSED, body, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                var end = new Point(200, list.getCellBounds(1, 1).y + list.getCellBounds(1, 1).height / 2);
                dispatch(list, MouseEvent.MOUSE_DRAGGED, end, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                dispatch(list, MouseEvent.MOUSE_RELEASED, end, 0, MouseEvent.BUTTON1);
                assertEquals(0, writes.get());
                assertNotEquals(Boolean.TRUE, list.getClientProperty("desktop.cardDragActive"));
                assertEquals("甲", list.getModel().getElementAt(0));
            }
        });
    }

    @Test
    void outputAreasUseTheCurrentUiFamilyAtTwelveLogicalPoints() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int scale : new int[]{100, 150}) {
                DesktopThemeService.install(ThemeMode.LIGHT, scale);
                var output = DesktopPartFactory.outputArea();
                assertEquals(UIManager.getFont("defaultFont").getFamily(), output.getFont().getFamily());
                assertEquals(UIScale.scale(12), output.getFont().getSize());
                assertFalse(output.getFont().isBold());
            }
        });
    }

    private static DesktopCardList<String> list() {
        var model = new DefaultListModel<String>();
        model.addElement("甲");
        model.addElement("乙");
        var list = new DesktopCardList<>(model, 90);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSize(400, UIScale.scale(200));
        return list;
    }

    private static Point center(Rectangle bounds) {
        assertFalse(bounds.isEmpty());
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private static void click(JComponent view, Point point) {
        dispatch(view, MouseEvent.MOUSE_PRESSED, point, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        dispatch(view, MouseEvent.MOUSE_RELEASED, point, 0, MouseEvent.BUTTON1);
    }

    private static void dispatch(JComponent view, int id, Point point, int modifiers, int button) {
        view.dispatchEvent(new MouseEvent(view, id, System.currentTimeMillis(), modifiers, point.x, point.y,
            point.x, point.y, 1, false, button));
    }

    private static String tip(JTable table, Point point) {
        return table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
            point.x, point.y, point.x, point.y, 0, false, MouseEvent.NOBUTTON));
    }
}

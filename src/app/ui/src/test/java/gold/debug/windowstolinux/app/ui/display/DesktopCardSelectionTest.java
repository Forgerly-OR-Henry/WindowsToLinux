package gold.debug.windowstolinux.app.ui.display;

import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesktopCardSelectionTest {
    @Test
    void admittedReorderingCannotStartFromTheInterCardGapAfterUiReinstallation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var list = (DesktopCardList<String>) list(true);
            var writes = new AtomicInteger();
            list.reorder(ignored -> writes.incrementAndGet(), () -> true);
            for (int scale : new int[]{100, 150, 100}) {
                DesktopThemeService.install(scale == 150 ? ThemeMode.DARK : ThemeMode.LIGHT, scale);
                SwingUtilities.updateComponentTreeUI(list);
                list.setSelectedIndex(1);
                var first = list.getCellBounds(0, 0);
                var second = list.getCellBounds(1, 1);
                for (int gap : new int[]{first.y + UIScale.scale(2), first.y + first.height - UIScale.scale(2)}) {
                    press(list, new Point(80, gap), 0);
                    var end = new Point(80, second.y + second.height / 2);
                    dispatch(list, MouseEvent.MOUSE_DRAGGED, end, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                    release(list, end, 0);
                    assertEquals(0, writes.get(), "Card spacing must never initiate an order write");
                    assertNotEquals(Boolean.TRUE, list.getClientProperty("desktop.cardDragActive"));
                    assertEquals(1, list.getSelectedIndex());
                }
            }
        });
    }

    @Test
    void admittedReorderingKeepsFirstAndSecondClicksCorrectAfterUiReinstallation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var list = (DesktopCardList<String>) list(true);
            var writes = new AtomicInteger();
            list.reorder(ignored -> writes.incrementAndGet(), () -> true);
            for (int scale : new int[]{100, 150, 100}) {
                DesktopThemeService.install(scale == 150 ? ThemeMode.DARK : ThemeMode.LIGHT, scale);
                SwingUtilities.updateComponentTreeUI(list);
                assertTrue(list.canReorder());
                list.clearSelection();
                click(list, 0, 0);
                assertEquals(0, list.getSelectedIndex());
                click(list, 0, 0);
                assertEquals(-1, list.getSelectedIndex());
                click(list, 1, 0);
                assertEquals(1, list.getSelectedIndex());
                assertEquals(0, writes.get(), "Pointer clicks must never submit an order write");
            }
        });
    }

    @Test
    void listsAndCardTablesToggleCompletedClicksWithoutChangingProgrammaticOrKeyboardSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            for (var factory : factories()) {
                var view = factory.get();
                click(view, 0, 0);
                assertEquals(0, selected(view));
                click(view, 0, 0);
                assertEquals(-1, selected(view));
                click(view, 1, 0);
                assertEquals(1, selected(view));
                select(view, 1);
                select(view, 1);
                assertEquals(1, selected(view));
                var previous = view.getActionMap().get("selectPreviousRow");
                assertNotNull(previous);
                previous.actionPerformed(new ActionEvent(view, ActionEvent.ACTION_PERFORMED, "selectPreviousRow"));
                assertEquals(0, selected(view));
                click(view, 1, 0);
                assertEquals(1, selected(view));
                clickAt(view, new Point(80, 280), 0);
                assertEquals(1, selected(view), "The empty viewport must not select the nearest card");
                view.setEnabled(false);
                click(view, 0, 0);
                assertEquals(1, selected(view));
            }
        });
    }

    @Test
    void dragEscapeAndModelChangesCancelOnlyThePendingPointerToggle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            for (var factory : factories()) {
                var view = factory.get();
                select(view, 0);
                var center = point(0);
                press(view, center, 0);
                dispatch(view, MouseEvent.MOUSE_DRAGGED, new Point(center.x + 20, center.y), InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                release(view, center, 0);
                assertEquals(0, selected(view), "A drag must retain the selected card");

                press(view, center, 0);
                view.putClientProperty("desktop.cardDragActive", true);
                release(view, center, 0);
                assertEquals(0, selected(view), "An active reorder must not be treated as a click");
                view.putClientProperty("desktop.cardDragActive", false);

                press(view, center, 0);
                var escape = new KeyEvent(view, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
                for (var listener : view.getKeyListeners()) listener.keyPressed(escape);
                release(view, center, 0);
                assertEquals(0, selected(view));

                press(view, center, 0);
                if (view instanceof JList<?> list) {
                    @SuppressWarnings("unchecked")
                    var model = (DefaultListModel<String>) list.getModel();
                    model.set(0, "已更新");
                } else ((DefaultTableModel) ((JTable) view).getModel()).setValueAt("已更新", 0, 0);
                release(view, center, 0);
                assertEquals(0, selected(view));
                click(view, 0, 0);
                assertEquals(-1, selected(view), "A subsequent ordinary click must still toggle");
            }
        });
    }

    @Test
    void themeRefreshKeepsModelsSelectionAndExactlyOneOwnedListenerSet() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            for (var factory : factories()) {
                var view = factory.get();
                select(view, 1);
                Object model = view instanceof JList<?> list ? list.getModel() : ((JTable) view).getModel();
                var listeners = listeners(view);
                for (int scale : new int[]{150, 100, 150, 100}) {
                    DesktopThemeService.install(scale == 150 ? ThemeMode.DARK : ThemeMode.LIGHT, scale);
                    SwingUtilities.updateComponentTreeUI(view);
                    assertSame(model, view instanceof JList<?> list ? list.getModel() : ((JTable) view).getModel());
                    assertEquals(1, selected(view));
                    assertArrayEquals(listeners, listeners(view));
                }
                click(view, 1, 0);
                assertEquals(-1, selected(view));
                click(view, 0, 0);
                assertEquals(0, selected(view), "The first click after a theme refresh must still select");
            }
        });
    }

    @Test
    void nativeTablesPickersAndModifiedMultiSelectionKeepTheirExistingBehavior() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var nativeTable = table(false);
            nativeTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            nativeTable.addRowSelectionInterval(0, 0);
            click(nativeTable, 0, 0);
            click(nativeTable, 0, 0);
            assertEquals(0, nativeTable.getSelectedRow());
            nativeTable.addRowSelectionInterval(1, 1);
            assertArrayEquals(new int[]{0, 1}, nativeTable.getSelectedRows());

            var comboList = list(false);
            comboList.setName("ComboBox.list");
            var fileList = list(false);
            fileList.putClientProperty("List.isFileList", true);
            for (var list : List.of(comboList, fileList)) {
                list.setSelectedIndex(0);
                var content = new Point(8, list.getCellBounds(0, 0).height / 2);
                clickAt(list, content, 0);
                clickAt(list, content, 0);
                assertEquals(0, list.getSelectedIndex());
            }
            var chooserList = list(false);
            new JFileChooser().add(chooserList);
            chooserList.setSelectedIndex(0);
            click(chooserList, 0, 0);
            assertEquals(0, chooserList.getSelectedIndex());

            var multi = list(false);
            multi.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            click(multi, 0, 0);
            click(multi, 1, InputEvent.CTRL_DOWN_MASK);
            assertArrayEquals(new int[]{0, 1}, multi.getSelectedIndices());
            click(multi, 1, InputEvent.CTRL_DOWN_MASK);
            assertArrayEquals(new int[]{0}, multi.getSelectedIndices());
        });
    }

    private static List<Supplier<JComponent>> factories() {
        return List.of(() -> list(false), () -> list(true), () -> table(true));
    }

    private static JList<String> list(boolean reorder) {
        var model = new DefaultListModel<String>();
        model.addElement("服务器甲");
        model.addElement("服务器乙");
        JList<String> list = reorder ? new DesktopCardList<>(model, 90) : new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(90);
        list.setSize(300, 300);
        if (reorder) new DesktopPartFactory(ThemePalette.light()).cardList(list, true);
        return list;
    }

    private static JTable table(boolean cards) {
        var table = new JTable(new DefaultTableModel(new Object[][]{{"甲"}, {"乙"}}, new Object[]{"服务器"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty(DesktopCardSupport.CARDS, cards);
        table.setRowHeight(90);
        table.setSize(300, 300);
        table.doLayout();
        return table;
    }

    private static int[] listeners(JComponent view) {
        int models = view instanceof JList<?> list
            ? ((DefaultListModel<?>) list.getModel()).getListDataListeners().length
            : ((DefaultTableModel) ((JTable) view).getModel()).getTableModelListeners().length;
        return new int[]{view.getMouseListeners().length, view.getMouseMotionListeners().length,
            view.getFocusListeners().length, view.getKeyListeners().length, view.getComponentListeners().length,
            view.getPropertyChangeListeners().length, models};
    }

    private static int selected(JComponent view) {
        return view instanceof JList<?> list ? list.getSelectedIndex() : ((JTable) view).getSelectedRow();
    }

    private static void select(JComponent view, int row) {
        if (view instanceof JList<?> list) list.setSelectedIndex(row);
        else ((JTable) view).setRowSelectionInterval(row, row);
    }

    private static Point point(int row) {
        return new Point(80, row * 90 + 45);
    }

    private static void click(JComponent view, int row, int modifiers) {
        Point point;
        if (view instanceof JList<?> list) {
            var bounds = list.getCellBounds(row, row);
            point = new Point(80, bounds.y + bounds.height / 2);
        } else {
            var bounds = ((JTable) view).getCellRect(row, 0, true);
            point = new Point(80, bounds.y + bounds.height / 2);
        }
        clickAt(view, point, modifiers);
    }

    private static void clickAt(JComponent view, Point point, int modifiers) {
        press(view, point, modifiers);
        release(view, point, modifiers);
    }

    private static void press(JComponent view, Point point, int modifiers) {
        dispatch(view, MouseEvent.MOUSE_PRESSED, point, modifiers | InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
    }

    private static void release(JComponent view, Point point, int modifiers) {
        dispatch(view, MouseEvent.MOUSE_RELEASED, point, modifiers, MouseEvent.BUTTON1);
    }

    private static void dispatch(JComponent view, int id, Point point, int modifiers, int button) {
        view.dispatchEvent(new MouseEvent(view, id, System.currentTimeMillis(), modifiers, point.x, point.y,
            point.x, point.y, 1, false, button));
    }
}

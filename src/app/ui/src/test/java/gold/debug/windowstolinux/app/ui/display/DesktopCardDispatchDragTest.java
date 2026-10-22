package gold.debug.windowstolinux.app.ui.display;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.control.DesktopCardTableOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises shown Swing handlers and rendering through component events, separately from OS/Robot delivery. / 通过组件事件验证已显示窗口的处理及绘制，与操作系统和 Robot 投递分开。 */
class DesktopCardDispatchDragTest {
    @Test
    void tableHandlesPreserveEmptyOrExistingSelectionAndCancelOnFilteringWithoutReplacingModels() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            for (int scale : new int[]{100, 150}) {
                TableFixture fixture = edt(() -> new TableFixture(theme, scale));
                try {
                    int mouseListeners = edt(() -> fixture.table.getMouseListeners().length);
                    for (int selected : new int[]{-1, 2}) {
                        edt(() -> {
                            fixture.model.fireTableDataChanged();
                            SwingUtilities.updateComponentTreeUI(fixture.frame);
                            fixture.frame.validate();
                            assertTrue(fixture.table.isShowing());
                            assertSame(fixture.model, fixture.table.getModel());
                            assertEquals(mouseListeners, fixture.table.getMouseListeners().length);
                            if (selected < 0) fixture.table.clearSelection();
                            else fixture.table.setRowSelectionInterval(selected, selected);
                            return null;
                        });
                        var start = edt(() -> handleCenter(fixture.order.handleBounds(0)));
                        var end = edt(() -> handleCenter(fixture.order.handleBounds(2)));
                        int writes = fixture.writes.get();
                        edt(() -> {
                            dispatch(fixture.table, MouseEvent.MOUSE_PRESSED, start, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                            dispatch(fixture.table, MouseEvent.MOUSE_RELEASED, start, 0, MouseEvent.BUTTON1);
                            assertEquals(selected, fixture.table.getSelectedRow(), "A short handle click must not open or close details");
                            dispatch(fixture.table, MouseEvent.MOUSE_PRESSED, start, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                            return null;
                        });
                        for (int step = 1; step <= 10; step++) {
                            int current = step;
                            edt(() -> {
                                var point = new Point(start.x, start.y + (end.y - start.y) * current / 10);
                                dispatch(fixture.table, MouseEvent.MOUSE_DRAGGED, point, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                                assertEquals(Boolean.TRUE, fixture.table.getClientProperty("desktop.cardDragActive"));
                                assertEquals(selected, fixture.table.getSelectedRow());
                                assertEquals(List.of("甲", "乙", "丙"), fixture.values());
                                assertEquals(writes, fixture.writes.get());
                                if (current == 5)
                                    capture(fixture.frame, "table-" + theme + "-" + scale + "-selection-" + selected + "-drag");
                                return null;
                            });
                            Thread.sleep(20);
                        }
                        edt(() -> {
                            dispatch(fixture.table, MouseEvent.MOUSE_RELEASED, end, 0, MouseEvent.BUTTON1);
                            assertEquals(writes + 1, fixture.writes.get());
                            assertEquals(List.of("乙", "丙", "甲"), fixture.requested.get());
                            assertEquals(List.of("甲", "乙", "丙"), fixture.values(), "The owner applies only confirmed persistence");
                            assertEquals(selected, fixture.table.getSelectedRow());
                            return null;
                        });
                    }
                    edt(() -> {
                        fixture.model.fireTableDataChanged();
                        fixture.table.setRowSelectionInterval(2, 2);
                        var start = handleCenter(fixture.order.handleBounds(0));
                        var end = handleCenter(fixture.order.handleBounds(2));
                        dispatch(fixture.table, MouseEvent.MOUSE_PRESSED, start, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                        dispatch(fixture.table, MouseEvent.MOUSE_DRAGGED, end, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                        assertEquals(Boolean.TRUE, fixture.table.getClientProperty("desktop.cardDragActive"));
                        fixture.sorter.setRowFilter(RowFilter.regexFilter("乙|丙"));
                        assertNotEquals(Boolean.TRUE, fixture.table.getClientProperty("desktop.cardDragActive"));
                        dispatch(fixture.table, MouseEvent.MOUSE_RELEASED, end, 0, MouseEvent.BUTTON1);
                        assertEquals(2, fixture.writes.get());
                        fixture.sorter.setRowFilter(null);
                        fixture.admitted.set(false);
                        fixture.table.setRowSelectionInterval(2, 2);
                        start = handleCenter(fixture.order.handleBounds(0));
                        dispatch(fixture.table, MouseEvent.MOUSE_PRESSED, start, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                        dispatch(fixture.table, MouseEvent.MOUSE_DRAGGED, end, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                        dispatch(fixture.table, MouseEvent.MOUSE_RELEASED, end, 0, MouseEvent.BUTTON1);
                        assertEquals(2, fixture.table.getSelectedRow());
                        assertEquals(2, fixture.writes.get());
                        assertSame(fixture.model, fixture.table.getModel());
                        return null;
                    });
                } finally {
                    edt(() -> {
                        int listeners = fixture.table.getMouseListeners().length;
                        fixture.order.close();
                        assertEquals(listeners - 1, fixture.table.getMouseListeners().length);
                        fixture.frame.dispose();
                        assertFalse(fixture.frame.isDisplayable());
                        assertNotEquals(Boolean.TRUE, fixture.table.getClientProperty("desktop.cardDragActive"));
                        return null;
                    });
                }
            }
        }
    }

    @Test
    void shownCardsKeepSelectionAndConfirmedModelUntilReleaseAcrossThemeScaleAndUiReinstallation() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            for (int scale : new int[]{100, 150}) {
                Fixture fixture = edt(() -> new Fixture(theme, scale));
                try {
                    for (int pass = 0; pass < 2; pass++) {
                        int iteration = pass;
                        edt(() -> {
                            if (iteration > 0) SwingUtilities.updateComponentTreeUI(fixture.frame);
                            fixture.frame.validate();
                            assertTrue(fixture.frame.isShowing());
                            assertTrue(fixture.list.isShowing());
                            fixture.list.setSelectedIndex(0);
                            return null;
                        });
                        drag(fixture, theme + "-" + scale + "-ui-" + pass);
                    }
                    assertEquals(2, fixture.writes.get());
                } finally {
                    edt(() -> {
                        fixture.frame.dispose();
                        assertFalse(fixture.frame.isDisplayable());
                        assertFalse(fixture.list.isDisplayable());
                        assertNotEquals(Boolean.TRUE, fixture.list.getClientProperty("desktop.cardDragActive"));
                        return null;
                    });
                }
            }
        }
    }

    private static void drag(Fixture fixture, String imageName) throws Exception {
        var before = edt(() -> values(fixture.list));
        String selected = before.getFirst();
        int writes = fixture.writes.get();
        Point start = edt(() -> center(fixture.list, 0));
        Point end = edt(() -> center(fixture.list, 2));
        edt(() -> {
            dispatch(fixture.list, MouseEvent.MOUSE_PRESSED, start, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
            assertEquals(selected, fixture.list.getSelectedValue());
            return null;
        });
        for (int step = 1; step <= 12; step++) {
            int current = step;
            edt(() -> {
                Point point = new Point(start.x + (end.x - start.x) * current / 12,
                    start.y + (end.y - start.y) * current / 12);
                dispatch(fixture.list, MouseEvent.MOUSE_DRAGGED, point, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                assertEquals(Boolean.TRUE, fixture.list.getClientProperty("desktop.cardDragActive"));
                assertEquals(before, values(fixture.list), "The drag preview must retain the confirmed model");
                assertEquals(selected, fixture.list.getSelectedValue(), "Dragging the selected card must not deselect it");
                assertEquals(writes, fixture.writes.get());
                if (current == 6) capture(fixture.frame, imageName + "-drag");
                return null;
            });
            Thread.sleep(20);
        }
        edt(() -> {
            dispatch(fixture.list, MouseEvent.MOUSE_RELEASED, end, 0, MouseEvent.BUTTON1);
            var expected = new ArrayList<>(before);
            expected.add(expected.removeFirst());
            assertEquals(writes + 1, fixture.writes.get(), "A completed drag must submit exactly one order change");
            assertEquals(expected, fixture.saved.get());
            assertEquals(expected, values(fixture.list));
            assertEquals(selected, fixture.list.getSelectedValue());
            assertNotEquals(Boolean.TRUE, fixture.list.getClientProperty("desktop.cardDragActive"));
            capture(fixture.frame, imageName + "-released");
            return null;
        });
    }

    private static final class Fixture {
        private final DefaultListModel<String> model = new DefaultListModel<>();

        private final DesktopCardList<String> list;

        private final AtomicInteger writes = new AtomicInteger();

        private final AtomicReference<List<String>> saved = new AtomicReference<>();

        private final JFrame frame;

        private Fixture(ThemeMode theme, int scale) {
            DesktopThemeService.install(theme, scale);
            frame = new JFrame("Swing card fixture: component events, OS input verified separately");
            model.addElement("服务器甲");
            model.addElement("服务器乙");
            model.addElement("服务器丙");
            list = new DesktopCardList<>(model, 90);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            var parts = new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
            parts.cardList(list, true);
            list.reorder(order -> {
                String selected = list.getSelectedValue();
                writes.incrementAndGet();
                saved.set(order);
                model.clear();
                order.forEach(model::addElement);
                list.setSelectedValue(selected, true);
            }, () -> true);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.add(new JLabel("组件事件验证：选中卡片拖动，释放后保存顺序"), BorderLayout.NORTH);
            frame.add(new JScrollPane(list), BorderLayout.CENTER);
            frame.setSize(UIScale.scale(620), UIScale.scale(420));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.validate();
        }
    }

    private static final class TableFixture {
        private final javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
            new Object[][]{{"甲"}, {"乙"}, {"丙"}}, new Object[]{"服务器"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        private final JTable table;

        private final javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> sorter;

        private final AtomicInteger writes = new AtomicInteger();

        private final AtomicReference<List<String>> requested = new AtomicReference<>();

        private final AtomicBoolean admitted = new AtomicBoolean(true);

        private final DesktopCardTableOrder<String> order;

        private final JFrame frame;

        private TableFixture(ThemeMode theme, int scale) {
            DesktopThemeService.install(theme, scale);
            table = new JTable(model);
            table.setRowHeight(UIScale.scale(90));
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setTableHeader(null);
            sorter = new javax.swing.table.TableRowSorter<>(model);
            table.setRowSorter(sorter);
            var parts = new DesktopPartFactory(ThemePalette.light());
            order = parts.cardOrder(table, this::values, value -> {
                requested.set(value);
                writes.incrementAndGet();
            }, admitted::get);
            assertFalse(order.canReorder());
            parts.cardList(table, true);
            frame = new JFrame("Swing table cards: component events, OS input verified separately");
            frame.add(new JScrollPane(table));
            frame.setSize(UIScale.scale(620), UIScale.scale(420));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.validate();
        }

        private List<String> values() {
            return java.util.stream.IntStream.range(0, model.getRowCount()).mapToObj(row -> (String) model.getValueAt(row, 0)).toList();
        }
    }

    private static Point handleCenter(java.awt.Rectangle bounds) {
        assertFalse(bounds.isEmpty());
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private static Point center(JList<?> list, int row) {
        if (list instanceof DesktopCardList<?> cards) {
            var handle = cards.handleBounds(row);
            assertFalse(handle.isEmpty());
            return new Point(handle.x + handle.width / 2, handle.y + handle.height / 2);
        }
        var bounds = list.getCellBounds(row, row);
        return new Point(Math.min(bounds.width / 2, UIScale.scale(100)), bounds.y + bounds.height / 2);
    }

    private static void dispatch(Component view, int id, Point point, int modifiers, int button) {
        Point screen = view.getLocationOnScreen();
        view.dispatchEvent(new MouseEvent(view, id, System.currentTimeMillis(), modifiers, point.x, point.y,
            screen.x + point.x, screen.y + point.y, 1, false, button));
    }

    private static List<String> values(JList<String> list) {
        return java.util.stream.IntStream.range(0, list.getModel().getSize()).mapToObj(list.getModel()::getElementAt).toList();
    }

    private static void capture(JFrame frame, String name) throws Exception {
        var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            frame.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Path file = Path.of("target/card-component-drag", name + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}

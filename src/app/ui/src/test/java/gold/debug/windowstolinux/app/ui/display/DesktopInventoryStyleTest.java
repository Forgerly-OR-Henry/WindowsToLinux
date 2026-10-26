package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesktopInventoryStyleTest {
    @Test
    void cardsHaveSeparateCornersGapsAndOnlySelectedMarkersAcrossThemeUpdates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                for (int scale : new int[]{100, 150}) {
                    DesktopThemeService.install(theme, scale);
                    var model = new DefaultListModel<String>();
                    model.addElement("服务器甲");
                    model.addElement("服务器乙");
                    var list = new JList<>(model);
                    list.setSelectedIndex(0);
                    for (int pass = 0; pass < 3; pass++) {
                        SwingUtilities.updateComponentTreeUI(list);
                        var first = render(list.getCellRenderer().getListCellRendererComponent(list, "服务器甲", 0, true, false), scale);
                        var second = render(list.getCellRenderer().getListCellRendererComponent(list, "服务器乙", 1, false, false), scale);
                        int center = first.getHeight() / 2;
                        assertEquals(UIManager.getColor("Component.focusColor").getRGB(), first.getRGB(2, center));
                        assertNotEquals(first.getRGB(2, center), second.getRGB(2, center));
                        assertEquals(UIManager.getColor("List.background").getRGB(), first.getRGB(0, 0));
                        assertEquals(UIManager.getColor("List.background").getRGB(), first.getRGB(120, 2));
                        assertSame(model, list.getModel());
                        assertEquals(0, list.getSelectedIndex());
                    }
                }
            }
        });
    }

    @Test
    void fixedRowsRescaleWithoutLosingSelectionOrAuthorListeners() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var list = new JList<>(new String[]{"甲", "乙"});
            list.setFixedCellHeight(64);
            list.setSelectedIndex(1);
            var transfer = new TransferHandler("name");
            list.setTransferHandler(transfer);
            javax.swing.event.ListSelectionListener listener = event -> fail("Theme refresh must not change selection");
            list.addListSelectionListener(listener);
            var model = list.getModel();
            for (int scale : new int[]{150, 100, 150, 100}) {
                DesktopThemeService.install(scale == 150 ? ThemeMode.DARK : ThemeMode.LIGHT, scale);
                SwingUtilities.updateComponentTreeUI(list);
                assertEquals(64 * scale / 100, list.getFixedCellHeight());
                assertEquals(1, list.getSelectedIndex());
                assertSame(model, list.getModel());
                assertSame(transfer, list.getTransferHandler());
                assertEquals(1, java.util.Arrays.stream(list.getListSelectionListeners()).filter(value -> value == listener).count());
            }
        });
    }

    @Test
    void authorRendererContentAndTooltipsSurviveReplacementAndThemeRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var list = new JList<>(new String[]{"entry"});
            var original = new AtomicReference<Component>();
            ListCellRenderer<String> renderer = (view, value, index, selected, focused) -> {
                var label = new JLabel(value + " 原内容");
                label.setToolTipText("原提示");
                original.set(label);
                return label;
            };
            list.setCellRenderer(renderer);
            DesktopThemeService.install(ThemeMode.DARK);
            SwingUtilities.updateComponentTreeUI(list);
            assertSame(renderer, DesktopListUi.originalRenderer(list));
            var row = (JComponent) list.getCellRenderer().getListCellRendererComponent(list, "entry", 0, true, true);
            assertTrue(SwingUtilities.isDescendingFrom(original.get(), row));
            assertEquals("原提示", row.getToolTipText());
        });
    }

    @Test
    void multiSelectionTableKeepsColumnsBooleanEditorAndTransferHandler() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var model = new DefaultTableModel(new Object[][]{{"甲", true}, {"乙", false}, {"丙", true}}, new String[]{"名称", "启用"}) {
                @Override
                public Class<?> getColumnClass(int column) {
                    return column == 1 ? Boolean.class : String.class;
                }
            };
            var table = new JTable(model);
            var transfer = new TransferHandler("name");
            table.setTransferHandler(transfer);
            table.addRowSelectionInterval(0, 0);
            table.addRowSelectionInterval(2, 2);
            table.setSize(400, 150);
            var image = new BufferedImage(400, 150, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            table.paint(g);
            g.dispose();
            int accent = UIManager.getColor("Component.focusColor").getRGB();
            assertEquals(accent, image.getRGB(2, table.getRowHeight() / 2));
            assertNotEquals(accent, image.getRGB(2, table.getRowHeight() * 3 / 2));
            assertEquals(accent, image.getRGB(2, table.getRowHeight() * 5 / 2));
            assertEquals(2, table.getColumnCount());
            var booleanRenderer = table.getDefaultRenderer(Boolean.class);
            assertNotSame(table.getDefaultRenderer(Object.class), booleanRenderer);
            assertInstanceOf(JCheckBox.class, table.getDefaultEditor(Boolean.class).getTableCellEditorComponent(table, true, true, 0, 1));
            assertSame(transfer, table.getTransferHandler());
            assertSame(model, table.getModel());
            var columns = table.getColumnModel();
            for (int scale : new int[]{150, 100, 150}) {
                DesktopThemeService.install(scale == 150 ? ThemeMode.DARK : ThemeMode.LIGHT, scale);
                SwingUtilities.updateComponentTreeUI(table);
                table.setSize(400, table.getRowHeight() * 3);
                var updated = new BufferedImage(400, table.getHeight(), BufferedImage.TYPE_INT_RGB);
                var paint = updated.createGraphics();
                table.paint(paint);
                paint.dispose();
                int selectedColor = UIManager.getColor("Component.focusColor").getRGB();
                assertEquals(selectedColor, updated.getRGB(2, table.getRowHeight() / 2));
                assertEquals(selectedColor, updated.getRGB(2, table.getRowHeight() * 5 / 2));
                assertNotEquals(selectedColor, updated.getRGB(2, table.getRowHeight() * 3 / 2));
                assertArrayEquals(new int[]{0, 2}, table.getSelectedRows());
                assertSame(columns, table.getColumnModel());
                assertSame(transfer, table.getTransferHandler());
                assertSame(model, table.getModel());
            }
        });
    }

    @Test
    void comboPopupAndFileChooserListsKeepTheirOriginalRendererSurface() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var popup = new JList<>(new String[]{"choice"});
            popup.setName("ComboBox.list");
            assertInstanceOf(DefaultListCellRenderer.class, popup.getCellRenderer().getListCellRendererComponent(popup, "choice", 0, true, false));
            var fileList = new JList<>(new String[]{"file"});
            var chooser = new JFileChooser();
            chooser.add(fileList);
            assertInstanceOf(DefaultListCellRenderer.class, fileList.getCellRenderer().getListCellRendererComponent(fileList, "file", 0, true, false));
        });
    }

    private static BufferedImage render(Component row, int scale) {
        var image = new BufferedImage(500, 90 * scale / 100, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(UIManager.getColor("List.background"));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        row.setSize(image.getWidth(), image.getHeight());
        layout(row);
        row.paint(g);
        g.dispose();
        return image;
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (var child : container.getComponents()) layout(child);
        }
    }
}

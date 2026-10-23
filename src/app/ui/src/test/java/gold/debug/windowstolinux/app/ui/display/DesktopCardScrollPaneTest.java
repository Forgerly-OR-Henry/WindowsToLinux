package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;

import com.formdev.flatlaf.util.UIScale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesktopCardScrollPaneTest {
    @Test
    void persistentIndicatorsKeepTheirWidthGapAndToolbarEdgeForEmptyFittingAndOverflowingLists() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                for (int scale : new int[]{100, 150}) {
                    DesktopThemeService.install(theme, scale);
                    int neutral = theme == ThemeMode.DARK ? 50 : 210;
                    assertEquals(new Color(neutral, neutral, neutral), UIManager.getColor("ScrollBar.thumb"));
                    for (int rows : new int[]{0, 1, 20}) {
                        var scroll = new JScrollPane(list(rows));
                        var root = new JPanel(new BorderLayout());
                        var toolbar = new JPanel(new BorderLayout());
                        var add = new JButton("添加服务器");
                        toolbar.add(add, BorderLayout.EAST);
                        root.add(toolbar, BorderLayout.NORTH);
                        root.add(scroll, BorderLayout.CENTER);
                        root.setSize(UIScale.scale(600), UIScale.scale(300));
                        layout(root);
                        var bar = scroll.getVerticalScrollBar();
                        assertTrue(bar.isVisible());
                        assertFalse(scroll.getHorizontalScrollBar().isVisible());
                        assertEquals(UIScale.scale(12), bar.getWidth());
                        var viewport = scroll.getViewport();
                        assertEquals(UIScale.scale(12), bar.getX() - viewport.getX() - viewport.getWidth());
                        assertEquals(root.getWidth(), bar.getX() + bar.getWidth());
                        assertEquals(root.getWidth(), add.getX() + add.getWidth());
                        assertIndicator(bar, rows > 1);
                        if (rows == 1) {
                            bar.setEnabled(false);
                            assertIndicator(bar, false);
                        }
                    }
                }
            }
        });
    }

    @Test
    void anExactlyFittingCardTablePaintsTheFullTrackAndKeepsItsModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var table = new JTable(new DefaultTableModel(new Object[][]{{"甲"}, {"乙"}}, new Object[]{"服务器"}));
            table.putClientProperty(DesktopCardSupport.CARDS, true);
            table.setTableHeader(null);
            table.setRowHeight(90);
            var model = table.getModel();
            var scroll = new JScrollPane(table);
            scroll.setSize(600, 180);
            layout(scroll);
            assertEquals(180, scroll.getViewport().getExtentSize().height);
            assertEquals(180, table.getHeight());
            assertSame(model, table.getModel());
            assertIndicator(scroll.getVerticalScrollBar(), false);
        });
    }

    @Test
    void themeRefreshAndViewReplacementDoNotAccumulateMarginsOrListeners() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var list = list(20);
            var scroll = new JScrollPane(list);
            Border originalBorder = BorderFactory.createEmptyBorder(1, 2, 3, 4);
            scroll.setViewportBorder(originalBorder);
            scroll.setSize(600, 300);
            layout(scroll);
            var model = scroll.getVerticalScrollBar().getModel();
            scroll.getVerticalScrollBar().setValue(100);
            int[] listeners = listeners(scroll, list);
            for (int scale : new int[]{150, 100, 150, 100}) {
                DesktopThemeService.install(scale == 150 ? ThemeMode.DARK : ThemeMode.LIGHT, scale);
                SwingUtilities.updateComponentTreeUI(scroll);
                layout(scroll);
                assertEquals(4 + UIScale.scale(12), scroll.getViewportBorder().getBorderInsets(scroll).right);
                assertSame(model, scroll.getVerticalScrollBar().getModel());
                assertEquals(100, model.getValue());
                assertArrayEquals(listeners, listeners(scroll, list));
                assertEquals(UIScale.scale(12), scroll.getVerticalScrollBar().getWidth());
            }
            var oldBar = scroll.getVerticalScrollBar();
            var replacement = new JScrollBar();
            scroll.setVerticalScrollBar(replacement);
            layout(scroll);
            assertNull(oldBar.getClientProperty(DesktopCardSupport.SCROLLBAR));
            assertEquals(Boolean.TRUE, replacement.getClientProperty(DesktopCardSupport.SCROLLBAR));
            assertEquals(12, replacement.getWidth());

            scroll.setViewportView(new JTextArea("普通文本"));
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, scroll.getVerticalScrollBarPolicy());
            assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED, scroll.getHorizontalScrollBarPolicy());
            assertSame(originalBorder, scroll.getViewportBorder());
            assertNull(replacement.getClientProperty(DesktopCardSupport.SCROLLBAR));
            assertEquals(14, replacement.getPreferredSize().width);
            scroll.setViewportView(list);
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());
            assertEquals(16, scroll.getViewportBorder().getBorderInsets(scroll).right);
        });
    }

    @Test
    void filePickersNativeTablesAndEnclosingPagesKeepNativeScrollPolicies() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var scroll = new JScrollPane(list(2));
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());
            var chooser = new JFileChooser();
            chooser.add(scroll);
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, scroll.getVerticalScrollBarPolicy());
            assertNull(scroll.getVerticalScrollBar().getClientProperty(DesktopCardSupport.SCROLLBAR));
            chooser.remove(scroll);
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());

            var table = new JScrollPane(new JTable(2, 2));
            var text = new JScrollPane(new JTextArea());
            var page = new JPanel();
            page.add(scroll);
            var outer = new JScrollPane(page);
            var combo = list(2);
            combo.setName("ComboBox.list");
            for (var nativeScroll : new JScrollPane[]{table, text, outer, new JScrollPane(combo)}) {
                assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, nativeScroll.getVerticalScrollBarPolicy());
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED, nativeScroll.getHorizontalScrollBarPolicy());
                assertNull(nativeScroll.getVerticalScrollBar().getClientProperty(DesktopCardSupport.SCROLLBAR));
            }
        });
    }

    private static JList<String> list(int rows) {
        var model = new DefaultListModel<String>();
        for (int row = 0; row < rows; row++) model.addElement("服务器 " + (row + 1));
        var list = new JList<>(model);
        list.setFixedCellHeight(UIScale.scale(90));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        return list;
    }

    private static int[] listeners(JScrollPane scroll, JList<?> list) {
        return new int[]{scroll.getPropertyChangeListeners().length, scroll.getHierarchyListeners().length,
            scroll.getViewport().getContainerListeners().length, list.getPropertyChangeListeners().length,
            scroll.getVerticalScrollBar().getPropertyChangeListeners().length};
    }

    private static void assertIndicator(JScrollBar bar, boolean overflowing) {
        var model = bar.getModel();
        int[] originalRange = {model.getMinimum(), model.getMaximum(), model.getExtent(), model.getValue()};
        var image = new BufferedImage(bar.getWidth(), bar.getHeight(), BufferedImage.TYPE_INT_RGB);
        var paint = image.createGraphics();
        bar.paint(paint);
        paint.dispose();
        assertArrayEquals(originalRange, new int[]{model.getMinimum(), model.getMaximum(), model.getExtent(), model.getValue()});
        int thumb = UIManager.getColor("ScrollBar.thumb").getRGB();
        int x = image.getWidth() - UIScale.scale(3);
        int colored = 0, middle = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            if (image.getRGB(x, y) == thumb) {
                colored++;
                middle = y;
            }
        }
        assertTrue(colored > 0, "The indicator must remain visible");
        if (overflowing)
            assertTrue(colored < image.getHeight() * .75, "An overflowing list needs a proportional thumb");
        else assertTrue(colored >= image.getHeight() - UIScale.scale(8), "A fitting list needs a full-track thumb");
        if (!overflowing) middle = image.getHeight() / 2;
        else middle /= 2;
        int width = 0;
        for (int px = 0; px < image.getWidth(); px++) if (image.getRGB(px, middle) == thumb) width++;
        assertEquals(UIScale.scale(6), width);
        assertEquals(thumb, image.getRGB(image.getWidth() - 1, middle), "The visible right edge must align with the toolbar");
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (var child : container.getComponents()) layout(child);
        }
    }
}

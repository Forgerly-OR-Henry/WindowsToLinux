package gold.debug.windowstolinux.app.ui.control;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.display.DesktopListCard;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import org.junit.jupiter.api.Test;

/** Checks local inner-card surfaces without changing native dialogs or inventory gestures. / 检查局部内层卡片表面，不改变原生弹窗或清单手势。 */
class DesktopEmbeddedCardTest {
    private static final Color OUTSIDE = new Color(190, 40, 170);

    @Test
    void innerOutlineUsesPageColorAndSurvivesOpaqueChildrenAtBothZoomLevels() throws Exception {
        appearances((palette, factory) -> {
            var card = factory.outlinedCard(new BorderLayout());
            card.setBorder(null);
            var child = new JPanel();
            child.setBackground(palette.cardBackground());
            card.add(child);
            var image = paint(card, 200, 100);
            assertEquals(OUTSIDE.getRGB(), image.getRGB(0, 0));
            assertEquals(palette.pageBackground().getRGB(), image.getRGB(image.getWidth() / 2, 0));
            assertEquals(palette.cardBackground().getRGB(), image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
            var ordinaryCard = factory.card(new BorderLayout());
            assertEquals(new java.awt.Insets(14, 16, 14, 16), ordinaryCard.getInsets(),
                "Ordinary cards and confirmation windows retain their fixed padding at every zoom");
            var ordinary = paint(ordinaryCard, 200, 100);
            assertEquals(palette.cardBackground().getRGB(), ordinary.getRGB(ordinary.getWidth() / 2, 0),
                "Existing ordinary cards must not acquire the inner outline");
            assertEquals(palette.cardBorder(), factory.divider().getForeground(),
                "The general cardBorder role must remain unchanged");
            assertEquals(palette.pageBackground(), factory.cardDivider().getForeground());
        });
    }

    @Test
    void embeddedInventoriesPaintLocalFramesAndKeepSelectionAndHandleGeometry() throws Exception {
        appearances((palette, factory) -> {
            var list = inventory();
            factory.cardList(list, true);
            var original = rendered(list, false);
            var originalHandle = DesktopListCard.handleBounds(original, UIScale.scale(360), UIScale.scale(116));
            factory.cardInventory(list);
            var row = rendered(list, false);
            assertEquals(originalHandle, DesktopListCard.handleBounds(row, UIScale.scale(360), UIScale.scale(116)));
            var rowImage = paint(row, 360, 116);
            int x = rowImage.getWidth() / 2, top = UIScale.scale(9);
            assertEquals(palette.pageBackground().getRGB(), rowImage.getRGB(x, top));
            assertEquals(palette.cardBackground().getRGB(), rowImage.getRGB(x, top + UIScale.scale(3)));
            var selected = paint(rendered(list, true), 360, 116);
            assertEquals(palette.accent().getRGB(), selected.getRGB(UIScale.scale(1), selected.getHeight() / 2),
                "The selection marker must remain above the shallow frame");

            var scroll = new JScrollPane(list);
            paint(scroll, 380, 160);
            assertEquals(palette.cardBackground(), list.getBackground());
            assertEquals(palette.cardBackground(), scroll.getBackground());
            assertEquals(palette.cardBackground(), scroll.getViewport().getBackground());
            var bar = scroll.getVerticalScrollBar();
            assertEquals(UIScale.scale(12), bar.getWidth());
            var barImage = render(bar);
            assertEquals(palette.cardBackground().getRGB(), barImage.getRGB(UIScale.scale(1), barImage.getHeight() / 2),
                "The scrollbar hit area and track must blend with the surrounding card");
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, scroll.getVerticalScrollBarPolicy());
            assertEquals(palette.pageBackground(), new JList<>().getBackground(),
                "Unrelated inventories must retain their default surface");
        });
    }

    @Test
    void themeAndZoomRefreshRetainModelsDraftsPositionsAndSinglePadding() throws Exception {
        appearances((palette, factory) -> {
            var list = inventory();
            factory.cardInventory(list);
            list.setSelectedIndex(7);
            var scroll = new JScrollPane(list);
            var outer = factory.outerCard(new BorderLayout());
            outer.add(scroll);
            paint(outer, 400, 200);
            scroll.getViewport().setViewPosition(new Point(0, 100));
            var position = scroll.getViewport().getViewPosition();
            var model = list.getModel();
            var selectionModel = list.getSelectionModel();
            var root = factory.pagePanel();
            root.add(outer);
            var details = factory.outlinedCard(new BorderLayout());
            var input = new JTextField("Uncommitted draft");
            input.select(2, 8);
            details.add(input);
            var document = input.getDocument();
            var detailScroll = new JScrollPane(details);
            factory.cardScroll(detailScroll);
            root.add(detailScroll, BorderLayout.EAST);
            for (ThemeMode theme : List.of(ThemeMode.DARK, ThemeMode.LIGHT)) {
                var nextPalette = theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
                DesktopThemeService.install(theme, 150);
                SwingUtilities.updateComponentTreeUI(root);
                factory.palette(nextPalette);
                factory.refreshTheme(root);
                factory.refreshTheme(root);
                assertSame(model, list.getModel());
                assertSame(selectionModel, list.getSelectionModel());
                assertEquals(7, list.getSelectedIndex());
                assertEquals(position, scroll.getViewport().getViewPosition());
                assertSame(document, input.getDocument());
                assertEquals("Uncommitted draft", input.getText());
                assertEquals(2, input.getSelectionStart());
                assertEquals(8, input.getSelectionEnd());
                assertEquals(UIScale.scale(14), outer.getInsets().top);
                assertEquals(UIScale.scale(16), outer.getInsets().left);
                assertEquals(nextPalette.cardBackground(), scroll.getViewport().getBackground());
                assertEquals(nextPalette.cardBackground(), scroll.getVerticalScrollBar().getBackground());
                assertEquals(nextPalette.cardBackground(), detailScroll.getViewport().getBackground());
                assertEquals(nextPalette.cardBackground(), details.getBackground());
                details.setSize(UIScale.scale(200), UIScale.scale(100));
                layout(details);
                assertEquals(nextPalette.pageBackground().getRGB(), render(details)
                    .getRGB(UIScale.scale(100), 0));
                assertSame(details, detailScroll.getViewport().getView());
            }
        });
    }

    private static JList<String> inventory() {
        var model = new DefaultListModel<String>();
        for (int index = 0; index < 20; index++) model.addElement("Model " + index);
        var list = new JList<>(model);
        list.setFixedCellHeight(UIScale.scale(116));
        return list;
    }

    private static JComponent rendered(JList<String> list, boolean selected) {
        return (JComponent) list.getCellRenderer().getListCellRendererComponent(list, "Model 0", 0, selected, false);
    }

    private static BufferedImage paint(JComponent component, int width, int height) {
        var host = new JPanel(new BorderLayout());
        host.setBackground(OUTSIDE);
        host.add(component);
        host.setSize(UIScale.scale(width), UIScale.scale(height));
        layout(host);
        return render(host);
    }

    private static BufferedImage render(JComponent component) {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents())
            if (child instanceof Container nested) layout(nested);
    }

    private static void appearances(AppearanceCheck check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            float zoom = UIScale.getZoomFactor();
            ThemeMode previous = UIManager.getLookAndFeel() instanceof FlatDarkLaf ? ThemeMode.DARK : ThemeMode.LIGHT;
            try {
                for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                    for (int scale : new int[]{100, 150}) {
                        DesktopThemeService.install(theme, scale);
                        var palette = theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
                        check.verify(palette, new DesktopPartFactory(palette));
                    }
            } finally {
                DesktopThemeService.install(previous, Math.round(zoom * 100));
            }
        });
    }

    @FunctionalInterface
    private interface AppearanceCheck {
        void verify(ThemePalette palette, DesktopPartFactory factory);
    }
}

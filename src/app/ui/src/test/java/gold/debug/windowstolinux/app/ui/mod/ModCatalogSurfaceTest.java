package gold.debug.windowstolinux.app.ui.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import org.junit.jupiter.api.Test;

class ModCatalogSurfaceTest {
    @Test
    void catalogControlsShareRightEdgeAndOnlyInventoryScrollsForEmptyFittingAndOverflowingCards() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int scale : new int[]{100, 150}) {
                    DesktopThemeService.install(theme, scale);
                    for (int rows : new int[]{0, 1, 12}) {
                        var fixture = surface(rows, theme);
                        fixture.surface().setSize(1400, 760);
                        for (boolean details : new boolean[]{false, true}) {
                            fixture.surface().expanded(details);
                            layout(fixture.surface());
                            var scroll = find(fixture.surface(), "store.inventoryScroll", JScrollPane.class);
                            var inventoryCard = find(fixture.surface(), "store.inventoryCard", JPanel.class);
                            var detailCard = find(fixture.surface(), "store.detailCard", JPanel.class);
                            var bar = scroll.getVerticalScrollBar();
                            var modes = find(fixture.surface(), "modui.modes", JPanel.class);
                            assertTrue(bar.isVisible());
                            assertFalse(scroll.getHorizontalScrollBar().isVisible());
                            assertEquals(UIScale.scale(12), bar.getWidth());
                            assertEquals(edge(fixture.surface(), bar), edge(fixture.surface(), fixture.refresh()));
                            assertEquals(edge(fixture.surface(), modes), edge(fixture.surface(), fixture.refresh()));
                            assertEquals(edge(fixture.surface(), inventoryCard) - inventoryCard.getInsets().right,
                                edge(fixture.surface(), fixture.refresh()));
                            assertEquals(UIScale.scale(14), inventoryCard.getInsets().top);
                            assertEquals(UIScale.scale(16), inventoryCard.getInsets().right);
                            assertTrue(SwingUtilities.isDescendingFrom(fixture.actions(), details ? detailCard : inventoryCard));
                            var palette = theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
                            assertEquals(palette.cardBackground(), inventoryCard.getBackground());
                            assertEquals(palette.cardBackground(), detailCard.getBackground());
                            assertEquals(palette.cardBackground(), fixture.list().getBackground());
                            assertEquals(palette.cardBackground(), scroll.getViewport().getBackground());
                            assertEquals(UIScale.scale(12), bar.getX() - scroll.getViewport().getX() - scroll.getViewport().getWidth());
                            var fixedSearch = SwingUtilities.convertPoint(fixture.search(), 0, 0, fixture.surface());
                            scroll.getVerticalScrollBar().setValue(UIScale.scale(100));
                            layout(fixture.surface());
                            assertEquals(fixedSearch, SwingUtilities.convertPoint(fixture.search(), 0, 0, fixture.surface()));
                            assertIndicator(bar, rows > 1);
                            if (rows == 1) {
                                fixture.list().setFixedCellHeight(scroll.getViewport().getHeight());
                                layout(fixture.surface());
                                assertEquals(bar.getMaximum() - bar.getMinimum(), bar.getVisibleAmount());
                                assertIndicator(bar, false);
                            }
                            assertNull(fixture.list().getSelectedValue());
                        }
                    }
                }
        });
    }

    @Test
    void footerIsMeasuredAtNewDetailWidthBeforeItsBoundsAreAssigned() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 150);
            var fixture = surface(12, ThemeMode.LIGHT);
            fixture.surface().setSize(1100, 760);
            fixture.surface().expanded(false);
            layout(fixture.surface());
            int wideHeight = fixture.actions().getHeight();
            fixture.surface().expanded(true);
            layout(fixture.surface());
            var details = find(fixture.surface(), "store.detailScroll", JScrollPane.class);
            assertTrue(details.isVisible());
            assertTrue(fixture.actions().getHeight() > wideHeight);
            assertEquals(fixture.actions().getPreferredSize().height, fixture.actions().getHeight());
            for (var action : fixture.actions().getComponents()) {
                assertTrue(fixture.actions().getBounds().width >= action.getX() + action.getWidth());
                assertTrue(fixture.actions().getBounds().height >= action.getY() + action.getHeight());
            }
            var detailBottom = SwingUtilities.convertPoint(details, 0, details.getHeight(), fixture.surface());
            var actionTop = SwingUtilities.convertPoint(fixture.actions(), 0, 0, fixture.surface());
            assertTrue(detailBottom.y + UIScale.scale(18) <= actionTop.y);
            var detailCard = find(fixture.surface(), "store.detailCard", JPanel.class);
            assertEquals(edge(fixture.surface(), detailCard) - detailCard.getInsets().right,
                edge(fixture.surface(), fixture.actions()));
            var actionBottom = SwingUtilities.convertPoint(fixture.actions(), 0, fixture.actions().getHeight(), detailCard);
            assertEquals(detailCard.getHeight() - detailCard.getInsets().bottom, actionBottom.y);
            int fixedActionY = actionTop.y;
            details.getVerticalScrollBar().setValue(UIScale.scale(80));
            layout(fixture.surface());
            assertEquals(fixedActionY, SwingUtilities.convertPoint(fixture.actions(), 0, 0, fixture.surface()).y);
            fixture.surface().expanded(false);
            layout(fixture.surface());
            assertEquals(wideHeight, fixture.actions().getHeight());
            assertFalse(detailCard.isVisible());
        });
    }

    @Test
    void themeReinstallKeepsSelectionHandlersInsetsAndLiteralRemoteHeadings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var fixture = surface(1, ThemeMode.LIGHT);
            var surface = fixture.surface();
            surface.setSize(1400, 760);
            surface.detailHeading("<html><img src='http://127.0.0.1:1/unrequested'>literal", "sample.mod", () -> {
            });
            surface.expanded(true);
            fixture.list().setSelectedIndex(0);
            var store = find(surface, "modui.store", JToggleButton.class);
            int listenerCount = store.getActionListeners().length;
            for (int scale : new int[]{150, 100, 150, 100}) {
                DesktopThemeService.install(scale == 150 ? ThemeMode.DARK : ThemeMode.LIGHT, scale);
                SwingUtilities.updateComponentTreeUI(surface);
                fixture.components().palette(scale == 150 ? ThemePalette.dark() : ThemePalette.light());
                fixture.components().refreshTheme(surface);
                layout(surface);
                assertEquals(UIScale.scale(22), surface.getInsets().right);
                assertEquals(UIScale.scale(18), surface.getInsets().top);
                assertEquals(0, fixture.list().getSelectedIndex());
                assertEquals(listenerCount, store.getActionListeners().length);
                var scroll = find(surface, "store.inventoryScroll", JScrollPane.class);
                var inventoryCard = find(surface, "store.inventoryCard", JPanel.class);
                assertEquals(UIScale.scale(12), scroll.getViewportBorder().getBorderInsets(scroll).right);
                assertEquals(UIScale.scale(12), scroll.getVerticalScrollBar().getWidth());
                assertEquals(UIScale.scale(16), inventoryCard.getInsets().right);
                assertEquals(UIScale.scale(14), inventoryCard.getInsets().top);
                assertTrue(find(surface, "modui.store", JToggleButton.class).isSelected());
            }
            assertLiteral(surface);
        });
    }

    private static Fixture surface(int count, ThemeMode theme) {
        var components = new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
        var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("en"));
        var model = new DefaultListModel<String>();
        for (int index = 0; index < count; index++) model.addElement("Mod " + index);
        var list = new DesktopCardList<>(model, 116);
        components.cardList(list, false);
        var search = new JTextField();
        var refresh = components.secondaryButton("Refresh");
        var surface = new ModCatalogSurface(components, messages, list, search, refresh, true);
        var detail = components.transparent(new BorderLayout());
        detail.add(new JTextArea("Actual detail", 20, 30));
        surface.details(detail);
        surface.count(count);
        var actions = components.transparent(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), UIScale.scale(4)));
        for (String text : new String[]{"View logs", "More actions", "View changes", "Save only", "Save and restart"})
            actions.add(components.secondaryButton(text));
        surface.footer(actions);
        return new Fixture(surface, components, list, search, refresh, actions);
    }

    private static int edge(Component root, Component child) {
        return SwingUtilities.convertPoint(child, child.getWidth(), 0, root).x;
    }

    private static void assertLiteral(Component root) {
        if (root instanceof JLabel label && label.getText() != null && label.getText().startsWith("<html>")) {
            assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
            assertNull(label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey));
        }
        if (root instanceof Container container)
            for (var child : container.getComponents()) assertLiteral(child);
    }

    private static void assertIndicator(JScrollBar bar, boolean overflowing) {
        var image = new BufferedImage(bar.getWidth(), bar.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        bar.paint(graphics);
        graphics.dispose();
        int thumb = UIManager.getColor("ScrollBar.thumb").getRGB();
        int colored = 0, first = -1, last = -1;
        for (int y = 0; y < image.getHeight(); y++)
            if (image.getRGB(image.getWidth() - UIScale.scale(3), y) == thumb) {
                colored++;
                if (first < 0) first = y;
                last = y;
            }
        assertTrue(colored > 0);
        if (overflowing) assertTrue(colored < image.getHeight() * .85);
        else assertTrue(colored >= image.getHeight() - UIScale.scale(8));
        int width = 0;
        for (int x = 0; x < image.getWidth(); x++)
            if (image.getRGB(x, (first + last) / 2) == thumb) width++;
        assertEquals(UIScale.scale(6), width);
    }

    private static void layout(Component component) {
        for (int pass = 0; pass < 3; pass++) layoutOnce(component);
    }

    private static void layoutOnce(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (var child : container.getComponents()) layoutOnce(child);
        }
    }

    private static <T> T find(Component component, String name, Class<T> type) {
        if (name.equals(component.getName()) && type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container)
            for (var child : container.getComponents()) {
                T found = find(child, name, type);
                if (found != null) return found;
            }
        return null;
    }

    private record Fixture(ModCatalogSurface surface, DesktopPartFactory components, DesktopCardList<String> list,
                           JTextField search, JButton refresh, JPanel actions) {
    }
}

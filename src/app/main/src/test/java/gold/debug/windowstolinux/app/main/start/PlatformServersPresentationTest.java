package gold.debug.windowstolinux.app.main.start;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.server.PlatformServersPane;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformServersPresentationTest {
    @TempDir
    Path root;

    @Test
    void actualPaneShowsSelectedMetadataAndRendersThemesSizesAndEmptyStatesWithoutNetwork() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformServerServiceTest.setup(runtime);
            runtime.started();
            String[] names = {"生产服务器", "测试服务器", "备用服务器", "演示服务器：用于验证很长的服务器显示名称不会覆盖操作按钮"};
            for (int i = 0; i < names.length; i++) {
                var profile = new ServerProfile("preview-" + i,
                    i == 3 ? "long-hostname-for-layout-check.example.invalid" : "192.0.2." + (10 + i * 10),
                    i == 2 ? 2222 : 22, "deploy", "preview/" + i, names[i]);
                service.saveServerProfile(profile, "isolated-test-credential".toCharArray());
                if (i < 3) runtime.persistence().servers().recordObservation(profile.stored(), i != 2, "");
            }
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                for (int scale : new int[]{100, 150}) {
                    var window = new AtomicReference<JFrame>();
                    var view = new AtomicReference<PlatformServersPane>();
                    onEdt(() -> {
                        DesktopThemeService.install(theme, scale);
                        var frame = new JFrame();
                        var pane = new PlatformServersPane(frame, service,
                            new DesktopPartFactory(theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark()),
                            new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
                        window.set(frame);
                        view.set(pane);
                        frame.setContentPane(pane);
                        frame.setSize(1440, 790);
                        frame.setVisible(true);
                        pane.setLocked(false);
                        return null;
                    });
                    try {
                        await(() -> ((JTable) named(view.get(), "servers.table")).getRowCount() == 4
                            && named(view.get(), "servers.refresh").isEnabled());
                        onEdt(() -> {
                            var pane = view.get();
                            layout(pane);
                            layout(pane);
                            var table = (JTable) named(pane, "servers.table");
                            assertEquals(-1, table.getSelectedRow());
                            assertFalse(named(pane, "servers.details.scroll").isVisible());
                            assertEquals(named(pane, "servers.workspace").getWidth(), named(pane, "servers.inventory").getWidth());
                            assertInventoryAlignment(pane);
                            capture(pane, "servers-" + theme + "-" + scale + "-initial");
                            assertPointerSelection(pane);
                            return null;
                        });
                        for (boolean minimum : new boolean[]{false, true}) {
                            onEdt(() -> {
                                var pane = view.get();
                                pane.setSize(minimum ? 1120 : 1440, minimum ? 630 : 810);
                                var table = (JTable) named(pane, "servers.table");
                                table.setRowSelectionInterval(0, 0);
                                layout(pane);
                                layout(pane);
                                assertEquals("4 台", ((JLabel) named(pane, "servers.count")).getText());
                                assertTrue(named(pane, "servers.count").getWidth() > 0);
                                assertTrue(table.getWidth() > 0 && table.getHeight() > 0, () -> "Inventory bounds: " + table.getBounds());
                                assertEquals("生产服务器", ((JLabel) named(pane, "servers.details.title")).getText());
                                assertTrue(((JLabel) named(pane, "servers.details.endpoint")).getText().contains("192.0.2.10:22"));
                                assertTrue(named(pane, "servers.check").isEnabled());
                                assertTrue(named(pane, "servers.details.scroll").isVisible());
                                assertInventoryAlignment(pane);
                                var collapse = named(pane, "servers.collapseDetails");
                                var checkButton = named(pane, "servers.check");
                                assertEquals(checkButton.getHeight(), collapse.getHeight());
                                assertTrue(Math.abs(checkButton.getWidth() - collapse.getWidth())
                                        <= com.formdev.flatlaf.util.UIScale.scale(12),
                                    () -> "Collapse " + collapse.getSize() + " / SSH " + checkButton.getSize());
                                var icon = named(pane, "servers.details.icon");
                                assertEquals(centerY(icon, pane), centerY(collapse, pane), 1);
                                assertEquals(centerY(named(pane, "servers.inventory.header"), pane), centerY(icon, pane), 1,
                                    "Identity and collapse controls align with the title/search region");
                                var authState = (JLabel) named(pane, "servers.auth.state");
                                assertEquals("检查通过", authState.getText());
                                assertTrue(authState.getForeground().getGreen() > authState.getForeground().getRed());
                                for (int i = 0; i < 4; i++) {
                                    var value = named(pane, "servers.details.field." + i);
                                    var caption = named(pane, "servers.details.caption." + i);
                                    assertTrue(value.getWidth() > 0);
                                    assertTrue(caption.getY() + caption.getHeight() <= value.getY());
                                }
                                for (int i = 0; i < 3; i++) {
                                    var divider = (JSeparator) named(pane, "servers.details.divider." + i);
                                    assertEquals(SwingConstants.VERTICAL, divider.getOrientation());
                                    assertEquals(UIManager.getColor("Panel.background"), divider.getForeground());
                                    if (divider.isVisible()) assertTrue(divider.getHeight() > divider.getWidth());
                                }
                                boolean singleRow = named(pane, "servers.details.cell.0").getY()
                                    == named(pane, "servers.details.cell.2").getY();
                                assertEquals(singleRow, named(pane, "servers.details.divider.1").isVisible());
                                for (String action : new String[]{"servers.add", "servers.edit", "servers.check", "servers.more"}) {
                                    var button = named(pane, action);
                                    assertTrue(button.getWidth() > 0 && button.getHeight() > 0, action);
                                    assertTrue(button.getX() + button.getWidth() <= button.getParent().getWidth(), action);
                                    assertTrue(button.getY() + button.getHeight() <= button.getParent().getHeight(), action);
                                }
                                capture(pane, "servers-" + theme + "-" + scale + (minimum ? "-minimum" : "-normal"));
                                table.setRowSelectionInterval(3, 3);
                                assertTrue(((JLabel) named(pane, "servers.details.title")).getText().startsWith("演示服务器"));
                                capture(pane, "servers-" + theme + "-" + scale + (minimum ? "-minimum-long" : "-normal-long"));
                                table.setRowSelectionInterval(0, 0);
                                var inventoryScroll = (JScrollPane) named(pane, "servers.inventory.scroll");
                                int headerY = SwingUtilities.convertPoint(named(pane, "servers.add"), 0, 0, pane).y;
                                inventoryScroll.getVerticalScrollBar().setValue(inventoryScroll.getVerticalScrollBar().getMaximum());
                                assertEquals(headerY, SwingUtilities.convertPoint(named(pane, "servers.add"), 0, 0, pane).y,
                                    "Inventory scrolling must leave its toolbar fixed");
                                inventoryScroll.getVerticalScrollBar().setValue(0);
                                var scroll = (JScrollPane) named(pane, "servers.details.scroll");
                                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                                capture(pane, "servers-" + theme + "-" + scale + (minimum ? "-minimum-bottom" : "-normal-bottom"));
                                scroll.getVerticalScrollBar().setValue(0);
                                return null;
                            });
                        }
                        onEdt(() -> {
                            var pane = view.get();
                            var table = (JTable) named(pane, "servers.table");
                            var search = (JTextField) named(pane, "servers.search");
                            table.setRowSelectionInterval(3, 3);
                            search.setText("long-hostname");
                            var model = table.getModel();
                            var document = (javax.swing.text.AbstractDocument) search.getDocument();
                            int listeners = document.getDocumentListeners().length;
                            for (int zoom : new int[]{150, 100, 150, scale}) {
                                var mode = zoom == 100 ? ThemeMode.LIGHT : theme;
                                DesktopThemeService.install(mode, zoom);
                                SwingUtilities.updateComponentTreeUI(pane);
                                new DesktopPartFactory(mode == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark()).refreshTheme(pane);
                                pane.localize();
                                layout(pane);
                                layout(pane);
                                assertCardSurfaces(pane);
                                assertSame(model, table.getModel());
                                assertEquals(0, table.getSelectedRow());
                                assertEquals(1, table.getRowCount());
                                assertEquals("long-hostname", search.getText());
                                assertEquals(listeners, document.getDocumentListeners().length);
                                assertEquals(UIManager.getColor("TextField.searchBackground"), search.getBackground());
                                assertEquals(com.formdev.flatlaf.util.UIScale.scale(20), pane.getInsets().left);
                                assertTrue(((JLabel) named(pane, "servers.details.title")).getText().startsWith("演示服务器"));
                            }
                            DesktopThemeService.install(theme, scale);
                            SwingUtilities.updateComponentTreeUI(pane);
                            new DesktopPartFactory(theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark()).refreshTheme(pane);
                            pane.localize();
                            search.setText("");
                            layout(pane);
                            layout(pane);
                            table.clearSelection();
                            layout(pane);
                            layout(pane);
                            assertFalse(named(pane, "servers.check").isEnabled());
                            assertTrue(named(pane, "servers.add").isEnabled());
                            assertFalse(named(pane, "servers.details.scroll").isVisible());
                            assertEquals(named(pane, "servers.workspace").getWidth(), named(pane, "servers.inventory").getWidth());
                            assertInventoryAlignment(pane);
                            capture(pane, "servers-" + theme + "-" + scale + "-unselected");
                            ((JTextField) named(pane, "servers.search")).setText("no matching host");
                            assertEquals(0, table.getRowCount());
                            assertTrue(named(pane, "servers.empty").isVisible());
                            capture(pane, "servers-" + theme + "-" + scale + "-no-matches");
                            return null;
                        });
                    } finally {
                        onEdt(() -> {
                            view.get().close();
                            window.get().dispose();
                            return null;
                        });
                    }
                }
            }
            for (var summary : service.list()) service.delete(summary.profile().id());
            var emptyView = new AtomicReference<PlatformServersPane>();
            onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var pane = new PlatformServersPane(null, service, new DesktopPartFactory(ThemePalette.light()),
                    new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
                emptyView.set(pane);
                pane.setLocked(false);
                return null;
            });
            await(() -> named(emptyView.get(), "servers.refresh").isEnabled());
            onEdt(() -> {
                var pane = emptyView.get();
                pane.setSize(1440, 790);
                layout(pane);
                capture(pane, "servers-LIGHT-empty");
                assertTrue(named(pane, "servers.empty").isVisible());
                assertTrue(named(pane, "servers.add").isEnabled());
                assertInventoryAlignment(pane);
                pane.close();
                return null;
            });
        }
    }

    private static void assertInventoryAlignment(JComponent pane) {
        assertCardSurfaces(pane);
        assertEquals(UIManager.getColor("TextField.searchBackground"), named(pane, "servers.search").getBackground());
        assertEquals(UIManager.getFont("Label.font").getFamily(), named(pane, "servers.output").getFont().getFamily());
        assertEquals(com.formdev.flatlaf.util.UIScale.scale(12), named(pane, "servers.output").getFont().getSize());
        var table = (JTable) named(pane, "servers.table");
        assertEquals(Boolean.TRUE, table.getClientProperty(DesktopListCard.OUTLINED));
        for (int row = 0; row < table.getRowCount(); row++) {
            var card = (JComponent) table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
            card.setSize(table.getColumnModel().getColumn(0).getWidth(), table.getRowHeight());
            layout(card);
            var title = named(card, "servers.card.name");
            var endpoint = named(card, "servers.card.endpoint");
            assertEquals(UIManager.getFont("Label.font").getFamily(), title.getFont().getFamily());
            assertEquals(UIManager.getFont("Label.font").getFamily(), endpoint.getFont().getFamily());
            assertEquals(com.formdev.flatlaf.util.UIScale.scale(14), endpoint.getFont().getSize());
            int gap = SwingUtilities.convertPoint(endpoint, 0, 0, card).y
                - SwingUtilities.convertPoint(title, 0, title.getHeight(), card).y;
            assertTrue(gap >= 0 && gap <= com.formdev.flatlaf.util.UIScale.scale(8),
                () -> "Compact title/connection spacing: " + gap);
            var badge = (JLabel) named(card, "servers.card.state");
            assertEquals(com.formdev.flatlaf.util.UIScale.scale(12), badge.getFont().getSize());
            assertEquals(SwingConstants.CENTER, badge.getHorizontalAlignment());
            assertEquals(badge.getPreferredSize(), badge.getSize(), "State capsule keeps its natural size");
        }
        var scroll = (JScrollPane) named(pane, "servers.inventory.scroll");
        var bar = scroll.getVerticalScrollBar();
        assertTrue(bar.isVisible(), "Inventory scrollbar is always visible");
        int right = SwingUtilities.convertPoint(bar, bar.getWidth(), 0, pane).x;
        for (String action : new String[]{"servers.add", "servers.refresh"}) {
            var button = named(pane, action);
            assertEquals(right, SwingUtilities.convertPoint(button, button.getWidth(), 0, pane).x, action);
        }
    }

    private static void assertCardSurfaces(JComponent pane) {
        var cardColor = UIManager.getColor("List.cardBackground");
        var pageColor = UIManager.getColor("Panel.background");
        assertEquals(pageColor, pane.getBackground());
        var inventory = (JComponent) named(pane, "servers.inventory");
        assertEquals(com.formdev.flatlaf.util.UIScale.scale(new Insets(14, 16, 14, 16)), inventory.getInsets());
        assertSurface(inventory, cardColor, cardColor);
        var table = (JTable) named(pane, "servers.table");
        var scroll = (JScrollPane) named(pane, "servers.inventory.scroll");
        assertSame(table, scroll.getViewport().getView());
        assertEquals(cardColor, table.getBackground());
        assertEquals(cardColor, scroll.getViewport().getBackground());
        assertEquals(cardColor, scroll.getVerticalScrollBar().getBackground());
        var detail = (JComponent) named(pane, "servers.details.outer");
        assertEquals(named(pane, "servers.details.scroll").isVisible(), detail.isVisible());
        assertEquals(com.formdev.flatlaf.util.UIScale.scale(new Insets(14, 16, 14, 16)), detail.getInsets());
        if (detail.isVisible()) {
            assertSurface(detail, cardColor, cardColor);
            var detailScroll = (JScrollPane) named(pane, "servers.details.scroll");
            assertSame(detail, detailScroll.getParent());
            assertEquals(cardColor, detailScroll.getViewport().getBackground());
            assertEquals(cardColor, detailScroll.getViewport().getView().getBackground());
            assertEquals(com.formdev.flatlaf.util.UIScale.scale(16), detailScroll.getX());
            assertEquals(com.formdev.flatlaf.util.UIScale.scale(14), detailScroll.getY());
            for (String name : new String[]{"servers.details.fields", "servers.details.actions", "servers.records"})
                assertSurface((JComponent) named(pane, name), cardColor, pageColor);
        }
    }

    private static void assertSurface(JComponent surface, Color fill, Color edge) {
        assertEquals(fill, surface.getBackground(), surface.getName());
        assertTrue(surface.getWidth() > 0 && surface.getHeight() > 0, surface.getName());
        var image = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(UIManager.getColor("Panel.background"));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        surface.paint(graphics);
        graphics.dispose();
        int center = surface.getWidth() / 2;
        assertEquals(edge, new Color(image.getRGB(center, 0)), surface.getName() + " top contour");
        assertEquals(fill, new Color(image.getRGB(center, com.formdev.flatlaf.util.UIScale.scale(4))),
            surface.getName() + " inner surface");
    }

    private static void assertPointerSelection(JComponent pane) {
        var table = (JTable) named(pane, "servers.table");
        var renderer = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
        var handle = DesktopListCard.handleBounds(renderer, table.getColumnModel().getColumn(0).getWidth(),
            table.getRowHeight());
        assertFalse(handle.isEmpty());
        click(table, new Point(handle.x + handle.width / 2, handle.y + handle.height / 2));
        assertEquals(-1, table.getSelectedRow(), "The outlined handle still only starts ordering");
        click(table, new Point(table.getWidth() / 2, table.getRowHeight() - 1));
        assertEquals(-1, table.getSelectedRow(), "The outer card must not make list gaps selectable");
        click(table, new Point(table.getWidth() / 2, table.getRowHeight() / 2));
        assertEquals("生产服务器", ((JLabel) named(pane, "servers.details.title")).getText());
        assertEquals(0, table.getSelectedRow());
        click(table, new Point(table.getWidth() / 2, table.getRowHeight() / 2));
        assertEquals(-1, table.getSelectedRow(), "Clicking the selected outlined card collapses details");
        layout(pane);
        layout(pane);
        assertFalse(named(pane, "servers.details.outer").isVisible());
        assertEquals(named(pane, "servers.workspace").getWidth(), named(pane, "servers.inventory").getWidth());
    }

    private static void click(JTable table, Point point) {
        long when = System.currentTimeMillis();
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK,
            point.x, point.y, 1, false, MouseEvent.BUTTON1));
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, when + 1, 0,
            point.x, point.y, 1, false, MouseEvent.BUTTON1));
    }

    private static int centerY(Component component, Component root) {
        return SwingUtilities.convertPoint(component, 0, component.getHeight() / 2, root).y;
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (var child : container.getComponents()) layout(child);
        }
    }

    private static void capture(JComponent pane, String name) {
        try {
            var image = new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            pane.paint(g);
            g.dispose();
            var folder = Path.of(System.getProperty("server.ui.capture", "target/server-ui-preview"));
            Files.createDirectories(folder);
            ImageIO.write(image, "png", folder.resolve(name + ".png").toFile());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}

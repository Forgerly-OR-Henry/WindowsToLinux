package gold.debug.windowstolinux.mod.deployment.ui.style;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ListResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.AbstractDocument;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import org.junit.jupiter.api.Test;

class DeploymentCardPaletteTest {
    @Test
    void publicCardsRenderContentGrayAndKeepSelectedBlueAcrossLiveThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemeMode previousTheme = FlatLaf.isLafDark() ? ThemeMode.DARK : ThemeMode.LIGHT;
            int previousZoom = Math.round(UIScale.getZoomFactor() * 100);
            try {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var messages = new ListResourceBundle() {
                    @Override
                    protected Object[][] getContents() {
                        return new Object[0][0];
                    }
                };
                var draft = new JTextField("未保存的部署草稿");
                draft.select(2, 5);
                var document = (AbstractDocument) draft.getDocument();
                var options = new JComboBox<>(new String[]{"第一项", "第二项"});
                options.setSelectedIndex(1);
                var model = options.getModel();
                var action = new JButton("保留操作");
                var actions = new AtomicInteger();
                action.addActionListener(event -> actions.incrementAndGet());
                var content = new JPanel(new BorderLayout(8, 8));
                content.add(draft, BorderLayout.CENTER);
                content.add(options, BorderLayout.WEST);
                content.add(action, BorderLayout.EAST);
                var detail = DeploymentPageStyle.card(null, content);
                var inventory = DeploymentPageStyle.card(null, new JLabel("库存卡片"));
                inventory.putClientProperty("deployment.style.inventory", true);
                var selected = DeploymentPageStyle.card(null, new JLabel("选中库存卡片"));
                selected.putClientProperty("deployment.style.inventory", true);
                selected.putClientProperty("deployment.style.selected", true);
                var inner = DeploymentPageStyle.section(null, new JLabel("内层浅框"));
                var board = new JPanel(null);
                board.add(detail);
                board.add(inventory);
                board.add(selected);
                board.add(inner);

                for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int zoom : new int[]{100, 150}) {
                        DesktopThemeService.install(theme, zoom);
                        SwingUtilities.updateComponentTreeUI(board);
                        DeploymentPageStyle.refresh(board, messages);
                        board.setOpaque(true);
                        board.setBackground(UIManager.getColor("Panel.background"));
                        int width = UIScale.scale(640);
                        int rowHeight = UIScale.scale(120);
                        board.setSize(width, rowHeight * 4 + 20);
                        for (int index = 0; index < board.getComponentCount(); index++)
                            board.getComponent(index).setBounds(10, rowHeight * index + 10, width - 20, rowHeight - 12);
                        layout(board);

                        Color surface = gray(theme == ThemeMode.DARK ? 20 : 250);
                        assertEquals(UIScale.scale(20), DeploymentPageStyle.scale(20));
                        Color selectedSurface = UIManager.getColor("List.selectionBackground");
                        assertEquals(surface, UIManager.getColor("Table.background"));
                        assertSurface(detail, surface);
                        assertSurface(inventory, surface);
                        assertTrue(selectedSurface.getBlue() > selectedSurface.getRed());
                        assertSurface(selected, selectedSurface);
                        assertSurface(inner, surface);
                        assertEquals(gray(theme == ThemeMode.DARK ? 50 : 220).getRGB(),
                            render(inner).getRGB(0, inner.getHeight() / 2));
                        assertEquals(gray(theme == ThemeMode.DARK ? 30 : 240), action.getBackground());
                        assertSame(content, ((BorderLayout) detail.getLayout()).getLayoutComponent(BorderLayout.CENTER));
                        assertSame(document, draft.getDocument());
                        assertEquals("未保存的部署草稿", draft.getText());
                        assertEquals(2, draft.getSelectionStart());
                        assertEquals(5, draft.getSelectionEnd());
                        assertSame(model, options.getModel());
                        assertEquals(1, options.getSelectedIndex());
                        assertEquals(1, action.getActionListeners().length);
                        action.doClick(0);

                        var path = Path.of("target", "ui-neutral", "deployment-cards-" + theme + "-" + zoom + ".png");
                        Files.createDirectories(path.getParent());
                        assertTrue(ImageIO.write(render(board), "png", path.toFile()));
                    }
                assertEquals(4, actions.get());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                DesktopThemeService.install(previousTheme, previousZoom);
            }
        });
    }

    private static Color gray(int value) {
        return new Color(value, value, value);
    }

    private static void assertSurface(JPanel card, Color expected) {
        var image = render(card);
        assertEquals(expected.getRGB(), image.getRGB(8, card.getHeight() / 2),
            () -> "Card did not render expected content surface " + expected);
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
}

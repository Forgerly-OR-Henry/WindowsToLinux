package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.AbstractDocument;

import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import org.junit.jupiter.api.Test;

class DesktopNeutralPaletteTest {
    @Test
    void actualControlSurfacesUseNeutralLevelsAcrossThemesAndZoom() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int zoom : new int[]{100, 150}) {
                        DesktopThemeService.install(theme, zoom);
                        boolean dark = theme == ThemeMode.DARK;
                        var palette = dark ? ThemePalette.dark() : ThemePalette.light();
                        var factory = new DesktopPartFactory(palette);
                        Color page = gray(dark ? 30 : 230);
                        Color card = gray(dark ? 10 : 250);
                        Color control = gray(dark ? 50 : 210);
                        Color searchColor = dark ? control : card;
                        assertEquals(page, palette.sidebarBackground());
                        var board = factory.pagePanel();
                        board.setLayout(null);
                        int width = com.formdev.flatlaf.util.UIScale.scale(500);
                        int row = com.formdev.flatlaf.util.UIScale.scale(70);
                        board.setSize(width, row * 9);
                        assertSurface(board, page);

                        var search = new JTextField();
                        factory.styleSearchField(search);
                        var disabled = new JButton("禁用操作");
                        disabled.setEnabled(false);
                        var disabledInput = new JTextField();
                        disabledInput.setEnabled(false);
                        JComponent[] controls = {factory.card(null), factory.secondaryButton("普通按钮"),
                            search, new JTextField(), new JComboBox<>(new String[]{"选项"}),
                            disabled, disabledInput, factory.primaryButton("主要操作")};
                        Color[] expected = {card, control, searchColor, control, control, control, control, palette.accent()};
                        String[] names = {"卡片 / 详情", "次要按钮", "搜索框", "普通输入框", "下拉框", "禁用按钮", "禁用输入框", "蓝色主按钮"};
                        for (int index = 0; index < controls.length; index++) {
                            var label = new JLabel(names[index]);
                            label.setBounds(20, row * index + 10, width / 3, row - 20);
                            board.add(label);
                            var component = controls[index];
                            component.setBounds(width / 3, row * index + 10, width * 2 / 3 - 20, row - 20);
                            board.add(component);
                            layout(component);
                            assertSurface(component, expected[index]);
                        }
                        var popup = new JPopupMenu();
                        popup.add(new JMenuItem("菜单项"));
                        for (JComponent component : new JComponent[]{new JTree(), new JToolTip(), popup, new JTable(1, 1)}) {
                            component.setSize(width / 2, row);
                            layout(component);
                            assertSurface(component, card);
                        }
                        assertEquals(card, UIManager.getColor("Tree.textBackground"));
                        var tableHeader = new JTable(1, 1).getTableHeader();
                        tableHeader.setSize(width, row);
                        assertSurface(tableHeader, page);
                        for (String key : new String[]{"Panel.background", "ScrollBar.track", "TitlePane.background", "TitlePane.inactiveBackground"})
                            assertEquals(page, UIManager.getColor(key), key);
                        var path = Path.of("target", "ui-gray", "controls-" + theme + "-" + zoom + ".png");
                        Files.createDirectories(path.getParent());
                        ImageIO.write(render(board), "png", path.toFile());
                    }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
            }
        });
    }

    @Test
    void liveThemeRefreshKeepsDraftsSelectionAndActionsWithSearchException() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var factory = new DesktopPartFactory(ThemePalette.light());
                var page = factory.pagePanel();
                var card = factory.card(null);
                var search = new JTextField("未保存的搜索草稿");
                factory.styleSearchField(search);
                search.select(2, 5);
                var document = (AbstractDocument) search.getDocument();
                var listeners = document.getDocumentListeners().length;
                var combo = new JComboBox<>(new String[]{"第一项", "第二项"});
                combo.setSelectedIndex(1);
                var model = combo.getModel();
                var action = factory.secondaryButton("操作");
                var clicks = new AtomicInteger();
                action.addActionListener(event -> clicks.incrementAndGet());
                page.add(card);
                page.add(search);
                page.add(combo);
                page.add(action);
                for (ThemeMode theme : new ThemeMode[]{ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.DARK}) {
                    DesktopThemeService.install(theme, 150);
                    SwingUtilities.updateComponentTreeUI(page);
                    factory.palette(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
                    factory.refreshTheme(page);
                    boolean dark = theme == ThemeMode.DARK;
                    assertEquals(gray(dark ? 30 : 230), page.getBackground());
                    assertEquals(gray(dark ? 10 : 250), card.getBackground());
                    assertEquals(gray(dark ? 50 : 210), action.getBackground());
                    assertEquals(gray(dark ? 50 : 210), combo.getBackground());
                    assertEquals(gray(dark ? 50 : 250), search.getBackground());
                    assertEquals(UIManager.getColor("TextField.foreground"), search.getForeground());
                    assertEquals(UIManager.getColor("TextField.caretForeground"), search.getCaretColor());
                    assertSame(document, search.getDocument());
                    assertEquals(listeners, document.getDocumentListeners().length);
                    assertEquals("未保存的搜索草稿", search.getText());
                    assertEquals(2, search.getSelectionStart());
                    assertEquals(5, search.getSelectionEnd());
                    assertSame(model, combo.getModel());
                    assertEquals(1, combo.getSelectedIndex());
                    action.doClick();
                }
                assertEquals(3, clicks.get());
            } finally {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
            }
        });
    }

    private static Color gray(int value) {
        return new Color(value, value, value);
    }

    private static void assertSurface(JComponent component, Color expected) {
        var image = render(component);
        int matching = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if (image.getRGB(x, y) == expected.getRGB()) matching++;
        assertTrue(matching > image.getWidth() * image.getHeight() / 4,
            () -> component.getClass().getSimpleName() + " did not render surface " + expected);
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

package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import org.junit.jupiter.api.Test;

class DesktopThemeServiceTest {
    @Test
    void ordinaryAndTechnicalTextShareChineseUiFamilyAcrossThemesAndZoom() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int zoom : new int[]{100, 150}) {
                        DesktopThemeService.install(theme, zoom);
                        assertEquals("Microsoft YaHei UI", com.formdev.flatlaf.FlatLaf.getPreferredFontFamily());
                        var expected = UIManager.getFont("Label.font").getFamily(java.util.Locale.ROOT);
                        boolean preferredInstalled = java.util.Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment()
                                .getAvailableFontFamilyNames(java.util.Locale.ROOT))
                            .anyMatch("Microsoft YaHei UI"::equalsIgnoreCase);
                        if (preferredInstalled) assertEquals("Microsoft YaHei UI", expected);
                        var root = new javax.swing.JPanel();
                        var output = gold.debug.windowstolinux.app.ui.control.DesktopPartFactory.outputArea();
                        var controls = new javax.swing.JComponent[]{new javax.swing.JLabel("服务器 SSH 认证 Aa09"),
                            new javax.swing.JButton("检查 SSH"), new javax.swing.JTextField("服务器 192.0.2.1"),
                            new javax.swing.JComboBox<>(new String[]{"服务器"}), new javax.swing.JList<>(new String[]{"服务器"}),
                            new javax.swing.JTable(1, 1), new javax.swing.JMenu("菜单"), new javax.swing.JToolTip(), output};
                        for (var control : controls) {
                            root.add(control);
                            assertEquals(expected, control.getFont().getFamily(java.util.Locale.ROOT));
                            if (preferredInstalled)
                                assertEquals(-1, control.getFont().canDisplayUpTo("服务器认证命令日志Aa09"));
                        }
                        assertEquals(com.formdev.flatlaf.util.UIScale.scale(13), controls[0].getFont().getSize());
                        assertEquals(com.formdev.flatlaf.util.UIScale.scale(12), output.getFont().getSize());
                        SwingUtilities.updateComponentTreeUI(root);
                        new gold.debug.windowstolinux.app.ui.control.DesktopPartFactory(
                            theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light()).refreshTheme(root);
                        for (var control : controls)
                            assertEquals(expected, control.getFont().getFamily(java.util.Locale.ROOT));
                        assertEquals(com.formdev.flatlaf.util.UIScale.scale(12), output.getFont().getSize());
                    }
            } finally {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
            }
        });
    }

    @Test
    void wrappedDescriptionsStayInsideActualWidthAndGrowWhenNarrowed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var components = new gold.debug.windowstolinux.app.ui.control.DesktopPartFactory(ThemePalette.light());
            var description = components.description("Save connection settings, then check SSH authentication when needed. "
                + "A check does not inspect or change the remote environment.");
            description.setBounds(0, 0, 420, 100);
            int wideHeight = description.getPreferredSize().height;
            assertTrue(description.getPreferredSize().width <= 420);
            description.setBounds(0, 0, 220, 100);
            assertTrue(description.getPreferredSize().width <= 220);
            assertTrue(description.getPreferredSize().height > wideHeight);
        });
    }

    @Test
    void dropdownWidthsStayStableAcrossSelectionsTextChangesAndAsyncOptions() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            String previousScale = System.getProperty("flatlaf.uiScale");
            try {
                for (String scale : new String[]{"1.0", "1.5"})
                    for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                        System.setProperty("flatlaf.uiScale", scale);
                        DesktopThemeService.install(theme);
                        var choices = new javax.swing.JComboBox<>(new String[]{"A", "Initial longest choice"});
                        var delayed = new javax.swing.JComboBox<String>();
                        var content = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
                        content.add(choices);
                        content.add(delayed);
                        var window = new javax.swing.JFrame();
                        window.setContentPane(content);
                        window.setSize(700, 200);
                        window.setVisible(true);
                        try {
                            assertInstanceOf(DesktopComboBoxUi.class, choices.getUI());
                            assertInstanceOf(DesktopComboBoxUi.class, delayed.getUI());
                            int choiceWidth = choices.getWidth(), delayedWidth = delayed.getWidth();
                            choices.setSelectedIndex(1);
                            layout(content);
                            assertEquals(choiceWidth, choices.getWidth());
                            String longer = "Server refreshed with a much longer descriptive name than the original options";
                            choices.setModel(new javax.swing.DefaultComboBoxModel<>(new String[]{"B", longer}));
                            choices.setSelectedIndex(1);
                            layout(content);
                            assertEquals(choiceWidth, choices.getWidth(),
                                "Replacing labels must not resize an existing dropdown");
                            delayed.addItem(longer);
                            layout(content);
                            assertEquals(delayedWidth, delayed.getWidth(),
                                "Loading options after opening the page must not shift its layout");
                            choices.showPopup();
                            var popup = (javax.swing.plaf.basic.BasicComboPopup) choices.getUI()
                                .getAccessibleChild(choices, 0);
                            assertTrue(popup.getWidth() > choices.getWidth(),
                                "The expanded list must still fit longer options");
                            assertEquals(longer, choices.getSelectedItem());
                            choices.hidePopup();
                            choices.setEditable(true);
                            choices.setSelectedItem(longer + longer);
                            layout(content);
                            assertEquals(choiceWidth, choices.getWidth(),
                                "Editing text must retain the dropdown width");
                            assertEquals(longer + longer, choices.getSelectedItem());
                        } finally {
                            choices.hidePopup();
                            window.dispose();
                        }
                    }
            } finally {
                if (previousScale == null)
                    System.clearProperty("flatlaf.uiScale");
                else
                    System.setProperty("flatlaf.uiScale", previousScale);
                DesktopThemeService.install(ThemeMode.LIGHT);
            }
        });
    }

    @Test
    void sharedComboStylePreservesLocalizedRenderersPopupSelectionAndThemeChanges() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (String language : new String[]{"zh-CN", "en-US"}) {
                    DesktopThemeService.install(theme);
                    var messages = new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(
                        gold.debug.windowstolinux.app.ui.language.MessageCatalog.forLanguageTag(language));
                    var combo = new javax.swing.JComboBox<>(ThemeMode.values());
                    combo.setSelectedIndex(1);
                    messages.localize(combo, "theme.");
                    var renderer = combo.getRenderer();
                    var content = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 16, 16));
                    content.add(combo);
                    var window = new javax.swing.JFrame();
                    window.setContentPane(content);
                    window.setSize(400, 240);
                    window.setVisible(true);
                    try {
                        combo.showPopup();
                        var popup = (javax.swing.plaf.basic.BasicComboPopup) combo.getUI().getAccessibleChild(combo, 0);
                        var list = popup.getList();
                        assertEquals(1, list.getSelectedIndex());
                        assertTrue(
                            list.getCellBounds(0, 0).height >= list.getFontMetrics(list.getFont()).getHeight() + 8);
                        var label = (javax.swing.JLabel) list.getCellRenderer().getListCellRendererComponent(list,
                            combo.getItemAt(1), 1, true, false);
                        assertEquals(messages.text("theme.light"), label.getText());
                        assertEquals(combo.getItemAt(1), combo.getSelectedItem());
                        popup.setSize(popup.getPreferredSize());
                        layout(popup);
                        assertTrue(popup.getHeight() > list.getCellBounds(0, 2).height);
                        int width = Math.max(combo.getWidth(), popup.getWidth()) + 32,
                            height = combo.getHeight() + popup.getHeight() + 40;
                        var image = new java.awt.image.BufferedImage(width, height,
                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        graphics.setColor(theme == ThemeMode.DARK
                            ? ThemePalette.dark().pageBackground()
                            : ThemePalette.light().pageBackground());
                        graphics.fillRect(0, 0, width, height);
                        var controlGraphics = graphics.create(16, 12, combo.getWidth(), combo.getHeight());
                        combo.printAll(controlGraphics);
                        controlGraphics.dispose();
                        var popupGraphics = graphics.create(16, combo.getHeight() + 20, popup.getWidth(),
                            popup.getHeight());
                        popup.printAll(popupGraphics);
                        popupGraphics.dispose();
                        graphics.dispose();
                        var path = java.nio.file.Path
                            .of("target/visual-checks/dropdown-" + theme + "-" + language + ".png");
                        java.nio.file.Files.createDirectories(path.getParent());
                        javax.imageio.ImageIO.write(image, "png", path.toFile());
                        combo.hidePopup();
                        combo.setSelectedIndex(2);
                        SwingUtilities.updateComponentTreeUI(content);
                        assertSame(renderer, combo.getRenderer());
                        assertEquals(2, combo.getSelectedIndex());
                        combo.setEnabled(false);
                        assertFalse(combo.isEnabled());
                        var editable = new javax.swing.JComboBox<>(new String[]{"first", "second"});
                        editable.setEditable(true);
                        editable.setSelectedItem("custom");
                        SwingUtilities.updateComponentTreeUI(editable);
                        assertEquals("custom", editable.getSelectedItem());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    } finally {
                        combo.hidePopup();
                        window.dispose();
                    }
                }
            DesktopThemeService.install(ThemeMode.LIGHT);
        });
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents())
            if (child instanceof java.awt.Container nested)
                layout(nested);
    }

    @Test
    void installsFlatLightLookAndFeel() {
        DesktopThemeService.install(ThemeMode.LIGHT);

        assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel());
    }

    @Test
    void sharedControlsPaintRoundedEdgesAndDistinctButtonStatesAcrossThemesAndZoom() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int zoom : new int[]{100, 150}) {
                        DesktopThemeService.install(theme, zoom);
                        var palette = theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
                        var button = new javax.swing.JButton();
                        for (javax.swing.JComponent control : new javax.swing.JComponent[]{button,
                            new javax.swing.JTextField(), new javax.swing.JComboBox<>(new String[]{""})}) {
                            var rendered = paintControl(control, palette.pageBackground());
                            int surface = rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2);
                            assertNotEquals(palette.pageBackground().getRGB(), surface);
                            assertTrue(surfaceInset(rendered, com.formdev.flatlaf.util.UIScale.scale(2), surface)
                                    > surfaceInset(rendered, rendered.getHeight() / 2, surface),
                                control.getClass().getSimpleName() + " must expose its rounded top corners");
                        }
                        int normal = centerColor(paintControl(button, palette.pageBackground()));
                        button.getModel().setRollover(true);
                        int hover = centerColor(paintControl(button, palette.pageBackground()));
                        assertNotEquals(normal, hover, "Hover must visibly respond in both themes");
                        button.getModel().setArmed(true);
                        button.getModel().setPressed(true);
                        int pressed = centerColor(paintControl(button, palette.pageBackground()));
                        assertNotEquals(hover, pressed, "Pressing must be visibly distinct from hover");
                        button.setEnabled(false);
                        assertNotEquals(pressed, centerColor(paintControl(button, palette.pageBackground())),
                            "Disabled controls must retain a distinct state");
                    }
            } finally {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
            }
        });
    }

    private static java.awt.image.BufferedImage paintControl(javax.swing.JComponent control,
                                                             java.awt.Color backdrop) {
        var parent = new javax.swing.JPanel(null);
        parent.setBackground(backdrop);
        parent.add(control);
        var size = com.formdev.flatlaf.util.UIScale.scale(new java.awt.Dimension(160, 40));
        control.setBounds(0, 0, size.width, size.height);
        control.doLayout();
        var image = new java.awt.image.BufferedImage(size.width, size.height,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(backdrop);
        graphics.fillRect(0, 0, size.width, size.height);
        control.printAll(graphics);
        graphics.dispose();
        return image;
    }

    @Test
    void multilineSurfacesKeepTextSelectionAndTransparentLabelsWhenRounded() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                    DesktopThemeService.install(theme, 100);
                    var backdrop = (theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light()).pageBackground();
                    var area = new javax.swing.JTextArea("Server connected\nTask ready");
                    var document = area.getDocument();
                    var margin = new java.awt.Insets(8, 8, 8, 8);
                    area.setMargin(margin);
                    area.setBackground(new java.awt.Color(120, 132, 143));
                    area.setForeground(java.awt.Color.WHITE);
                    area.setSelectionColor(java.awt.Color.MAGENTA);
                    area.select(0, 6);
                    area.getCaret().setSelectionVisible(true);
                    var rendered = paintControl(area, backdrop);
                    assertEquals(backdrop.getRGB(), rendered.getRGB(0, 0),
                        "Opaque multiline surfaces must clear their rounded corners to the parent surface");
                    assertEquals(area.getBackground().getRGB(), rendered.getRGB(80, 37),
                        "Explicit text area backgrounds must remain visible inside the rounded surface");
                    assertTrue(containsColor(rendered, area.getForeground().getRGB()), "Native text must still render");
                    assertTrue(containsColor(rendered, area.getSelectionColor().getRGB()), "Native selection must still render");
                    assertSame(document, area.getDocument());
                    assertEquals("Server connected\nTask ready", area.getText());
                    assertEquals(0, area.getSelectionStart());
                    assertEquals(6, area.getSelectionEnd());
                    assertEquals(margin, area.getMargin());
                    area.setOpaque(false);
                    rendered = paintControl(area, backdrop);
                    assertEquals(backdrop.getRGB(), rendered.getRGB(80, 37),
                        "Transparent information labels must not acquire a filled surface");
                    assertTrue(containsColor(rendered, area.getForeground().getRGB()));
                    assertSame(document, area.getDocument());
                }
            } finally {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
            }
        });
    }

    private static boolean containsColor(java.awt.image.BufferedImage image, int color) {
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if (image.getRGB(x, y) == color)
                    return true;
        return false;
    }

    private static int centerColor(java.awt.image.BufferedImage image) {
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }

    private static int surfaceInset(java.awt.image.BufferedImage image, int row, int surfaceColor) {
        for (int x = 0; x < image.getWidth() / 2; x++)
            if (image.getRGB(x, row) == surfaceColor)
                return x;
        throw new AssertionError("Missing control surface in painted row " + row);
    }

    @Test
    void installsFlatDarkLookAndFeelForTheDarkAppearance() {
        DesktopThemeService.install(ThemeMode.DARK);

        assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel());
        assertEquals(ThemeMode.LIGHT, SystemThemeResolver.effectiveTheme(ThemeMode.LIGHT));
        assertEquals(ThemeMode.DARK, SystemThemeResolver.effectiveTheme(ThemeMode.DARK));
    }

    @Test
    void keepsNavigationFullHeightAlongsideTheWorkspace() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<PlatformFrame> frame = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            var security = (gold.debug.windowstolinux.app.service.security.SecurityAccess) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[]{
                        gold.debug.windowstolinux.app.service.security.SecurityAccess.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("securityState"))
                            return new gold.debug.windowstolinux.app.service.security.DesktopSecurityState(
                                true, true, false, 0, false);
                        throw new AssertionError(method);
                    });
            PlatformFrame desktopFrame = new PlatformFrame(security,
                gold.debug.windowstolinux.app.ui.language.MessageCatalog.forLanguageTag("en"), ThemePalette.light(),
                new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false),
                gold.debug.windowstolinux.app.ui.error.FailureReportStore.disabled());
            frame.set(desktopFrame);
            var root = desktopFrame.getContentPane();
            root.setSize(1060, 680);
            root.doLayout();
            var shell = (javax.swing.JPanel) root.getComponent(0);
            shell.doLayout();
            var layout = (java.awt.BorderLayout) shell.getLayout();
            var sidebar = layout.getLayoutComponent(java.awt.BorderLayout.WEST);
            var workspace = layout.getLayoutComponent(java.awt.BorderLayout.CENTER);
            assertEquals(0, sidebar.getY());
            assertEquals(workspace.getHeight(), sidebar.getHeight());
            assertEquals(sidebar.getX() + sidebar.getWidth(), workspace.getX());
        });

        SwingUtilities.invokeAndWait(() -> frame.get().dispose());
    }
}

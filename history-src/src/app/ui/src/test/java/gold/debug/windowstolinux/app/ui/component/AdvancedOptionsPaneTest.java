package gold.debug.windowstolinux.app.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import org.junit.jupiter.api.Test;

class AdvancedOptionsPaneTest {
    @Test
    void longLabelsWrapWithoutClippingHelpOrInputsAcrossWidthsLanguagesAndScales() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        String previousScale = System.getProperty("flatlaf.uiScale");
        try {
            for (String scale : java.util.List.of("1.0", "1.25", "1.5")) {
                for (String language : java.util.List.of("zh-CN", "en")) {
                    SwingUtilities.invokeAndWait(() -> {
                        System.setProperty("flatlaf.uiScale", scale);
                        DesktopThemeService.install(ThemeMode.LIGHT);
                        var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag(language));
                        var pane = new AdvancedOptionsPane(new JPanel(),
                                new DesktopComponentFactory(ThemePalette.light()), messages);
                        var keys = java.util.List.of("field.containerPorts", "field.containerVolumes",
                                "field.configurationEntries", "field.databaseReviewMode", "field.databaseDetails",
                                "field.secretReferences");
                        var inputs = new java.util.ArrayList<JTextField>();
                        for (String key : keys) {
                            JTextField input = new JTextField();
                            inputs.add(input);
                            pane.field(key, input);
                        }
                        JFrame frame = new JFrame();
                        JScrollPane drawer = (JScrollPane) pane.drawer();
                        frame.setContentPane(drawer);
                        drawer.setVisible(true);
                        frame.setBounds(0, 0, 320, 680);
                        frame.setVisible(true);
                        try {
                            int narrowHeight = 0;
                            for (int width : new int[]{320, 240, 420, 320}) {
                                frame.setSize(width, 680);
                                frame.validate();
                                RepaintManager.currentManager(frame).validateInvalidComponents();
                                frame.validate();
                                Container fields = (Container) drawer.getViewport().getView();
                                int previousBottom = 0;
                                for (int index = 0; index < inputs.size(); index++) {
                                    Container row = (Container) fields.getComponent(index);
                                    JTextArea label = descendants(row).filter(JTextArea.class::isInstance)
                                            .map(JTextArea.class::cast).filter(area -> !area.isEditable()).findFirst()
                                            .orElseThrow();
                                    JButton help = descendants(row).filter(JButton.class::isInstance)
                                            .map(JButton.class::cast).findFirst().orElseThrow();
                                    assertEquals(messages.text(keys.get(index)), label.getText());
                                    assertEquals(label.getText(),
                                            inputs.get(index).getAccessibleContext().getAccessibleName());
                                    var last = label.modelToView2D(label.getDocument().getLength() - 1);
                                    assertNotNull(last);
                                    assertTrue(last.getMaxY() <= label.getHeight(),
                                            "Last line must fit: " + label.getText());
                                    assertTrue(last.getMaxX() <= label.getWidth(),
                                            "Last character must fit: " + label.getText());
                                    Rectangle helpBounds = SwingUtilities.convertRectangle(help.getParent(),
                                            help.getBounds(), fields);
                                    assertTrue(helpBounds.getMaxX() <= drawer.getViewport().getWidth(),
                                            "Help must remain inside the drawer");
                                    assertTrue(help.getWidth() > 0 && help.getHeight() > 0 && help.isFocusable());
                                    assertTrue(row.getY() >= previousBottom, "Wrapped fields must not overlap");
                                    previousBottom = row.getY() + row.getHeight();
                                    Rectangle labelBounds = SwingUtilities.convertRectangle(label.getParent(),
                                            label.getBounds(), row);
                                    assertTrue(labelBounds.getMaxY() <= inputs.get(index).getY());
                                    assertTrue(inputs.get(index)
                                            .getHeight() >= inputs.get(index).getPreferredSize().height);
                                    assertTrue(
                                            inputs.get(index).getX() + inputs.get(index).getWidth() <= row.getWidth());
                                }
                                int detailsHeight = fields.getComponent(4).getHeight();
                                if (width == 240)
                                    narrowHeight = detailsHeight;
                                if (width == 420)
                                    assertTrue(detailsHeight < narrowHeight,
                                            "A wider drawer must reclaim the extra wrapped lines");
                                if (width == 320)
                                    capture(drawer, "advanced-labels-" + language + "-" + scale + ".png");
                            }
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        } finally {
                            frame.dispose();
                        }
                    });
                }
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (previousScale == null)
                    System.clearProperty("flatlaf.uiScale");
                else
                    System.setProperty("flatlaf.uiScale", previousScale);
                DesktopThemeService.install(ThemeMode.LIGHT);
            });
        }
    }

    private static java.util.stream.Stream<Component> descendants(Container parent) {
        return java.util.Arrays.stream(parent.getComponents())
                .flatMap(child -> child instanceof Container nested
                        ? java.util.stream.Stream.concat(java.util.stream.Stream.of(child), descendants(nested))
                        : java.util.stream.Stream.of(child));
    }

    private static void capture(JComponent component, String name) throws Exception {
        var image = new java.awt.image.BufferedImage(component.getWidth(), component.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        var directory = java.nio.file.Path.of("target", "visual-checks");
        java.nio.file.Files.createDirectories(directory);
        javax.imageio.ImageIO.write(image, "png", directory.resolve(name).toFile());
    }

    @Test
    void expandingHelpResizesTheDialogAndKeepsTheFormUsable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
            var form = new JScrollPane(new JTextField("preserved input"));
            form.setPreferredSize(new Dimension(520, 250));
            var pane = new AdvancedOptionsPane(form, new DesktopComponentFactory(ThemePalette.light()), messages);
            pane.addOption(new JTextField(24));
            JDialog dialog = new JDialog();
            try {
                dialog.setContentPane(pane);
                dialog.pack();
                int closedWidth = dialog.getWidth();
                pane.setExpanded(true);
                dialog.validate();
                assertTrue(dialog.getWidth() > closedWidth);
                assertTrue(form.getWidth() >= 400);
                pane.setExpanded(false);
                assertEquals(closedWidth, dialog.getWidth());
                assertEquals("preserved input", ((JTextField) form.getViewport().getView()).getText());
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void nestedInputsShowCustomizationAndBusyRestoresOriginalEnabledStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
            var pane = new AdvancedOptionsPane(new JPanel(), new DesktopComponentFactory(ThemePalette.light()),
                    messages);
            JTextArea input = new JTextArea();
            pane.field("field.configurationEntries", new JScrollPane(input));
            ToggleSwitch required = new ToggleSwitch();
            JPanel flags = new JPanel();
            flags.add(required);
            pane.field("component.required", flags);
            JTextField disabled = new JTextField();
            disabled.setEnabled(false);
            pane.field("field.databaseDetails", disabled);
            assertFalse(customized(pane, messages));
            input.setText("PORT=8080");
            assertTrue(customized(pane, messages));
            input.setText("");
            assertFalse(customized(pane, messages));
            required.setSelected(true);
            assertTrue(customized(pane, messages));
            pane.setBusy(true);
            assertFalse(input.isEnabled());
            assertFalse(required.isEnabled());
            pane.setBusy(false);
            assertTrue(input.isEnabled());
            assertTrue(required.isEnabled());
            assertFalse(disabled.isEnabled());
        });
    }

    private static boolean customized(Container parent, PageMessagePresenter messages) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && label.getText().equals(messages.text("advanced.modified")))
                return true;
            if (child instanceof Container nested && customized(nested, messages))
                return true;
        }
        return false;
    }
}

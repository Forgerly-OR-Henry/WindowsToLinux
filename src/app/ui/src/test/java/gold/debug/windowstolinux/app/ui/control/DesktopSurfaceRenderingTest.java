package gold.debug.windowstolinux.app.ui.control;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.display.DesktopSwitchIcon;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import org.junit.jupiter.api.Test;

/** Checks rendered surfaces and existing control state without opening or resizing a window. / 离屏检查实际表面绘制和已有控件状态，不打开或调整窗口。 */
class DesktopSurfaceRenderingTest {
    private static final Color OUTSIDE = new Color(218, 40, 174);

    private static final Color CHILD = new Color(35, 174, 155);

    @Test
    void opaqueContentCannotCoverTheRoundedCardCorners() throws Exception {
        appearances((theme, scale, factory) -> {
            var card = factory.card(new BorderLayout());
            card.setBorder(BorderFactory.createEmptyBorder());
            var content = new JPanel();
            content.setBackground(CHILD);
            content.setOpaque(true);
            card.add(content);

            var image = paint(card, 160, 80, OUTSIDE);
            int inset = UIScale.scale(1);
            int farX = image.getWidth() - inset - 1;
            int farY = image.getHeight() - inset - 1;
            for (int x : new int[]{inset, farX})
                for (int y : new int[]{inset, farY})
                    assertEquals(OUTSIDE.getRGB(), image.getRGB(x, y),
                        "Opaque child content must be clipped at every rounded corner: " + theme + " " + scale);
            assertEquals(CHILD.getRGB(), image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
            assertEquals(CHILD.getRGB(), image.getRGB(image.getWidth() / 2, inset));
            capture("card-" + theme + "-" + scale, image);
        });
    }

    @Test
    void buttonFeedbackUsesDistinctRenderedSurfacesAndPrimaryActionsStayBlue() throws Exception {
        appearances((theme, scale, factory) -> {
            checkButton(theme, scale, "primary", factory.primaryButton(""), true);
            checkButton(theme, scale, "secondary", factory.secondaryButton(""), false);
            checkButton(theme, scale, "navigation", factory.navigationButton(""), false);
        });
    }

    @Test
    void nativeFocusAndErrorStatesDoNotAddAnOuterOrInnerFrame() throws Exception {
        appearances((theme, scale, factory) -> {
            var toolbarButton = new JButton("Toolbar");
            var toolbar = new JToolBar();
            toolbar.setFloatable(false);
            toolbar.setLayout(new BorderLayout());
            toolbar.setBorder(BorderFactory.createEmptyBorder());
            toolbar.add(toolbarButton);
            JComponent[] controls = {new JButton("Button"), factory.primaryButton("Primary"),
                factory.secondaryButton("Secondary"), factory.navigationButton("Navigation"),
                new JTextField("Draft value"), new JComboBox<>(new String[]{"Selected choice"}), toolbarButton};
            String[] names = {"button", "primary", "secondary", "navigation", "input", "dropdown", "toolbar"};
            var background = theme == ThemeMode.DARK ? ThemePalette.dark().pageBackground()
                : ThemePalette.light().pageBackground();
            for (int index = 0; index < controls.length; index++) {
                var control = controls[index];
                var surface = control == toolbarButton ? toolbar : control;
                control.putClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER,
                    (Predicate<JComponent>) component -> false);
                var normal = paint(surface, 160, 38, OUTSIDE);
                control.putClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER,
                    (Predicate<JComponent>) component -> true);
                assertTrue(FlatUIUtils.isPermanentFocusOwner(control), "The actual FlatLaf focus path must be active");
                var focused = paint(surface, 160, 38, OUTSIDE);
                assertSameEdges(normal, focused, names[index] + " must not gain a focus frame");
                capture("focused-" + names[index] + "-" + theme + "-" + scale,
                    paint(surface, 160, 38, background));
                if (control instanceof JTextField) {
                    control.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
                    assertSameEdges(focused, paint(surface, 160, 38, OUTSIDE),
                        "An error state must not add an inner outline to the input");
                }
                if (control instanceof JButton button) {
                    button.getModel().setRollover(true);
                    var hover = paint(surface, 160, 38, OUTSIDE);
                    button.getModel().setArmed(true);
                    button.getModel().setPressed(true);
                    var pressed = paint(surface, 160, 38, OUTSIDE);
                    int x = normal.getWidth() - UIScale.scale(12), y = normal.getHeight() / 2;
                    assertNotEquals(focused.getRGB(x, y), hover.getRGB(x, y), "Hover feedback must remain visible");
                    assertNotEquals(hover.getRGB(x, y), pressed.getRGB(x, y), "Pressed feedback must remain visible");
                }
            }
        });
    }

    @Test
    void focusedSwitchHasNoHaloAndRetainsHoverAndSelectedFeedback() throws Exception {
        appearances((theme, scale, factory) -> {
            class FocusedSwitch extends JCheckBox {
                boolean focused;

                @Override
                public boolean isFocusOwner() {
                    return focused;
                }
            }
            var control = new FocusedSwitch();
            control.setIcon(new DesktopSwitchIcon());
            var background = theme == ThemeMode.DARK ? ThemePalette.dark().pageBackground()
                : ThemePalette.light().pageBackground();
            var normal = paint(control, 160, 38, background);
            control.focused = true;
            var focused = paint(control, 160, 38, background);
            assertArrayEquals(normal.getRGB(0, 0, normal.getWidth(), normal.getHeight(), null, 0, normal.getWidth()),
                focused.getRGB(0, 0, focused.getWidth(), focused.getHeight(), null, 0, focused.getWidth()),
                "A focused switch must not paint a halo around the track");
            control.getModel().setRollover(true);
            var hover = paint(control, 160, 38, background);
            control.getModel().setRollover(false);
            control.setSelected(true);
            control.putClientProperty(DesktopSwitchIcon.class, null);
            control.setIcon(new DesktopSwitchIcon());
            var selected = paint(control, 160, 38, background);
            int x = UIScale.scale(29), y = normal.getHeight() / 2;
            assertNotEquals(focused.getRGB(x, y), hover.getRGB(x, y), "Switch hover feedback must remain visible");
            assertNotEquals(focused.getRGB(x, y), selected.getRGB(x, y), "The selected switch must remain visible");
            capture("focused-switch-" + theme + "-" + scale,
                stateStrip(List.of(normal, focused, hover, selected), List.of("Normal", "Focused", "Hover", "Selected")));
        });
    }

    private static void assertSameEdges(BufferedImage expected, BufferedImage actual, String message) {
        int band = UIScale.scale(3);
        for (int y = 0; y < expected.getHeight(); y++)
            for (int x = 0; x < expected.getWidth(); x++)
                if (x < band || y < band || x >= expected.getWidth() - band || y >= expected.getHeight() - band)
                    assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), message + " at " + x + "," + y);
    }

    @Test
    void appearanceRefreshKeepsDraftSelectionModelsAndActionListeners() throws Exception {
        appearances((theme, scale, factory) -> {
            var root = factory.pagePanel();
            var card = factory.card(new FlowLayout());
            var input = new JTextField("Unsubmitted connection draft", 24);
            input.select(4, 14);
            var choices = new JComboBox<>(new String[]{"first", "retained choice"});
            choices.setSelectedIndex(1);
            var option = new JCheckBox("Retained disabled option", true);
            option.setEnabled(false);
            var action = factory.primaryButton("");
            var invoked = new AtomicInteger();
            action.addActionListener(event -> invoked.incrementAndGet());
            card.add(input);
            card.add(choices);
            card.add(option);
            card.add(action);
            root.add(card, BorderLayout.CENTER);
            var document = input.getDocument();
            var choiceModel = choices.getModel();
            var actionModel = action.getModel();

            ThemeMode next = theme == ThemeMode.LIGHT ? ThemeMode.DARK : ThemeMode.LIGHT;
            var nextPalette = next == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
            DesktopThemeService.install(next, scale);
            SwingUtilities.updateComponentTreeUI(root);
            factory.palette(nextPalette);
            factory.refreshTheme(root);

            assertSame(card, root.getComponent(0));
            assertSame(input, card.getComponent(0));
            assertSame(document, input.getDocument());
            assertEquals("Unsubmitted connection draft", input.getText());
            assertEquals("bmitted co", input.getSelectedText());
            assertSame(choiceModel, choices.getModel());
            assertEquals("retained choice", choices.getSelectedItem());
            assertTrue(option.isSelected());
            assertFalse(option.isEnabled());
            assertSame(actionModel, action.getModel());
            action.doClick(0);
            assertEquals(1, invoked.get(), "Theme refresh must preserve the original action listener");
            var image = paint(action, 160, 38, nextPalette.pageBackground());
            assertEquals(nextPalette.accent().getRGB(), center(image),
                "The retained primary action must actually paint its refreshed accent");
        });
    }

    private static void checkButton(ThemeMode theme, int scale, String name, JButton button, boolean primary)
        throws Exception {
        var images = new java.util.ArrayList<BufferedImage>();
        for (String state : List.of("normal", "hover", "pressed", "disabled")) {
            button.getModel().setArmed(false);
            button.getModel().setPressed(false);
            button.getModel().setRollover(false);
            button.setEnabled(true);
            if (state.equals("hover") || state.equals("pressed")) button.getModel().setRollover(true);
            if (state.equals("pressed")) {
                button.getModel().setArmed(true);
                button.getModel().setPressed(true);
            }
            if (state.equals("disabled")) button.setEnabled(false);
            var image = paint(button, 160, 38, OUTSIDE);
            assertEquals(OUTSIDE.getRGB(), image.getRGB(0, 0),
                "Action surfaces must leave their outer corners rounded: " + name + " " + state);
            if (primary && !state.equals("disabled")) {
                var color = new Color(center(image));
                assertTrue(color.getBlue() > color.getRed() && color.getBlue() > color.getGreen(),
                    "Primary feedback must retain its blue accent: " + state + " " + color);
            }
            images.add(image);
        }
        for (int first = 0; first < images.size(); first++)
            for (int second = first + 1; second < images.size(); second++)
                assertNotEquals(center(images.get(first)), center(images.get(second)),
                    name + " must paint distinguishable normal, hover, pressed and disabled feedback in " + theme);
        capture(name + "-" + theme + "-" + scale, stateStrip(images));
    }

    private static int center(BufferedImage image) {
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
    }

    private static BufferedImage paint(JComponent component, int width, int height, Color outside) {
        var host = new JPanel(new BorderLayout());
        host.setBackground(outside);
        host.add(component);
        host.setSize(UIScale.scale(width), UIScale.scale(height));
        layout(host);
        var image = new BufferedImage(host.getWidth(), host.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            host.printAll(graphics);
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

    private static BufferedImage stateStrip(List<BufferedImage> images) {
        return stateStrip(images, List.of("Normal", "Hover", "Pressed", "Disabled"));
    }

    private static BufferedImage stateStrip(List<BufferedImage> images, List<String> labels) {
        int width = images.get(0).getWidth(), height = images.get(0).getHeight();
        var strip = new BufferedImage(width * images.size(), height + 24, BufferedImage.TYPE_INT_RGB);
        var graphics = strip.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, strip.getWidth(), strip.getHeight());
            for (int index = 0; index < images.size(); index++) {
                graphics.drawImage(images.get(index), index * width, 24, null);
                graphics.setColor(Color.DARK_GRAY);
                graphics.drawString(labels.get(index), index * width + 8, 17);
            }
        } finally {
            graphics.dispose();
        }
        return strip;
    }

    private static void capture(String name, BufferedImage image) throws Exception {
        String destination = System.getProperty("desktop.surface.capture");
        if (destination == null || destination.isBlank()) return;
        Path directory = Path.of(destination);
        Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }

    private static void appearances(AppearanceCheck check) throws Exception {
        onEdt(() -> {
            float previousZoom = UIScale.getZoomFactor();
            ThemeMode previousTheme = UIManager.getLookAndFeel() instanceof FlatDarkLaf
                ? ThemeMode.DARK : ThemeMode.LIGHT;
            try {
                for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                    for (int scale : new int[]{100, 150}) {
                        DesktopThemeService.install(theme, scale);
                        var palette = theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
                        check.verify(theme, scale, new DesktopPartFactory(palette));
                    }
            } finally {
                DesktopThemeService.install(previousTheme, Math.round(previousZoom * 100));
            }
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    @FunctionalInterface
    private interface AppearanceCheck {
        void verify(ThemeMode theme, int scale, DesktopPartFactory factory) throws Exception;
    }
}

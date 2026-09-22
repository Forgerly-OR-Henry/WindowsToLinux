package gold.debug.windowstolinux.app.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.accessibility.AccessibleState;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import org.junit.jupiter.api.Test;

class ToggleSwitchTest {
    @Test
    void focusAndHoverNeverPaintOutsideTheTrackAndKeyboardFocusStaysInsideTheThumb() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String previousScale = System.getProperty("flatlaf.uiScale");
            KeyboardFocusManager previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            try {
                System.setProperty("flatlaf.uiScale", "1.0");
                for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK)) {
                    DesktopThemeService.install(theme);
                    var control = new ToggleSwitch();
                    KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
                        @Override
                        public Component getFocusOwner() {
                            return control;
                        }
                    });
                    for (boolean selected : List.of(false, true))
                        for (boolean hover : List.of(false, true)) {
                            control.setSelected(selected);
                            control.getModel().setRollover(hover);
                            for (FocusEvent.Cause cause : List.of(FocusEvent.Cause.MOUSE_EVENT,
                                    FocusEvent.Cause.TRAVERSAL_FORWARD)) {
                                FocusEvent focus = new FocusEvent(control, FocusEvent.FOCUS_GAINED, false, null, cause);
                                for (var listener : control.getFocusListeners())
                                    listener.focusGained(focus);
                                Icon icon = control.getIcon();
                                BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                                        BufferedImage.TYPE_INT_ARGB);
                                Graphics2D graphics = image.createGraphics();
                                try {
                                    icon.paintIcon(control, graphics, 0, 0);
                                } finally {
                                    graphics.dispose();
                                }
                                for (int y = 0; y < image.getHeight(); y++)
                                    for (int x = 0; x < image.getWidth(); x++) {
                                        if (x < 2 || x >= 42 || y < 3 || y >= 23)
                                            assertEquals(0, image.getRGB(x, y) >>> 24,
                                                    "No outer border in focus or hover states");
                                    }
                                Color center = cause == FocusEvent.Cause.MOUSE_EVENT
                                        ? Color.WHITE
                                        : UIManager.getColor("Component.focusColor");
                                assertEquals(center.getRGB(), image.getRGB(selected ? 32 : 12, 13));
                            }
                        }
                }
            } finally {
                KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocus);
                if (previousScale == null)
                    System.clearProperty("flatlaf.uiScale");
                else
                    System.setProperty("flatlaf.uiScale", previousScale);
                DesktopThemeService.install(ThemeMode.LIGHT);
            }
        });
    }

    @Test
    void mouseAndSpaceKeepNativeStateEventsAccessibilityAndDisabledBehavior() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var control = new ToggleSwitch("Enabled", false);
            AtomicInteger actions = new AtomicInteger();
            control.addActionListener(event -> actions.incrementAndGet());
            control.doClick(0);
            assertTrue(control.isSelected());
            assertEquals(1, actions.get());
            assertTrue(control.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
            for (String key : List.of("pressed SPACE", "released SPACE")) {
                Object action = control.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
                assertNotNull(action);
                control.getActionMap().get(action).actionPerformed(new ActionEvent(control, 0, key));
            }
            assertFalse(control.isSelected());
            assertEquals(2, actions.get());
            control.setEnabled(false);
            control.doClick(0);
            assertFalse(control.isSelected());
            assertEquals(2, actions.get());
            control.setSelected(true);
            assertEquals(2, actions.get(), "Restoring state must not submit an action");
        });
    }

    @Test
    void wrappedFieldLabelsKeepTrailingSwitchesAndHelpInsideNarrowInspectors() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String previous = System.getProperty("flatlaf.uiScale");
        try {
            for (String scale : List.of("1.0", "1.25", "1.5"))
                for (String locale : List.of("zh-CN", "en-US")) {
                    for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                        SwingUtilities.invokeAndWait(() -> {
                            System.setProperty("flatlaf.uiScale", scale);
                            DesktopThemeService.install(theme);
                            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag(locale));
                            var pane = new AdvancedOptionsPane(new JPanel(),
                                    new DesktopComponentFactory(
                                            theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light()),
                                    messages);
                            var keys = List.of("auto.detectType", "experimentalAdapterRisk", "component.required");
                            var switches = new java.util.ArrayList<ToggleSwitch>();
                            for (String key : keys) {
                                var control = new ToggleSwitch();
                                pane.field(key, control);
                                switches.add(control);
                            }
                            switches.getFirst().setSelected(true);
                            switches.getLast().setEnabled(false);
                            JFrame frame = new JFrame();
                            JScrollPane drawer = (JScrollPane) pane.drawer();
                            frame.setContentPane(drawer);
                            drawer.setVisible(true);
                            frame.setSize(320, 400);
                            frame.setVisible(true);
                            try {
                                for (int width : new int[]{320, 240, 420}) {
                                    frame.setSize(width, 400);
                                    frame.validate();
                                    RepaintManager.currentManager(frame).validateInvalidComponents();
                                    frame.validate();
                                    for (int i = 0; i < switches.size(); i++) {
                                        ToggleSwitch control = switches.get(i);
                                        Container row = (Container) ((Container) drawer.getViewport().getView())
                                                .getComponent(i);
                                        JTextArea label = descendants(row).filter(JTextArea.class::isInstance)
                                                .map(JTextArea.class::cast).findFirst().orElseThrow();
                                        JButton help = descendants(row).filter(JButton.class::isInstance)
                                                .map(JButton.class::cast).findFirst().orElseThrow();
                                        Rectangle labelBounds = SwingUtilities.convertRectangle(label.getParent(),
                                                label.getBounds(), row);
                                        Rectangle switchBounds = SwingUtilities.convertRectangle(control.getParent(),
                                                control.getBounds(), row);
                                        Rectangle helpBounds = SwingUtilities.convertRectangle(help.getParent(),
                                                help.getBounds(), row);
                                        assertEquals(messages.text(keys.get(i)), label.getText());
                                        assertNull(control.getText());
                                        assertEquals(label.getText(),
                                                control.getAccessibleContext().getAccessibleName());
                                        assertTrue(labelBounds.getMaxX() <= switchBounds.x);
                                        assertEquals(switchBounds.getCenterY(), labelBounds.getCenterY(), 1,
                                                "Wrapped text and its switch must share the same vertical center");
                                        assertTrue(switchBounds.getMaxX() <= helpBounds.x
                                                && helpBounds.getMaxX() <= row.getWidth());
                                        assertTrue(control.getWidth() >= control.getIcon().getIconWidth());
                                        assertTrue(label.modelToView2D(label.getDocument().getLength() - 1)
                                                .getMaxY() <= label.getHeight());
                                    }
                                    if (width == 320)
                                        render(drawer, locale + "-" + theme + "-" + scale);
                                }
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            } finally {
                                frame.dispose();
                            }
                        });
                }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (previous == null)
                    System.clearProperty("flatlaf.uiScale");
                else
                    System.setProperty("flatlaf.uiScale", previous);
                DesktopThemeService.install(ThemeMode.LIGHT);
            });
        }
    }

    private static Stream<Component> descendants(Container parent) {
        return Stream.of(parent.getComponents())
                .flatMap(child -> child instanceof Container nested
                        ? Stream.concat(Stream.of(child), descendants(nested))
                        : Stream.of(child));
    }

    private static void render(JComponent component, String name) throws Exception {
        Path directory = Path.of("target", "visual-checks", "switches");
        Files.createDirectories(directory);
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        javax.imageio.ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }
}

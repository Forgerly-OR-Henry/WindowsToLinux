package gold.debug.windowstolinux.app.ui.shell;

import com.formdev.flatlaf.FlatLightLaf;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class BeginnerDesktopLayoutTest {
    @Test void allPagesKeepTheirDrawerAndInputsAcrossLanguageAndThemeRebuild() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            DesktopFrame frame = new DesktopFrame(null, MessageCatalog.forLanguageTag("zh-CN"), DesktopDisplayConfiguration.defaults(),
                    ThemePalette.light(), (source, selected) -> { }, null);
            DesktopFrame restored = null;
            try {
                var show = DesktopFrame.class.getDeclaredMethod("showPage",String.class,String.class,String.class); show.setAccessible(true);
                for (String page : List.of("deployment","components","applications","backup","servers","ai","settings")) {
                    show.invoke(frame,page,"nav."+page,"page."+page+".description");
                    var root = frame.getContentPane(); root.setSize(1060,680); layout(root);
                    var pane = descendants(root).filter(AdvancedOptionsPane.class::isInstance).map(AdvancedOptionsPane.class::cast)
                            .filter(Component::isVisible).filter(value -> value.getParent().isVisible()).findFirst().orElseThrow();
                    assertFalse(pane.expanded()); render(root,page+"-closed.png");
                    pane.setExpanded(true); layout(root); render(root,page+"-expanded.png");
                    if (page.equals("components")) {
                        JButton lifecycle = descendants(pane).filter(JButton.class::isInstance).map(JButton.class::cast)
                                .filter(button -> button.getText().equals(MessageCatalog.forLanguageTag("zh-CN").text("component.button.lifecycle")))
                                .findFirst().orElseThrow();
                        Rectangle action = SwingUtilities.convertRectangle(lifecycle.getParent(), lifecycle.getBounds(), pane);
                        assertTrue(action.y + action.height <= pane.getHeight(), "Lifecycle action must not be clipped");
                        assertTrue(lifecycle.getY() + lifecycle.getHeight() <= lifecycle.getParent().getHeight());
                    }
                    if (page.equals("settings")) {
                        descendants(pane).filter(JButton.class::isInstance).map(JButton.class::cast)
                                .filter(button -> button.getText().equals(MessageCatalog.forLanguageTag("zh-CN").text("settings.diagnostics.open")))
                                .forEach(button -> assertTrue(button.getHeight() <= 40, "Diagnostic action must retain normal height"));
                    }
                    descendants(pane).filter(JButton.class::isInstance).map(JButton.class::cast)
                            .filter(button -> "help".equals(button.getClientProperty("JButton.buttonType")))
                            .forEach(button -> assertFalse(button.getAccessibleContext().getAccessibleName().startsWith("help."),button.getAccessibleContext().getAccessibleName()));
                }
                try (var state = frame.captureViewState()) {
                    assertTrue(state.expanded().values().stream().allMatch(Boolean::booleanValue));
                    restored = new DesktopFrame(null,MessageCatalog.forLanguageTag("en-US"),
                            new DesktopDisplayConfiguration("en-US",ThemeMode.DARK),ThemePalette.dark(),(source,selected) -> { },state);
                    try (var after = restored.captureViewState()) {
                        assertEquals(state.expanded(),after.expanded()); assertEquals(state.deploymentSelection(),after.deploymentSelection());
                        assertEquals(state.deployment().runtimePrimary(),after.deployment().runtimePrimary());
                    }
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { if (restored != null) restored.dispose(); frame.dispose(); }
        });
    }
    @Test void keepsPrimaryActionLogAndHelpVisibleAtSupportedSizes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            var catalog = MessageCatalog.forLanguageTag("zh-CN");
            DesktopFrame frame = new DesktopFrame(null, catalog, DesktopDisplayConfiguration.defaults(),
                    ThemePalette.light(), (source, selected) -> { }, null);
            try {
                for (int[] size : List.of(new int[]{1180, 780}, new int[]{1060, 720})) {
                    frame.setLocation(0, 0); frame.setSize(size[0], size[1]); frame.setVisible(true);
                    var root = frame.getContentPane(); layout(root);
                    AdvancedOptionsPane deployment = descendants(root).filter(AdvancedOptionsPane.class::isInstance)
                            .map(AdvancedOptionsPane.class::cast).findFirst().orElseThrow();
                    assertFalse(deployment.expanded());
                    JButton deploy = descendants(deployment).filter(JButton.class::isInstance).map(JButton.class::cast)
                            .filter(button -> button.getText().equals(catalog.text("auto.start"))).findFirst().orElseThrow();
                    JTextArea log = descendants(deployment).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                            .filter(area -> !area.isEditable()).findFirst().orElseThrow();
                    render(root, "deployment-" + size[0] + ".png");
                    assertTrue(log.getHeight() >= 200, "Log must remain readable: " + log.getSize() + " parent=" + log.getParent().getSize());
                    assertTrue(deploy.getWidth() >= 120 && deploy.getHeight() >= 30);
                    render(root, "deployment-" + size[0] + ".png");
                    Dimension previousLogSize = log.getSize();
                    deployment.setExpanded(true); frame.validate(); layout(root);
                    assertEquals(previousLogSize, log.getSize(), "Opening an inspector must preserve the workspace size");
                    assertTrue(log.getWidth() >= 360, "Inspector must not overlap the log");
                    assertTrue(log.getHeight() >= 200);
                    long help = Stream.concat(descendants(root), java.util.Arrays.stream(frame.getOwnedWindows()).flatMap(BeginnerDesktopLayoutTest::descendants)).filter(JButton.class::isInstance).map(JButton.class::cast)
                            .filter(button -> "help".equals(button.getClientProperty("JButton.buttonType")))
                            .peek(button -> {
                                assertNotNull(button.getToolTipText());
                                assertFalse(button.getAccessibleContext().getAccessibleName().startsWith("help."));
                                assertTrue(button.isFocusable());
                            }).count();
                    assertTrue(help >= 20);
                    render(root, "deployment-advanced-" + size[0] + ".png");
                    deployment.setExpanded(false);
                }
            } finally { frame.dispose(); }
        });
    }

    private static Stream<Component> descendants(Container parent) {
        return Stream.of(parent.getComponents()).flatMap(child -> child instanceof Container container
                ? Stream.concat(Stream.of(child), descendants(container)) : Stream.of(child));
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void render(Container component, String name) {
        try {
            Path directory = Path.of("target", "visual-checks"); Files.createDirectories(directory);
            BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { component.printAll(graphics); } finally { graphics.dispose(); }
            javax.imageio.ImageIO.write(image, "png", directory.resolve(name).toFile());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}

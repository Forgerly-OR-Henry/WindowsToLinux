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
    @Test void navigationAnimatesWithoutVerticalJumpsAndCanReverseBeforeCompletion() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        org.junit.jupiter.api.Assumptions.assumeFalse(Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation")));
        org.junit.jupiter.api.Assumptions.assumeFalse("false".equals(System.getProperty("flatlaf.animation")));
        var current = new java.util.concurrent.atomic.AtomicReference<DesktopFrame>();
        var sampler = new java.util.concurrent.atomic.AtomicReference<Timer>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new java.util.concurrent.CountDownLatch(1);
        var widths = new java.util.ArrayList<Integer>();
        var preferences = new java.util.ArrayList<Boolean>();
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var catalog = MessageCatalog.forLanguageTag("zh-CN");
            DesktopFrame frame = new DesktopFrame(null, catalog, DesktopDisplayConfiguration.defaults(),
                    ThemePalette.light(), (source, selected) -> { }, null);
            current.set(frame);
            frame.setLocation(0, 0); frame.setVisible(true);
            frame.onNavigationChange(preferences::add);
            Container root = frame.getContentPane();
            Container sidebar = (Container) ((BorderLayout) root.getLayout()).getLayoutComponent(BorderLayout.WEST);
            JButton toggle = descendants(sidebar).filter(JButton.class::isInstance).map(JButton.class::cast)
                    .filter(button -> catalog.text("nav.collapse").equals(button.getToolTipText())).findFirst().orElseThrow();
            JButton deploy = descendants(sidebar).filter(JButton.class::isInstance).map(JButton.class::cast)
                    .filter(button -> catalog.text("nav.deployment").equals(button.getToolTipText())).findFirst().orElseThrow();
            int initialY = deploy.getY();
            toggle.doClick(0);
            assertEquals(168, sidebar.getWidth(), "A click must not jump directly to the final width");
            int[] phase = {0};
            Timer timer = new Timer(15, event -> {
                try {
                    int width = sidebar.getWidth(); widths.add(width);
                    assertEquals(initialY, deploy.getY(), "Navigation rows must not jump as labels disappear");
                    assertTrue(width >= 64 && width <= 168);
                    if (phase[0] == 0 && width > 80 && width < 155) {
                        render(root, "navigation-mid-collapse.png");
                        phase[0] = 1; toggle.doClick(0);
                        assertEquals(width, sidebar.getWidth(), "Reversing must continue from the current width");
                    } else if (phase[0] == 1 && width == 168) {
                        phase[0] = 2; toggle.doClick(0);
                    } else if (phase[0] == 2 && width == 64) {
                        render(root, "navigation-collapsed.png");
                        assertTrue(frame.navigationCollapsed());
                        assertEquals(List.of(true, false, true), preferences);
                        assertTrue(widths.stream().distinct().count() >= 4, "The animation must render intermediate widths");
                        ((Timer) event.getSource()).stop(); done.countDown();
                    }
                } catch (Throwable problem) {
                    failure.set(problem); ((Timer) event.getSource()).stop(); done.countDown();
                }
            });
            sampler.set(timer); timer.start();
        });
        try {
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "Navigation animation did not complete");
            if (failure.get() != null) throw new AssertionError(failure.get());
            SwingUtilities.invokeAndWait(() -> {
                DesktopFrame frame = current.get();
                Container sidebar = (Container) ((BorderLayout) frame.getContentPane().getLayout()).getLayoutComponent(BorderLayout.WEST);
                JButton toggle = descendants(sidebar).filter(JButton.class::isInstance).map(JButton.class::cast).findFirst().orElseThrow();
                toggle.doClick(0); frame.dispose();
                try {
                    var field = DesktopFrame.class.getDeclaredField("navigationAnimation"); field.setAccessible(true);
                    assertFalse(((Timer) field.get(frame)).isRunning(), "Disposing the window must stop its animation");
                } catch (ReflectiveOperationException problem) { throw new AssertionError(problem); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (sampler.get() != null) sampler.get().stop(); if (current.get() != null) current.get().dispose(); });
        }
    }

    @Test void allPagesKeepTheirDrawerAndInputsAcrossLanguageAndThemeRebuild() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
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
                    Container workspace = (Container) ((BorderLayout) root.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                    Container header = (Container) ((BorderLayout) workspace.getLayout()).getLayoutComponent(BorderLayout.NORTH);
                    JButton toggle = descendants(header).filter(JButton.class::isInstance).map(JButton.class::cast)
                            .filter(button -> button.getText().equals(MessageCatalog.forLanguageTag("zh-CN").text("advanced.show")))
                            .findFirst().orElseThrow();
                    Rectangle toggleBounds = SwingUtilities.convertRectangle(toggle.getParent(), toggle.getBounds(), header);
                    Insets padding = header.getInsets();
                    assertEquals((header.getHeight() + padding.top - padding.bottom) / 2.0, toggleBounds.getCenterY(), 1,
                            "Advanced options must be centered inside the header border");
                    assertFalse(SwingUtilities.isDescendingFrom(toggle, pane));
                    assertFalse(pane.expanded()); render(root,page+"-closed.png");
                    toggle.doClick(0); assertTrue(pane.expanded()); layout(root); render(root,page+"-expanded.png");
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
            DesktopThemeService.install(ThemeMode.LIGHT);
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
                            .filter(area -> "deployment.output".equals(area.getName())).findFirst().orElseThrow();
                    render(root, "deployment-" + size[0] + ".png");
                    assertTrue(log.getHeight() >= 200, "Log must remain readable: " + log.getSize() + " parent=" + log.getParent().getSize());
                    assertTrue(deploy.getWidth() >= 120 && deploy.getHeight() >= 30);
                    JLabel direction = descendants(deployment).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                            .filter(label -> "deployment.direction".equals(label.getName())).findFirst().orElseThrow();
                    Container selection = direction.getParent();
                    Component source = selection.getComponent(0), target = selection.getComponent(2);
                    assertEquals(source.getWidth(), target.getWidth(), 1, "Deployment cards must keep equal widths");
                    descendants(selection).filter(JButton.class::isInstance).map(JButton.class::cast)
                            .filter(button -> button.getIcon() != null)
                            .forEach(button -> assertTrue(button.getHeight() >= 84, "Selection areas must not be compressed: " + button.getSize()));
                    assertTrue(direction.getX() > source.getX() + source.getWidth());
                    assertTrue(direction.getX() + direction.getWidth() < target.getX());
                    assertEquals(source.getBounds().getCenterY(), direction.getBounds().getCenterY(), 1);
                    assertTrue(direction.getWidth() >= direction.getIcon().getIconWidth());
                    render(root, "deployment-" + size[0] + ".png");
                    var sourceCard = descendants((Container) source).filter(gold.debug.windowstolinux.app.ui.deployment.automatic.DeploymentSourceCard.class::isInstance)
                            .map(gold.debug.windowstolinux.app.ui.deployment.automatic.DeploymentSourceCard.class::cast).findFirst().orElseThrow();
                    sourceCard.restore("https://github.com/MHSanaei/3x-ui.git", true); layout(root);
                    render(root, "deployment-selected-" + size[0] + ".png");
                    sourceCard.restore("", false); layout(root);
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

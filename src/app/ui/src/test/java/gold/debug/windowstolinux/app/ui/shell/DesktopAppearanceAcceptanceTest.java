package gold.debug.windowstolinux.app.ui.shell;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
class DesktopAppearanceAcceptanceTest {
    @Test void maximizedThemeRebuildKeepsNormalRestoreSizeAndInspectorState() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        org.junit.jupiter.api.Assumptions.assumeTrue(Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH));
        var current = new java.util.concurrent.atomic.AtomicReference<DesktopFrame>();
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT); var frame = new DesktopFrame(null); current.set(frame);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); frame.setLocation(0,0); frame.setVisible(true);
            descendants(frame).filter(AdvancedOptionsPane.class::isInstance).map(AdvancedOptionsPane.class::cast).filter(Component::isShowing).findFirst().orElseThrow().setExpanded(true);
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        });
        try {
            Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> {
                var old = current.get(); Rectangle bounds = old.workspaceWindowBounds(); assertEquals(1180,bounds.width);
                var state = old.captureViewState(); old.dispose(); DesktopThemeService.apply(ThemeMode.DARK);
                var next = new DesktopFrame(null,MessageCatalog.forLanguageTag("en"),new DesktopDisplayConfiguration("en",ThemeMode.DARK),ThemePalette.dark(),(source,selection) -> {},state);
                next.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); current.set(next); next.restoreWorkspaceWindowBounds(bounds); next.setExtendedState(Frame.MAXIMIZED_BOTH); next.setVisible(true);
                assertTrue(java.util.Arrays.stream(old.getOwnedWindows()).noneMatch(Window::isShowing));
            });
            Thread.sleep(500); SwingUtilities.invokeAndWait(() -> current.get().setExtendedState(Frame.NORMAL)); Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> { assertEquals(1180,current.get().workspaceWindowBounds().width); assertTrue(descendants(current.get()).filter(AdvancedOptionsPane.class::isInstance).map(AdvancedOptionsPane.class::cast).filter(Component::isShowing).findFirst().orElseThrow().expanded()); capture(current.get(),"desktop-rebuilt-after-maximum.png"); });
        } finally { SwingUtilities.invokeAndWait(() -> { current.get().dispose(); DesktopThemeService.install(ThemeMode.LIGHT); }); }
    }
    @Test void visibleWindowThemesLanguagesAndScaledControlsRetainWorkspaceGeometry() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        String previous = System.getProperty("flatlaf.uiScale");
        try {
            for (String scale : List.of("1.0","1.25","1.5")) for (ThemeMode theme : List.of(ThemeMode.LIGHT,ThemeMode.DARK)) {
                SwingUtilities.invokeAndWait(() -> {
                    System.setProperty("flatlaf.uiScale",scale); DesktopThemeService.install(theme);
                    assertEquals(Float.parseFloat(scale),com.formdev.flatlaf.util.UIScale.getUserScaleFactor(),0.01);
                    String language = theme == ThemeMode.LIGHT ? "zh-CN" : "en"; var catalog = MessageCatalog.forLanguageTag(language);
                    var palette = theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark();
                    DesktopFrame frame = new DesktopFrame(null,catalog,new DesktopDisplayConfiguration(language,theme),palette,(source,selection) -> {},null);
                    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); frame.setLocation(0,0); frame.setVisible(true);
                    try {
                        frame.validate(); JPanel deck = (JPanel)frame.getContentPane();
                        assertEquals(palette.pageBackground(),deck.getBackground());
                        var pane = descendants(frame).filter(AdvancedOptionsPane.class::isInstance).map(AdvancedOptionsPane.class::cast).filter(Component::isShowing).findFirst().orElseThrow();
                        Dimension before = pane.getSize(); pane.setExpanded(true); frame.validate(); assertEquals(before,pane.getSize());
                        for (JComboBox<?> combo : descendants(frame).filter(JComboBox.class::isInstance).map(value -> (JComboBox<?>)value).filter(Component::isShowing).toList()) {
                            JViewport viewport = (JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,combo);
                            if (viewport != null) { Rectangle bounds = SwingUtilities.convertRectangle(combo.getParent(),combo.getBounds(),viewport); assertTrue(bounds.x + bounds.width <= viewport.getWidth(), "inspector combo must fit its viewport"); }
                        }
                        capture(frame,"desktop-"+theme.name().toLowerCase()+"-"+scale+"-advanced.png"); pane.setExpanded(false);
                        frame.setNavigationCollapsed(true); frame.validate(); assertTrue(frame.navigationCollapsed());
                        JButton deploy = descendants(pane).filter(JButton.class::isInstance).map(JButton.class::cast).filter(button -> button.getText().equals(catalog.text("auto.start"))).findFirst().orElseThrow();
                        assertTrue(deploy.isShowing()); assertTrue(deploy.getWidth() >= deploy.getFontMetrics(deploy.getFont()).stringWidth(deploy.getText()) + deploy.getInsets().left + deploy.getInsets().right, "deployment button must fit its scaled label");
                        capture(frame,"desktop-"+theme.name().toLowerCase()+"-"+scale+"-collapsed.png");
                    } finally { frame.dispose(); }
                });
            }
        } finally { SwingUtilities.invokeAndWait(() -> { if(previous==null)System.clearProperty("flatlaf.uiScale");else System.setProperty("flatlaf.uiScale",previous); DesktopThemeService.install(ThemeMode.LIGHT); }); }
    }
    private static void capture(JFrame frame,String file) {
        try { RepaintManager.currentManager(frame).validateInvalidComponents(); frame.validate(); var image = new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB); var g=image.createGraphics();frame.paint(g);g.dispose();Files.createDirectories(Path.of("target/visual-checks"));javax.imageio.ImageIO.write(image,"png",Path.of("target/visual-checks",file).toFile()); }
        catch(Exception e){throw new AssertionError(e);}
    }
    private static Stream<Component> descendants(Container root){return Stream.of(root.getComponents()).flatMap(child -> child instanceof Container nested ? Stream.concat(Stream.of(child),descendants(nested)):Stream.of(child));}
}

package gold.debug.windowstolinux.app.ui.display;

import gold.debug.windowstolinux.app.ui.shell.DesktopFrame;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DesktopThemeServiceTest {
    @Test
    void installsFlatLightLookAndFeelAndSharedGeometry() {
        DesktopThemeService.install(ThemeMode.LIGHT);

        assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel());
        assertEquals(12, UIManager.get("Component.arc"));
        assertEquals(12, UIManager.get("Button.arc"));
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
        AtomicReference<DesktopFrame> frame = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            DesktopFrame desktopFrame = new DesktopFrame(null);
            frame.set(desktopFrame);
            var root = desktopFrame.getContentPane();
            root.setSize(1060, 680); root.doLayout();
            var layout = (java.awt.BorderLayout) root.getLayout();
            var sidebar = layout.getLayoutComponent(java.awt.BorderLayout.WEST);
            var workspace = layout.getLayoutComponent(java.awt.BorderLayout.CENTER);
            assertEquals(0, sidebar.getY());
            assertEquals(root.getHeight(), sidebar.getHeight());
            assertEquals(sidebar.getX() + sidebar.getWidth(), workspace.getX());
        });

        SwingUtilities.invokeAndWait(() -> frame.get().dispose());
    }
}

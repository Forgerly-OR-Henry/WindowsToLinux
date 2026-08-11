package gold.debug.windowstolinux.app.ui.appearance;

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

class DesktopThemeTest {
    @Test
    void installsFlatLightLookAndFeelAndSharedGeometry() {
        DesktopTheme.install(ThemeMode.LIGHT);

        assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel());
        assertEquals(12, UIManager.get("Component.arc"));
        assertEquals(12, UIManager.get("Button.arc"));
    }

    @Test
    void installsFlatDarkLookAndFeelForTheDarkAppearance() {
        DesktopTheme.install(ThemeMode.DARK);

        assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel());
        assertEquals(ThemeMode.LIGHT, SystemThemePreference.effectiveTheme(ThemeMode.LIGHT));
        assertEquals(ThemeMode.DARK, SystemThemePreference.effectiveTheme(ThemeMode.DARK));
    }

    @Test
    void createsTheFiveEntryDesktopShellWhenGraphicsAreAvailable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<DesktopFrame> frame = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            DesktopFrame desktopFrame = new DesktopFrame(null);
            frame.set(desktopFrame);
            assertEquals(3, desktopFrame.getContentPane().getComponentCount());
        });

        SwingUtilities.invokeAndWait(() -> frame.get().dispose());
    }
}

package gold.debug.windowstolinux.mod.files.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.display.*;

class FilesBrowserMotionTest {
    @Test
    void secondPaneUsesCurrentPoseAndHiddenPaneCannotReceiveInput() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var scope = new DesktopMotionScope(() -> true);
        var left = edt(JPanel::new);
        var right = edt(JPanel::new);
        var region = edt(() -> new FilesBrowserRegion(() -> Optional.of(scope)));
        var frame = edt(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            region.left(left);
            var window = new JFrame("File browser motion");
            window.setContentPane(region);
            window.setSize(900, 600);
            window.setVisible(true);
            region.expanded(true, right);
            return window;
        });
        try {
            Thread.sleep(70);
            edt(() -> {
                region.doLayout();
                int width = left.getWidth();
                if (DesktopMotion.enabled()) assertTrue(width > 400 && width < region.getWidth());
                region.expanded(false, right);
                region.doLayout();
                assertEquals(width, left.getWidth());
                assertFalse(right.isVisible());
                DesktopMotion.preference(false);
                region.doLayout();
                assertEquals(region.getWidth(), left.getWidth());
                region.expanded(true, right);
                region.doLayout();
                assertEquals((region.getWidth() - 6) / 2, left.getWidth());
                assertTrue(right.isVisible());
                region.reset();
                return null;
            });
        } finally {
            edt(() -> {
                region.close();
                scope.close();
                frame.dispose();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                return null;
            });
        }
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}

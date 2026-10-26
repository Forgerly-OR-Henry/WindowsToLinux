package gold.debug.windowstolinux.app.ui.window;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.concurrent.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

class PlatformSidebarPolicyTest {
    @Test
    void disablingGlobalMotionFinishesAnAlreadyMovingSidebarAndAllLaterChangesAreInstant() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var pages = edt(JTabbedPane::new);
        var shell = edt(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            var value = new PlatformNavigationShell(pages, MessageCatalog.forLanguageTag("zh-CN"), ThemePalette.light());
            pages.addTab("Server", new JPanel());
            return value;
        });
        var frame = edt(() -> {
            var window = new JFrame("Sidebar policy");
            window.setContentPane(shell);
            window.setSize(1000, 700);
            window.setVisible(true);
            shell.collapse(true, true);
            return window;
        });
        try {
            Thread.sleep(60);
            edt(() -> {
                var sidebar = find(shell, "platform.sidebar");
                DesktopMotion.preference(false);
                frame.validate();
                assertEquals(64, sidebar.getWidth());
                shell.collapse(false, true);
                frame.validate();
                assertEquals(PlatformNavigationShell.SIDEBAR_WIDTH, sidebar.getWidth());
                shell.collapse(true, true);
                frame.validate();
                assertEquals(64, sidebar.getWidth());
                return null;
            });
        } finally {
            edt(() -> {
                shell.close();
                frame.dispose();
                assertFalse(frame.isDisplayable());
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                return null;
            });
        }
    }

    private static Component find(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container parent) for (var child : parent.getComponents()) {
            var found = find(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}

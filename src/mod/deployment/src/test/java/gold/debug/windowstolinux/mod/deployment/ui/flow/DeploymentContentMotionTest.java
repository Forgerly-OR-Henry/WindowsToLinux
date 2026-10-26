package gold.debug.windowstolinux.mod.deployment.ui.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.appapi.ui.page.UiMotion;

class DeploymentContentMotionTest {
    @Test
    void repeatedRefreshDoesNotReplayEntranceAndNewActionExecutesDuringFade() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var scope = new DesktopMotionScope(() -> true);
        var animations = new AtomicInteger();
        var actions = new AtomicInteger();
        UiMotion clock = (owner, kind, frame) -> {
            animations.incrementAndGet();
            return scope.animate(owner, kind, frame);
        };
        var cards = edt(() -> new DeploymentContentCards(() -> Optional.of(clock)));
        var first = edt(() -> new JButton("First action"));
        var second = edt(() -> new JButton("Second action"));
        var frame = edt(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            cards.add(first, "first");
            cards.add(second, "second");
            cards.showCard("first");
            var window = new JFrame("Deployment content motion");
            window.setContentPane(cards);
            window.setSize(700, 420);
            window.setVisible(true);
            return window;
        });
        try {
            edt(() -> {
                cards.showCard("second");
                assertEquals(1, animations.get());
                for (int poll = 0; poll < 100; poll++) cards.showCard("second");
                assertEquals(1, animations.get(), "Polling must not start more animations");
                assertTrue(second.isVisible());
                assertFalse(first.isVisible());
                second.addActionListener(event -> actions.incrementAndGet());
                second.doClick(0);
                assertEquals(1, actions.get());
                cards.showCard("first");
                assertEquals(2, animations.get());
                DesktopMotion.preference(false);
                cards.showCard("second");
                assertTrue(second.isVisible());
                cards.reset();
                return null;
            });
        } finally {
            edt(() -> {
                cards.close();
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

package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.appapi.ui.page.*;

class DesktopMotionTest {
    @Test
    void easingIsMonotonicAndDurationsAreIndependentOfScale() {
        double previous = -1;
        for (int i = 0; i <= 100; i++) {
            double value = DesktopMotion.ease(i / 100d);
            assertTrue(value >= previous && value <= 1);
            previous = value;
        }
        assertEquals(0, DesktopMotion.ease(0));
        assertEquals(1, DesktopMotion.ease(1));
        assertEquals(.5, DesktopMotion.ease(.5));
        assertEquals(240, UiMotionKind.DETAIL.milliseconds());
        assertEquals(120, UiMotionKind.SWITCH.milliseconds());
    }

    @Test
    void disabledMotionFinishesImmediatelyAndRevokedScopesReleaseCallbacks() throws Exception {
        boolean previous = DesktopMotion.preference();
        SwingUtilities.invokeAndWait(() -> {
            var active = new AtomicBoolean(true);
            var scope = new DesktopMotionScope(active::get);
            var samples = new ArrayList<Double>();
            var owner = new JPanel();
            try {
                DesktopMotion.preference(false);
                var handle = scope.animate(owner, UiMotionKind.DETAIL, samples::add);
                assertEquals(List.of(1d), samples);
                handle.finish();
                assertEquals(1, samples.size());
                scope.close();
                scope.animate(owner, UiMotionKind.CONTENT, value -> fail("Closed scope callback"));
                active.set(false);
                handle.finish();
                assertEquals(1, samples.size());
            } finally {
                scope.close();
                DesktopMotion.preference(previous);
            }
        });
    }

    @Test
    void turningOffFinishesAlreadyRunningClocksSynchronously() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var scope = new AtomicReference<DesktopMotionScope>();
        var values = new ArrayList<Double>();
        SwingUtilities.invokeAndWait(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopMotion.preference(true);
            var owner = new JPanel() {
                @Override
                public boolean isShowing() {
                    return true;
                }
            };
            scope.set(new DesktopMotionScope(() -> true));
            scope.get().animate(owner, UiMotionKind.DETAIL, values::add);
            DesktopMotion.preference(false);
            assertEquals(1d, values.getLast());
            int count = values.size();
            scope.get().cancel();
            assertEquals(count, values.size());
        });
        SwingUtilities.invokeAndWait(() -> {
            scope.get().close();
            if (flag == null) System.clearProperty("flatlaf.animation");
            else System.setProperty("flatlaf.animation", flag);
            DesktopMotion.preference(previous);
        });
    }

    @Test
    void aFailingAuthorFrameCannotLeaveOtherTransitionsRunning() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var report = new AtomicReference<Throwable>();
        var handler = new AtomicReference<Thread.UncaughtExceptionHandler>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                handler.set(Thread.currentThread().getUncaughtExceptionHandler());
                Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> report.set(failure));
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                var scope = new DesktopMotionScope(() -> true);
                var owner = new JPanel() {
                    @Override
                    public boolean isShowing() {
                        return true;
                    }
                };
                var values = new ArrayList<Double>();
                scope.animate(owner, UiMotionKind.DETAIL, value -> {
                    if (value == 1) throw new IllegalStateException("fixture frame failure");
                });
                scope.animate(owner, UiMotionKind.DETAIL, values::add);
                DesktopMotion.preference(false);
                assertEquals(1d, values.getLast());
                assertEquals(0, DesktopMotion.runningCount());
                scope.close();
            });
            SwingUtilities.invokeAndWait(() -> assertInstanceOf(IllegalStateException.class, report.get()));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                Thread.currentThread().setUncaughtExceptionHandler(handler.get());
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
            });
        }
    }
}

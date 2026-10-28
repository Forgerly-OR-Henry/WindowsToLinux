package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.appapi.ui.page.*;

class DesktopMotionTest {
    @Test
    void theSharedClockRunsOnlyWhileAtLeastOneMotionIsActive() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        SwingUtilities.invokeAndWait(() -> {
            var first = new DesktopMotion(ignored -> {
            });
            var second = new DesktopMotion(ignored -> {
            });
            try {
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                first.start();
                second.start();
                assertEquals(2, DesktopMotion.runningCount());
                assertTrue(DesktopMotion.clockRunning());
                first.close();
                assertEquals(1, DesktopMotion.runningCount());
                assertTrue(DesktopMotion.clockRunning());
                second.close();
                assertEquals(0, DesktopMotion.runningCount());
                assertFalse(DesktopMotion.clockRunning());
            } finally {
                first.close();
                second.close();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
            }
        });
    }

    @Test
    void frameFailuresAreReportedWithoutStarvingRemainingClocks() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var clocks = new ArrayList<DesktopMotion>();
        var report = new AtomicReference<Throwable>();
        var handler = new AtomicReference<Thread.UncaughtExceptionHandler>();
        var healthyFrame = new CountDownLatch(1);
        var reported = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                handler.set(Thread.currentThread().getUncaughtExceptionHandler());
                Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> {
                    report.set(failure);
                    reported.countDown();
                });
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                clocks.add(new DesktopMotion(ignored -> {
                    throw new IllegalStateException("first frame failure");
                }));
                clocks.add(new DesktopMotion(ignored -> {
                    throw new IllegalArgumentException("second frame failure");
                }));
                clocks.add(new DesktopMotion(elapsed -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    assertTrue(elapsed > 0);
                    clocks.getLast().close();
                    healthyFrame.countDown();
                }));
                clocks.forEach(DesktopMotion::start);
            });
            assertTrue(healthyFrame.await(2, TimeUnit.SECONDS));
            assertTrue(reported.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertInstanceOf(IllegalStateException.class, report.get());
                assertEquals(1, report.get().getSuppressed().length);
                assertInstanceOf(IllegalArgumentException.class, report.get().getSuppressed()[0]);
                assertEquals(0, DesktopMotion.runningCount());
                assertFalse(DesktopMotion.clockRunning());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                clocks.forEach(DesktopMotion::close);
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                Thread.currentThread().setUncaughtExceptionHandler(handler.get());
            });
        }
    }

    @Test
    void cancellingASiblingDuringAFrameSkipsItsPendingCallback() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var clocks = new ArrayList<DesktopMotion>();
        var cancelledFrames = new AtomicInteger();
        var finished = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                clocks.add(new DesktopMotion(ignored -> {
                    clocks.forEach(DesktopMotion::close);
                    finished.countDown();
                }));
                clocks.add(new DesktopMotion(ignored -> cancelledFrames.incrementAndGet()));
                clocks.forEach(DesktopMotion::start);
            });
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0, cancelledFrames.get());
                assertEquals(0, DesktopMotion.runningCount());
                assertFalse(DesktopMotion.clockRunning());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                clocks.forEach(DesktopMotion::close);
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
            });
        }
    }

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

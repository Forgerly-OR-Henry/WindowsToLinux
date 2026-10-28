package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.app.service.setting.StartupPageLocation;
import gold.debug.windowstolinux.appapi.ui.page.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in real Swing timings with isolated storage; paint passes are not display refreshes. */
@EnabledIfSystemProperty(named = "wtl.motion.profile", matches = "before|after")
class PlatformMotionPerformanceProfileTest {
    @TempDir
    Path directory;

    @Test
    void comparesTimerWakeups() throws Exception {
        var values = new Measurements();
        for (int interval : new int[]{16, 10, 8}) {
            var done = new CountDownLatch(1);
            var sampleTimer = onEdt(() -> {
                values.phase = "timer_" + interval;
                var last = new long[]{System.nanoTime()};
                var count = new int[1];
                var timer = new javax.swing.Timer(interval, event -> {
                    long now = System.nanoTime();
                    if (count[0]++ > 10) values.record("callback", now - last[0]);
                    last[0] = now;
                    if (count[0] >= 100) {
                        ((javax.swing.Timer) event.getSource()).stop();
                        done.countDown();
                    }
                });
                timer.start();
                return timer;
            });
            try {
                assertTrue(done.await(8, TimeUnit.SECONDS));
            } finally {
                onEdt(() -> {
                    sampleTimer.stop();
                    return null;
                });
            }
        }
        Files.writeString(Path.of("target", "motion-timer-comparison.csv"), values.csv());
    }

    @Test
    void recordsIdleNavigationSidebarDrawerAndPopupTimings() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "The profile requires a real desktop");
        var measurements = new Measurements();
        var pending = new AtomicBoolean();
        var probe = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("motion-profile-lag").factory());
        RepaintManager previous = onEdt(() -> RepaintManager.currentManager((Component) null));
        var paints = new RepaintManager() {
            @Override
            public void paintDirtyRegions() {
                long start = System.nanoTime();
                super.paintDirtyRegions();
                measurements.record("paint_pass", System.nanoTime() - start);
            }
        };
        var queue = new ProfileQueue(measurements);
        String animation = System.getProperty("flatlaf.animation");
        boolean preference = DesktopMotion.preference();
        try (var runtime = PlatformRuntime.open(directory, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock("isolated-profile".toCharArray(), "isolated-profile".toCharArray());
            var models = runtime.models();
            PlatformFrame frame = onEdt(() -> {
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                var result = ApplicationLauncher.createWindow(runtime, MessageCatalog.forLanguageTag("zh-CN"),
                    new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT), ThemeMode.LIGHT, FailureReportStore.disabled(), models);
                result.setSize(1280, 800);
                result.setLocation(30, 30);
                result.setVisible(true);
                return result;
            });
            runtime.started();
            DesktopSplitPane drawer = onEdt(() -> {
                var inventory = new JPanel(new GridLayout(12, 1, 0, 8));
                for (int i = 0; i < 12; i++) inventory.add(new JButton("示例条目 / Sample " + i));
                var details = new JPanel(new BorderLayout());
                details.add(new JLabel("详情 / Details"));
                return new DesktopSplitPane(inventory, details, .44, 16, new Dimension(1100, 650));
            });
            try (var page = frame.pages().begin("example.motion.profile", false)) {
                page.register(new PageSpec("sample", "sample", "动画采样 / Motion profile", false), () -> new ModPage() {
                    public JComponent create(UiEnvironment environment) {
                        return drawer;
                    }

                    public void environmentChanged(UiEnvironment environment) {
                    }

                    public void close() {
                        drawer.close();
                    }
                });
                page.commit();
                await(() -> !DesktopTaskRunner.hasActiveTasks());
                onEdt(() -> {
                    RepaintManager.setCurrentManager(paints);
                    Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
                    return null;
                });
                probe.scheduleAtFixedRate(() -> {
                    if (pending.compareAndSet(false, true)) {
                        long sent = System.nanoTime();
                        SwingUtilities.invokeLater(() -> {
                            measurements.record("queue_delay", System.nanoTime() - sent);
                            pending.set(false);
                        });
                    }
                }, 0, 20, TimeUnit.MILLISECONDS);
                sample(measurements, "idle", () -> Thread.sleep(1200));
                sample(measurements, "navigation", () -> {
                    for (int i = 0; i < 5; i++) {
                        onEdt(() -> {
                            frame.resumePage(StartupPageLocation.platform("platform.servers"));
                            return null;
                        });
                        Thread.sleep(300);
                        page.open("sample");
                        Thread.sleep(300);
                    }
                });
                sample(measurements, "sidebar", () -> {
                    for (int i = 0; i < 8; i++) {
                        onEdt(() -> {
                            ((JButton) named(frame, "navigation.collapse")).doClick(0);
                            return null;
                        });
                        Thread.sleep(320);
                    }
                });
                sample(measurements, "drawer", () -> {
                    for (int i = 0; i < 5; i++) {
                        onEdt(() -> {
                            drawer.expanded(true);
                            return null;
                        });
                        Thread.sleep(110);
                        onEdt(() -> {
                            drawer.expanded(false);
                            return null;
                        });
                        Thread.sleep(300);
                    }
                });
                sample(measurements, "popup", () -> {
                    for (int i = 0; i < 5; i++) {
                        var menu = onEdt(() -> {
                            var popup = new JPopupMenu();
                            popup.add(new JMenuItem("示例操作 / Sample action"));
                            popup.show(drawer, 40, 40);
                            return popup;
                        });
                        Thread.sleep(220);
                        onEdt(() -> {
                            menu.setVisible(false);
                            return null;
                        });
                        Thread.sleep(80);
                    }
                });
                var output = Path.of("target", "motion-profile-" + System.getProperty("wtl.motion.profile") + ".csv");
                Files.createDirectories(output.getParent());
                Files.writeString(output, measurements.csv());
                assertTrue(measurements.count() > 100);
                System.out.println("Motion profile: " + output.toAbsolutePath());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
                await(() -> !DesktopTaskRunner.hasActiveTasks());
            }
        } finally {
            probe.shutdownNow();
            assertTrue(probe.awaitTermination(5, TimeUnit.SECONDS));
            onEdt(() -> {
                measurements.phase = null;
                RepaintManager.setCurrentManager(previous);
                queue.remove();
                if (animation == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", animation);
                DesktopMotion.preference(preference);
                return null;
            });
        }
    }

    private static void sample(Measurements values, String phase, Action action) throws Exception {
        var clock = onEdt(() -> {
            values.phase = phase;
            var motion = new DesktopMotion(seconds -> values.record("animation_callback", (long) (seconds * 1_000_000_000L)));
            if (!phase.equals("idle")) motion.start();
            return motion;
        });
        try {
            action.run();
        } finally {
            onEdt(() -> {
                clock.close();
                values.phase = null;
                return null;
            });
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static final class ProfileQueue extends EventQueue {
        private final Measurements measurements;

        private boolean installed;

        private ProfileQueue(Measurements measurements) {
            this.measurements = measurements;
        }

        @Override
        protected void dispatchEvent(AWTEvent event) {
            installed = true;
            long start = System.nanoTime();
            try {
                super.dispatchEvent(event);
            } finally {
                measurements.record("event_dispatch", System.nanoTime() - start);
            }
        }

        private void remove() {
            if (installed) pop();
        }
    }

    private static final class Measurements {
        private final Map<String, List<Long>> values = new LinkedHashMap<>();

        private volatile String phase;

        private synchronized void record(String metric, long duration) {
            if (phase != null) values.computeIfAbsent(phase + "," + metric, ignored -> new ArrayList<>()).add(duration);
        }

        private synchronized int count() {
            return values.values().stream().mapToInt(List::size).sum();
        }

        private synchronized String csv() {
            var text = new StringBuilder("phase,metric,count,mean_ms,p50_ms,p95_ms,max_ms,over_33ms\n");
            values.forEach((key, samples) -> {
                var sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
                text.append(String.format(Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f,%.3f,%d%n", key, sorted.length,
                    Arrays.stream(sorted).average().orElseThrow() / 1e6, sorted[sorted.length / 2] / 1e6,
                    sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * .95) - 1)] / 1e6,
                    sorted[sorted.length - 1] / 1e6, Arrays.stream(sorted).filter(value -> value > 33_000_000).count()));
            });
            return text.toString();
        }
    }
}

package gold.debug.windowstolinux.app.ui.display;

import java.awt.Toolkit;
import java.util.function.DoubleConsumer;
import java.util.*;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Shared elapsed-time clock and policy for UI motion. / UI 动效共用的实际时间时钟和策略。 */
public final class DesktopMotion implements AutoCloseable {
    private static volatile boolean preference = true;

    private static boolean suspended;

    private static final Set<DesktopMotion> RUNNING = new LinkedHashSet<>();

    private static final Map<Runnable, Boolean> OBSERVERS = Collections.synchronizedMap(new WeakHashMap<>());

    // Local Windows/JDK 21 sampling delivered 16 ms requests around 31 ms; 10 ms avoided that quantization.
    private static final Timer TIMER = new Timer(10, ignored -> pulse());

    static {
        TIMER.setCoalesce(true);
        Toolkit.getDefaultToolkit().addPropertyChangeListener("win.clientAreaAnimation",
            event -> SwingUtilities.invokeLater(DesktopMotion::refreshPolicy));
    }

    /** Applies the persisted global choice on the EDT, including already running motion. */
    public static void preference(boolean enabled) {
        requireEdt();
        preference = enabled;
        refreshPolicy();
    }

    public static boolean preference() {
        return preference;
    }

    /** Privacy lock suspends presentation without changing the persisted preference. */
    public static void suspended(boolean value) {
        requireEdt();
        suspended = value;
        refreshPolicy();
    }

    /** Weakly observes policy; the caller retains the listener for its own lifetime. */
    public static void observe(Runnable listener) {
        OBSERVERS.put(Objects.requireNonNull(listener), Boolean.TRUE);
    }

    public static void unobserve(Runnable listener) {
        OBSERVERS.remove(listener);
    }

    private static void refreshPolicy() {
        requireEdt();
        Throwable failure = null;
        if (!enabled()) for (var clock : List.copyOf(RUNNING)) {
            clock.close();
            try {
                clock.frame.accept(Double.POSITIVE_INFINITY);
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        List<Runnable> observers;
        synchronized (OBSERVERS) {
            observers = List.copyOf(OBSERVERS.keySet());
        }
        for (var listener : observers)
            try {
                listener.run();
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        for (var window : java.awt.Window.getWindows()) if (window.isDisplayable()) window.repaint();
        report(failure);
    }

    private static void report(Throwable failure) {
        if (failure != null) {
            Throwable reported = failure;
            SwingUtilities.invokeLater(() -> {
                Thread thread = Thread.currentThread();
                thread.getUncaughtExceptionHandler().uncaughtException(thread, reported);
            });
        }
    }

    public static double ease(double fraction) {
        double value = Math.max(0, Math.min(1, fraction));
        return value * value * (3 - 2 * value);
    }

    public static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Motion requires EDT");
    }

    private static void pulse() {
        long now = System.nanoTime();
        Throwable failure = null;
        for (var clock : List.copyOf(RUNNING)) {
            if (!RUNNING.contains(clock) || clock.last > now) continue;
            double elapsed = (now - clock.last) / 1_000_000_000d;
            clock.last = now;
            if (!enabled()) {
                clock.close();
                elapsed = Double.POSITIVE_INFINITY;
            }
            try {
                clock.frame.accept(elapsed);
            } catch (RuntimeException | Error problem) {
                clock.close();
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        report(failure);
    }

    /** Previous monotonic frame timestamp. / 上一帧单调时钟时间戳。 */
    private long last;

    /** EDT callback receiving real elapsed seconds. / 接收实际经过秒数的 EDT 回调。 */
    private final DoubleConsumer frame;

    /** Binds the EDT frame update. / 绑定 EDT 帧更新。
     * @param frame receives elapsed seconds / 接收经过的秒数 */
    public DesktopMotion(DoubleConsumer frame) {
        this.frame = frame;
    }

    /** Starts or continues the monotonic clock. / 启动或继续单调时钟。 */
    public void start() {
        requireEdt();
        if (!enabled()) {
            frame.accept(Double.POSITIVE_INFINITY);
        } else if (RUNNING.add(this)) {
            last = System.nanoTime();
            if (!TIMER.isRunning()) TIMER.start();
        }
    }

    /** Respects the application and system motion preferences. / 遵守应用及系统动画偏好。
     * @return whether motion is enabled / 是否启用动画 */
    public static boolean enabled() {
        return preference && !suspended && !"false".equals(System.getProperty("flatlaf.animation"))
            && !Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation"));
    }

    /** Stops the owned animation. / 停止所属动画。 */
    @Override
    public void close() {
        RUNNING.remove(this);
        if (RUNNING.isEmpty()) TIMER.stop();
    }

    static int runningCount() {
        return RUNNING.size();
    }

    static boolean clockRunning() {
        return TIMER.isRunning();
    }
}

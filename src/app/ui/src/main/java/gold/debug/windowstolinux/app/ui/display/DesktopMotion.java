package gold.debug.windowstolinux.app.ui.display;

import java.awt.Toolkit;
import java.util.function.DoubleConsumer;
import javax.swing.Timer;

/** Shared elapsed-time spring clock for UI motion. / UI 运动共用的实际时间弹簧时钟。 */
public final class DesktopMotion implements AutoCloseable {
    /** Owned frame timer and elapsed-time callback. / 所属帧计时器和实际时间回调。 */
    private final Timer timer;

    /** Previous monotonic frame timestamp. / 上一帧单调时钟时间戳。 */
    private long last;

    /** EDT callback receiving real elapsed seconds. / 接收实际经过秒数的 EDT 回调。 */
    private final DoubleConsumer frame;

    /** Binds the EDT frame update. / 绑定 EDT 帧更新。
     * @param frame receives elapsed seconds / 接收经过的秒数 */
    public DesktopMotion(DoubleConsumer frame) {
        this.frame = frame;
        timer = new Timer(16, ignored -> {
            long now = System.nanoTime();
            double elapsed = (now - last) / 1_000_000_000d;
            last = now;
            frame.accept(elapsed);
        });
        timer.setCoalesce(true);
    }

    /** Starts or continues motion without resetting its velocity. / 启动或继续运动，不重置速度。 */
    public void start() {
        if (!enabled()) {
            frame.accept(Double.POSITIVE_INFINITY);
        } else if (!timer.isRunning()) {
            last = System.nanoTime();
            timer.start();
        }
    }

    /** Respects the application and system motion preferences. / 遵守应用及系统动画偏好。
     * @return whether motion is enabled / 是否启用动画 */
    public static boolean enabled() {
        return !"false".equals(System.getProperty("flatlaf.animation"))
            && !Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation"));
    }

    /** Advances a critically damped spring using real elapsed time. / 根据实际经过时间推进临界阻尼弹簧。
     * @param value current position / 当前位置
     * @param velocity current velocity / 当前速度
     * @param target desired position / 目标位置
     * @param seconds elapsed seconds / 经过的秒数
     * @return next position and velocity / 下一位置和速度 */
    public static Sample step(double value, double velocity, double target, double seconds) {
        if (!Double.isFinite(seconds)) return new Sample(target, 0);
        double omega = 28, distance = value - target, offset = velocity + omega * distance;
        double decay = Math.exp(-omega * seconds);
        return new Sample(target + (distance + offset * seconds) * decay,
            (velocity - omega * offset * seconds) * decay);
    }

    /** Stops the owned animation. / 停止所属动画。 */
    @Override
    public void close() {
        timer.stop();
    }

    /** One continuous motion sample. / 一次连续运动采样。
     * @param value position / 位置
     * @param velocity velocity / 速度 */
    public record Sample(double value, double velocity) {
    }
}

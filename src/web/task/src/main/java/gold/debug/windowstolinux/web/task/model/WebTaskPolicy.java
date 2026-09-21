package gold.debug.windowstolinux.web.task.model;

import java.time.Duration;

/**
 * Bounds task concurrency, durable events, decisions and shutdown waits.
 * <p>限制任务并发、持久化事件、决定及关闭等待。
 *
 * @param workers workers / 工作线程集合
 * @param maxActive max active / 最大活跃
 * @param maxEvents max events / 最大事件集合
 * @param decisionTimeout decision timeout / 决定超时
 * @param shutdownTimeout shutdown timeout / 关闭超时
 * @param forcedShutdownTimeout forced shutdown timeout / 强制关闭超时
 */
public record WebTaskPolicy(int workers, int maxActive, int maxEvents, Duration decisionTimeout, Duration shutdownTimeout, Duration forcedShutdownTimeout) {
    /**
     * Validates and binds the inputs required by web task policy.
     * <p>校验并绑定Web任务策略所需输入。
     *
     * @param workers workers / 工作线程集合
     * @param maxActive max active / 最大活跃
     * @param maxEvents max events / 最大事件集合
     * @param decisionTimeout decision timeout / 决定超时
     * @param shutdownTimeout shutdown timeout / 关闭超时
     * @param forcedShutdownTimeout forced shutdown timeout / 强制关闭超时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public WebTaskPolicy {
        if (workers < 1 || workers > 64 || maxActive < workers || maxEvents < 1 || decisionTimeout == null
                || decisionTimeout.isNegative() || decisionTimeout.isZero() || shutdownTimeout == null
                || shutdownTimeout.isNegative() || shutdownTimeout.isZero() || forcedShutdownTimeout == null
                || forcedShutdownTimeout.isNegative() || forcedShutdownTimeout.isZero()) throw new IllegalArgumentException("Invalid w2l.tasks limits");
    }
}

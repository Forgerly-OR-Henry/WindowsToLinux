package gold.debug.windowstolinux.shared.deploy.result.lifecycle;

import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of a one-resource lifecycle operation.
 *
 * <p>单资源生命周期操作结果。
 *
 * @param accepted the {@code accepted} value / {@code accepted} 值
 * @param message the {@code message} value / {@code message} 值
 * @param observation the {@code observation} value / {@code observation} 值
 */
public record LifecycleActionResult(boolean accepted, LocalizedMessage message, Optional<LifecycleObservation> observation) {
    /**
     * Creates a {@code LifecycleActionResult} instance.
     *
     * <p>创建 {@code LifecycleActionResult} 实例。
     *
     * @param accepted the {@code accepted} value / {@code accepted} 值
     * @param message the {@code message} value / {@code message} 值
     * @param observation the {@code observation} value / {@code observation} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public LifecycleActionResult {
        message = Objects.requireNonNull(message, "message");
        observation = Objects.requireNonNull(observation, "observation");
    }
}

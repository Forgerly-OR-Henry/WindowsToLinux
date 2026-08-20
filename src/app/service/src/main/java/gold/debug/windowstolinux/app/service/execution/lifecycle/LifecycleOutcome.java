package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

import java.util.Objects;
import java.util.Optional;

/**
 * Secret-free result of one desktop lifecycle use case.
 *
 * <p>单次桌面生命周期用例的无秘密结果。
 *
 * @param accepted the {@code accepted} value / {@code accepted} 值
 * @param message the {@code message} value / {@code message} 值
 * @param observation the {@code observation} value / {@code observation} 值
 */
public record LifecycleOutcome(boolean accepted, LocalizedMessage message, Optional<LifecycleObservation> observation) {
    /**
     * Creates a {@code LifecycleOutcome} instance.
     *
     * <p>创建 {@code LifecycleOutcome} 实例。
     *
     * @param accepted the {@code accepted} value / {@code accepted} 值
     * @param message the {@code message} value / {@code message} 值
     * @param observation the {@code observation} value / {@code observation} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public LifecycleOutcome {
        message = Objects.requireNonNull(message, "message");
        observation = Objects.requireNonNull(observation, "observation");
    }
}

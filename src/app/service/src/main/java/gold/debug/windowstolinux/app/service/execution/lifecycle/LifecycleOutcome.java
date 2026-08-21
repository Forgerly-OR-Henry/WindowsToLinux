package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
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
public record LifecycleOutcome(
        boolean accepted,
        LocalizedMessage message,
        Optional<LifecycleObservation> observation,
        OperationIdentity operationIdentity,
        Optional<FailureDescriptor> failure,
        List<FailureDescriptor> nonFatalFailures
) {
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
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        OperationIdentity normalizedIdentity = operationIdentity;
        failure = Objects.requireNonNull(failure, "failure")
                .map(value -> value.withOperationIdentity(normalizedIdentity));
        nonFatalFailures = List.copyOf(Objects.requireNonNull(nonFatalFailures, "nonFatalFailures").stream()
                .map(value -> value.withOperationIdentity(normalizedIdentity)).toList());
        if (accepted && failure.isPresent()) {
            throw new IllegalArgumentException("an accepted lifecycle outcome cannot carry a terminal failure");
        }
        if (nonFatalFailures.stream().anyMatch(value -> value.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
    }

    /** Creates a result without a structured terminal failure. / 创建不含结构化终止失败的结果。 */
    public LifecycleOutcome(boolean accepted, LocalizedMessage message, Optional<LifecycleObservation> observation) {
        this(accepted, message, observation, OperationIdentity.create(), Optional.empty(), List.of());
    }
}

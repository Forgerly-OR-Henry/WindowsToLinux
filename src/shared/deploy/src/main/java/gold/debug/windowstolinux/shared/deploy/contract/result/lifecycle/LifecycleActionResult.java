package gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle;

import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
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
public record LifecycleActionResult(
        boolean accepted,
        LocalizedMessage message,
        Optional<LifecycleObservation> observation,
        OperationIdentity operationIdentity,
        Optional<FailureDescriptor> failure,
        List<FailureDescriptor> nonFatalFailures
) {
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
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        OperationIdentity normalizedIdentity = operationIdentity;
        failure = Objects.requireNonNull(failure, "failure")
                .map(value -> value.withOperationIdentity(normalizedIdentity));
        nonFatalFailures = List.copyOf(Objects.requireNonNull(nonFatalFailures, "nonFatalFailures").stream()
                .map(value -> value.withOperationIdentity(normalizedIdentity)).toList());
        if (accepted && failure.isPresent()) {
            throw new IllegalArgumentException("an accepted lifecycle result cannot carry a terminal failure");
        }
        if (nonFatalFailures.stream().anyMatch(value -> value.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
    }

    /** Creates a lifecycle result without a structured terminal failure. / 创建不含结构化终止失败的生命周期结果。 */
    public LifecycleActionResult(boolean accepted, LocalizedMessage message,
                                 Optional<LifecycleObservation> observation) {
        this(accepted, message, observation, OperationIdentity.create(), Optional.empty(), List.of());
    }

    /** Creates a rejected lifecycle result from a module-owned failure. / 从模块持有的失败创建被拒绝的生命周期结果。 */
    public static LifecycleActionResult failed(FailureDescriptor failure) {
        Objects.requireNonNull(failure, "failure");
        return new LifecycleActionResult(false, failure.userMessage(), Optional.empty(),
                failure.operationIdentity(), Optional.of(failure), List.of());
    }

    /** Adds a non-fatal warning while preserving the authoritative remote observation. / 添加非致命警告且保留权威远端观测。 */
    public LifecycleActionResult withNonFatalFailure(FailureDescriptor warning) {
        Objects.requireNonNull(warning, "warning");
        List<FailureDescriptor> warnings = new java.util.ArrayList<>(nonFatalFailures);
        warnings.add(warning.withOperationIdentity(operationIdentity));
        return new LifecycleActionResult(accepted, message, observation, operationIdentity, failure, warnings);
    }
}

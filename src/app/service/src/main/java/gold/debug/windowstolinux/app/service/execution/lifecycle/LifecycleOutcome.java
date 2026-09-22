package gold.debug.windowstolinux.app.service.execution.lifecycle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Secret-free result of one desktop lifecycle use case.
 *
 *  <p>单次桌面生命周期用例的无秘密结果。
 *
 * @param accepted accepted / 已接受
 * @param message localized explanation / 本地化说明
 * @param observation observation / 观测
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 * @param nonFatalFailures non fatal failures / 非致命失败集合
 */
public record LifecycleOutcome(boolean accepted, LocalizedMessage message, Optional<LifecycleObservation> observation,
        OperationIdentity operationIdentity, Optional<FailureDescriptor> failure,
        List<FailureDescriptor> nonFatalFailures) {
    /**
     * Validates and binds the inputs required by lifecycle outcome.
     * <p>校验并绑定生命周期结果所需输入。
     *
     * @param accepted accepted / 已接受
     * @param message localized explanation / 本地化说明
     * @param observation observation / 观测
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param nonFatalFailures non fatal failures / 非致命失败集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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
        if (nonFatalFailures.stream()
                .anyMatch(value -> value.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
    }

    /**
     * Creates a result without a structured terminal failure. / 创建不含结构化终止失败的结果。
     *
     * @param accepted accepted / 已接受
     * @param message localized explanation / 本地化说明
     * @param observation observation / 观测
     */
    public LifecycleOutcome(boolean accepted, LocalizedMessage message, Optional<LifecycleObservation> observation) {
        this(accepted, message, observation, OperationIdentity.create(), Optional.empty(), List.of());
    }
}

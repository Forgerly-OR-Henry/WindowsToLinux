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
 *  <p>单资源生命周期操作结果。
 *
 * @param accepted accepted / 已接受
 * @param message localized explanation / 本地化说明
 * @param observation observation / 观测
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 * @param nonFatalFailures non fatal failures / 非致命失败集合
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
     * Validates and binds the inputs required by lifecycle action result.
     * <p>校验并绑定生命周期动作结果所需输入。
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

    /**
     * Creates a lifecycle result without a structured terminal failure. / 创建不含结构化终止失败的生命周期结果。
     *
     * @param accepted accepted / 已接受
     * @param message localized explanation / 本地化说明
     * @param observation observation / 观测
     */
    public LifecycleActionResult(boolean accepted, LocalizedMessage message,
                                 Optional<LifecycleObservation> observation) {
        this(accepted, message, observation, OperationIdentity.create(), Optional.empty(), List.of());
    }

    /**
     * Creates a rejected lifecycle result from a module-owned failure. / 从模块持有的失败创建被拒绝的生命周期结果。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return a rejected lifecycle result from a module-owned failure / 从模块持有的失败创建被拒绝的生命周期结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static LifecycleActionResult failed(FailureDescriptor failure) {
        Objects.requireNonNull(failure, "failure");
        return new LifecycleActionResult(false, failure.userMessage(), Optional.empty(),
                failure.operationIdentity(), Optional.of(failure), List.of());
    }

    /**
     * Adds a non-fatal warning while preserving the authoritative remote observation. / 添加非致命警告且保留权威远端观测。
     *
     * @param warning warning / 警告
     * @return constructed or resolved lifecycle action result / 构造或解析得到的生命周期动作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public LifecycleActionResult withNonFatalFailure(FailureDescriptor warning) {
        Objects.requireNonNull(warning, "warning");
        List<FailureDescriptor> warnings = new java.util.ArrayList<>(nonFatalFailures);
        warnings.add(warning.withOperationIdentity(operationIdentity));
        return new LifecycleActionResult(accepted, message, observation, operationIdentity, failure, warnings);
    }
}

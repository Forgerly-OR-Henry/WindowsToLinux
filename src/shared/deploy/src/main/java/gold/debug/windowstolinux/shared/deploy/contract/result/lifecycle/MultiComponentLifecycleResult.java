package gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationAutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Authoritative application lifecycle result with every component preserved. / 保留每个组件的权威应用生命周期结果。
 *
 * @param accepted accepted / 已接受
 * @param message localized explanation / 本地化说明
 * @param runtimeState runtime state / 运行时状态
 * @param autostartState autostart state / 自动启动状态
 * @param componentResults component results / 组件结果集合
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 * @param nonFatalFailures non fatal failures / 非致命失败集合
 */
public record MultiComponentLifecycleResult(boolean accepted, LocalizedMessage message,
        ApplicationRuntimeState runtimeState, ApplicationAutostartState autostartState,
        List<ComponentLifecycleResult> componentResults, OperationIdentity operationIdentity,
        Optional<FailureDescriptor> failure, List<FailureDescriptor> nonFatalFailures) {
    /**
     * Validates whole-application and component outcome consistency. / 验证整体应用与组件结果一致性。
     *
     * @param accepted accepted / 已接受
     * @param message localized explanation / 本地化说明
     * @param runtimeState runtime state / 运行时状态
     * @param autostartState autostart state / 自动启动状态
     * @param componentResults component results / 组件结果集合
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param nonFatalFailures non fatal failures / 非致命失败集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentLifecycleResult {
        message = Objects.requireNonNull(message, "message");
        runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
        autostartState = Objects.requireNonNull(autostartState, "autostartState");
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        OperationIdentity normalizedIdentity = operationIdentity;
        failure = Objects.requireNonNull(failure, "failure")
                .map(value -> value.withOperationIdentity(normalizedIdentity));
        nonFatalFailures = List.copyOf(Objects.requireNonNull(nonFatalFailures, "nonFatalFailures").stream()
                .map(value -> value.withOperationIdentity(normalizedIdentity)).toList());
        componentResults = Objects.requireNonNull(componentResults, "componentResults").stream()
                .sorted(Comparator.comparing(ComponentLifecycleResult::componentId)).toList();
        if (componentResults.isEmpty())
            throw new IllegalArgumentException("application lifecycle results cannot be empty");
        if (accepted && componentResults.stream().anyMatch(result -> !result.accepted())) {
            throw new IllegalArgumentException(
                    "accepted application lifecycle results require every component to be accepted");
        }
        if (accepted && failure.isPresent()) {
            throw new IllegalArgumentException("an accepted lifecycle result cannot carry a terminal failure");
        }
        if (nonFatalFailures.stream()
                .anyMatch(value -> value.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
    }

    /**
     * Creates a lifecycle result without a structured terminal failure. / 创建不含结构化终止失败的生命周期结果。
     *
     * @param accepted accepted / 已接受
     * @param message localized explanation / 本地化说明
     * @param runtimeState runtime state / 运行时状态
     * @param autostartState autostart state / 自动启动状态
     * @param componentResults component results / 组件结果集合
     */
    public MultiComponentLifecycleResult(boolean accepted, LocalizedMessage message,
            ApplicationRuntimeState runtimeState, ApplicationAutostartState autostartState,
            List<ComponentLifecycleResult> componentResults) {
        this(accepted, message, runtimeState, autostartState, componentResults, OperationIdentity.create(),
                Optional.empty(), List.of());
    }

    /**
     * Adds a non-fatal warning while preserving authoritative component observations. / 添加非致命警告且保留权威组件观测。
     *
     * @param warning warning / 警告
     * @return constructed or resolved multi component lifecycle result / 构造或解析得到的多组件生命周期结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentLifecycleResult withNonFatalFailure(FailureDescriptor warning) {
        Objects.requireNonNull(warning, "warning");
        List<FailureDescriptor> warnings = new java.util.ArrayList<>(nonFatalFailures);
        warnings.add(warning.withOperationIdentity(operationIdentity));
        return new MultiComponentLifecycleResult(accepted, message, runtimeState, autostartState, componentResults,
                operationIdentity, failure, warnings);
    }
}

package gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle;

import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationAutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authoritative application lifecycle result with every component preserved. / 保留每个组件的权威应用生命周期结果。 */
public record MultiComponentLifecycleResult(
        boolean accepted,
        LocalizedMessage message,
        ApplicationRuntimeState runtimeState,
        ApplicationAutostartState autostartState,
        List<ComponentLifecycleResult> componentResults,
        OperationIdentity operationIdentity,
        Optional<FailureDescriptor> failure,
        List<FailureDescriptor> nonFatalFailures
) {
    /** Validates whole-application and component outcome consistency. / 验证整体应用与组件结果一致性。 */
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
        if (componentResults.isEmpty()) throw new IllegalArgumentException("application lifecycle results cannot be empty");
        if (accepted && componentResults.stream().anyMatch(result -> !result.accepted())) {
            throw new IllegalArgumentException("accepted application lifecycle results require every component to be accepted");
        }
        if (accepted && failure.isPresent()) {
            throw new IllegalArgumentException("an accepted lifecycle result cannot carry a terminal failure");
        }
        if (nonFatalFailures.stream().anyMatch(value -> value.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
    }

    /** Creates a lifecycle result without a structured terminal failure. / 创建不含结构化终止失败的生命周期结果。 */
    public MultiComponentLifecycleResult(boolean accepted, LocalizedMessage message,
                                         ApplicationRuntimeState runtimeState,
                                         ApplicationAutostartState autostartState,
                                         List<ComponentLifecycleResult> componentResults) {
        this(accepted, message, runtimeState, autostartState, componentResults,
                OperationIdentity.create(), Optional.empty(), List.of());
    }

    /** Adds a non-fatal warning while preserving authoritative component observations. / 添加非致命警告且保留权威组件观测。 */
    public MultiComponentLifecycleResult withNonFatalFailure(FailureDescriptor warning) {
        Objects.requireNonNull(warning, "warning");
        List<FailureDescriptor> warnings = new java.util.ArrayList<>(nonFatalFailures);
        warnings.add(warning.withOperationIdentity(operationIdentity));
        return new MultiComponentLifecycleResult(accepted, message, runtimeState, autostartState,
                componentResults, operationIdentity, failure, warnings);
    }
}

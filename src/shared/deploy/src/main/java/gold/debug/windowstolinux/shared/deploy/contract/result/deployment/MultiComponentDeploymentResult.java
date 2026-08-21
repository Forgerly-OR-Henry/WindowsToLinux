package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-level transaction result that always preserves every component outcome.
 *
 * <p>始终保留每个组件结果的应用级事务结果。
 */
public record MultiComponentDeploymentResult(
        DeploymentStatus status,
        List<DeploymentEvent> applicationEvents,
        List<ComponentDeploymentResult> componentResults,
        Optional<String> applicationReleaseIdentity,
        OperationIdentity operationIdentity,
        List<FailureDescriptor> nonFatalFailures
) {
    /** Validates application and component terminal consistency. / 验证应用与组件终态一致性。 */
    public MultiComponentDeploymentResult {
        status = Objects.requireNonNull(status, "status");
        applicationEvents = List.copyOf(Objects.requireNonNull(applicationEvents, "applicationEvents"));
        componentResults = List.copyOf(Objects.requireNonNull(componentResults, "componentResults").stream()
                .sorted(java.util.Comparator.comparing(ComponentDeploymentResult::componentId)).toList());
        applicationReleaseIdentity = Objects.requireNonNull(applicationReleaseIdentity, "applicationReleaseIdentity");
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        OperationIdentity normalizedIdentity = operationIdentity;
        applicationEvents = applicationEvents.stream().map(event -> event.withOperationIdentity(normalizedIdentity)).toList();
        componentResults = componentResults.stream().map(component -> new ComponentDeploymentResult(
                component.componentId(), component.state(), component.events().stream()
                .map(event -> event.withOperationIdentity(normalizedIdentity)).toList(), component.observation())).toList();
        nonFatalFailures = List.copyOf(Objects.requireNonNull(nonFatalFailures, "nonFatalFailures").stream()
                .map(failure -> failure.withOperationIdentity(normalizedIdentity)).toList());
        if (nonFatalFailures.stream().anyMatch(failure -> failure.definition().severity() != FailureSeverityLevel.WARNING)) {
            throw new IllegalArgumentException("nonFatalFailures may contain warning definitions only");
        }
        if (status == DeploymentStatus.SUCCEEDED != applicationReleaseIdentity.isPresent()) {
            throw new IllegalArgumentException("only a successful application transaction may expose a release identity");
        }
        applicationReleaseIdentity.ifPresent(value -> {
            if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid application release identity");
        });
        if (status == DeploymentStatus.SUCCEEDED && componentResults.stream()
                .anyMatch(result -> result.state() != ComponentTransactionState.SUCCEEDED)) {
            throw new IllegalArgumentException("successful application transactions require every component to succeed");
        }
        if (componentResults.stream().anyMatch(result -> result.state() == ComponentTransactionState.MANUAL_RECOVERY_REQUIRED)
                && status != DeploymentStatus.MANUAL_RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("a component recovery failure must make the application manual-recovery required");
        }
    }

    /** Creates a result and derives one identity for application and component evidence. / 创建结果并为应用及组件证据派生同一标识。 */
    public MultiComponentDeploymentResult(DeploymentStatus status, List<DeploymentEvent> applicationEvents,
                                          List<ComponentDeploymentResult> componentResults,
                                          Optional<String> applicationReleaseIdentity) {
        this(status, applicationEvents, componentResults, applicationReleaseIdentity,
                identity(applicationEvents, componentResults), List.of());
    }

    /** Adds a non-fatal warning without changing the authoritative remote result. / 添加非致命警告且不改变权威远端结果。 */
    public MultiComponentDeploymentResult withNonFatalFailure(FailureDescriptor failure) {
        Objects.requireNonNull(failure, "failure");
        List<FailureDescriptor> warnings = new java.util.ArrayList<>(nonFatalFailures);
        warnings.add(failure.withOperationIdentity(operationIdentity));
        return new MultiComponentDeploymentResult(status, applicationEvents, componentResults,
                applicationReleaseIdentity, operationIdentity, warnings);
    }

    private static OperationIdentity identity(List<DeploymentEvent> applicationEvents,
                                              List<ComponentDeploymentResult> componentResults) {
        java.util.stream.Stream<DeploymentEvent> events = java.util.stream.Stream.concat(
                Objects.requireNonNull(applicationEvents, "applicationEvents").stream(),
                Objects.requireNonNull(componentResults, "componentResults").stream()
                        .flatMap(component -> component.events().stream()));
        return events.map(DeploymentEvent::failure).flatMap(Optional::stream)
                .map(FailureDescriptor::operationIdentity).findFirst().orElseGet(OperationIdentity::create);
    }
}

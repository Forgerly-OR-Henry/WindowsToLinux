package gold.debug.windowstolinux.shared.deploy.result;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;

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
        Optional<String> applicationReleaseIdentity
) {
    /** Validates application and component terminal consistency. / 验证应用与组件终态一致性。 */
    public MultiComponentDeploymentResult {
        status = Objects.requireNonNull(status, "status");
        applicationEvents = List.copyOf(Objects.requireNonNull(applicationEvents, "applicationEvents"));
        componentResults = List.copyOf(Objects.requireNonNull(componentResults, "componentResults").stream()
                .sorted(java.util.Comparator.comparing(ComponentDeploymentResult::componentId)).toList());
        applicationReleaseIdentity = Objects.requireNonNull(applicationReleaseIdentity, "applicationReleaseIdentity");
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
}

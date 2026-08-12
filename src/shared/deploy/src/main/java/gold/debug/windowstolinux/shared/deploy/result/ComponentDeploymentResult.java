package gold.debug.windowstolinux.shared.deploy.result;

import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Component-scoped deployment evidence and terminal state. / 组件范围的部署证据与终态。 */
public record ComponentDeploymentResult(
        String componentId,
        ComponentTransactionState state,
        List<DeploymentEvent> events,
        Optional<LifecycleObservation> observation
) {
    /** Validates immutable component evidence. / 验证不可变组件证据。 */
    public ComponentDeploymentResult {
        componentId = Objects.requireNonNull(componentId, "componentId");
        state = Objects.requireNonNull(state, "state");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        observation = Objects.requireNonNull(observation, "observation");
    }
}

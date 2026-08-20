package gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle;

import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;
import java.util.Optional;

/** Live component outcome from one application lifecycle request. / 一次应用生命周期请求中的组件实时结果。 */
public record ComponentLifecycleResult(
        String componentId,
        boolean actionAttempted,
        boolean accepted,
        LocalizedMessage message,
        Optional<LifecycleObservation> observation
) {
    /** Validates bounded component evidence. / 验证有界的组件证据。 */
    public ComponentLifecycleResult {
        componentId = Objects.requireNonNull(componentId, "componentId");
        message = Objects.requireNonNull(message, "message");
        observation = Objects.requireNonNull(observation, "observation");
        if (accepted && observation.isEmpty()) {
            throw new IllegalArgumentException("accepted component lifecycle results require a live observation");
        }
    }
}

package gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle;

import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationAutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Authoritative application lifecycle result with every component preserved. / 保留每个组件的权威应用生命周期结果。 */
public record MultiComponentLifecycleResult(
        boolean accepted,
        LocalizedMessage message,
        ApplicationRuntimeState runtimeState,
        ApplicationAutostartState autostartState,
        List<ComponentLifecycleResult> componentResults
) {
    /** Validates whole-application and component outcome consistency. / 验证整体应用与组件结果一致性。 */
    public MultiComponentLifecycleResult {
        message = Objects.requireNonNull(message, "message");
        runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
        autostartState = Objects.requireNonNull(autostartState, "autostartState");
        componentResults = Objects.requireNonNull(componentResults, "componentResults").stream()
                .sorted(Comparator.comparing(ComponentLifecycleResult::componentId)).toList();
        if (componentResults.isEmpty()) throw new IllegalArgumentException("application lifecycle results cannot be empty");
        if (accepted && componentResults.stream().anyMatch(result -> !result.accepted())) {
            throw new IllegalArgumentException("accepted application lifecycle results require every component to be accepted");
        }
    }
}

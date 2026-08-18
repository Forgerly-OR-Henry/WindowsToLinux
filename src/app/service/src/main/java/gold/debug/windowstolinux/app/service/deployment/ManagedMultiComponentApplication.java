package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.shared.deploy.lifecycle.ManagedComponentLifecycle;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;

import java.util.List;
import java.util.Objects;

/** Durable secret-free application topology used after desktop restarts. / 桌面应用重启后使用的持久且不含秘密的应用拓扑。 */
public record ManagedMultiComponentApplication(
        MultiComponentDeploymentPlan plan,
        List<ManagedComponentLifecycle> components,
        String healthComponentId
) {
    /** Validates exact graph coverage. / 验证精确图覆盖。 */
    public ManagedMultiComponentApplication {
        plan = Objects.requireNonNull(plan, "plan");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        healthComponentId = Objects.requireNonNull(healthComponentId, "healthComponentId");
        if (!components.stream().map(ManagedComponentLifecycle::componentId).collect(
                java.util.stream.Collectors.toSet()).equals(plan.candidateNamespaces().keySet())
                || components.stream().map(value -> value.application().id()).distinct().count() != components.size()
                || !plan.candidateNamespaces().containsKey(healthComponentId)) {
            throw new IllegalArgumentException("managed lifecycle topology must exactly match its component plan");
        }
    }
}

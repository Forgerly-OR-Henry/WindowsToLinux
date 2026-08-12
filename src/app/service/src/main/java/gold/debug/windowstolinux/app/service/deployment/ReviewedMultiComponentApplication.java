package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.shared.deploy.plan.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlan;

import java.util.List;
import java.util.Objects;

/** Complete secret-free review object for one whole-application transaction. / 一次整应用事务的完整无秘密审阅对象。 */
public record ReviewedMultiComponentApplication(
        MultiComponentDeploymentPlan plan,
        List<ReviewedComponentApplication> components,
        ApplicationHealthGate applicationHealth
) {
    /** Validates exact component coverage and health ownership. / 验证精确组件覆盖与健康归属。 */
    public ReviewedMultiComponentApplication {
        plan = Objects.requireNonNull(plan, "plan");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        applicationHealth = Objects.requireNonNull(applicationHealth, "applicationHealth");
        var ids = components.stream().map(ReviewedComponentApplication::componentId).sorted().toList();
        if (!ids.equals(plan.candidateNamespaces().keySet().stream().sorted().toList())
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("reviewed components must exactly cover the application plan");
        }
        if (!plan.candidateNamespaces().containsKey(applicationHealth.componentId())) {
            throw new IllegalArgumentException("whole-application health must be owned by a planned component");
        }
    }
}

package gold.debug.windowstolinux.app.service.deployment.multi;

import gold.debug.windowstolinux.shared.deploy.execution.lifecycle.ManagedComponentLifecycle;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;

import java.util.List;
import java.util.Objects;

/**
 * Durable secret-free application topology used after desktop restarts. / 桌面应用重启后使用的持久且不含秘密的应用拓扑。
 *
 * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param healthComponentId health component id / 健康组件标识
 */
public record ManagedMultiComponentApplication(
        MultiComponentDeploymentPlan plan,
        List<ManagedComponentLifecycle> components,
        String healthComponentId
) {
    /**
     * Validates exact graph coverage. / 验证精确图覆盖。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param healthComponentId health component id / 健康组件标识
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

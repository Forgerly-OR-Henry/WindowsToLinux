package gold.debug.windowstolinux.app.service.deployment.multi;

import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;

import java.util.List;
import java.util.Objects;

/**
 * Complete secret-free review object for one whole-application transaction. / 一次整应用事务的完整无秘密审阅对象。
 *
 * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
 */
public record ReviewedMultiComponentApplication(
        MultiComponentDeploymentPlan plan,
        List<ReviewedComponentApplication> components,
        ApplicationHealthGate applicationHealth
) {
    /**
     * Validates exact component coverage and health ownership. / 验证精确组件覆盖与健康归属。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

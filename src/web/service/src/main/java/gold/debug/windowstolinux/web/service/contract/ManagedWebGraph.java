package gold.debug.windowstolinux.web.service.contract;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlanner;
import java.util.*;

/**
 * Carries workspace-owned application topology independent of HTTP and local filesystem paths.
 * <p>携带工作区持有的应用拓扑，独立于 HTTP 及本地文件系统路径。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param healthOwner health owner / 健康所有者
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 */
public record ManagedWebGraph(String applicationId, String healthOwner, List<ManagedWebComponent> components) {
    /**
     * Validates and binds the inputs required by managed web graph.
     * <p>校验并绑定受管Web图所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param healthOwner health owner / 健康所有者
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public ManagedWebGraph {
        components = List.copyOf(components);
        if (components.isEmpty() || components.size() > 64 || components.stream().noneMatch(c -> c.id().equals(healthOwner)))
            throw new IllegalArgumentException("Invalid managed application graph");
    }
    /**
     * Builds multi component deployment plan from the supplied plan inputs.
     * <p>根据所提供计划输入构建多组件部署计划。
     *
     * @return multi component deployment plan from the supplied plan inputs / 根据所提供计划输入构建多组件部署计划
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public MultiComponentDeploymentPlan plan() {
        var namespaces = new LinkedHashMap<String, String>(); var dependencies = new LinkedHashMap<String, List<String>>();
        for (var component : components) {
            if (namespaces.put(component.id(), component.application().id()) != null) throw new IllegalArgumentException("Duplicate component");
            dependencies.put(component.id(), component.dependencies());
        }
        return new MultiComponentDeploymentPlanner().restore(applicationId, namespaces, dependencies);
    }
}

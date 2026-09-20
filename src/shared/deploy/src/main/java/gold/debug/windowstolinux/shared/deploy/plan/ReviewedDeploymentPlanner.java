package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.extension.registry.DeploymentAdapterRegistry;
import java.util.Objects;

/**
 * Routes a reviewed request to the only adapter that can plan its selected single-component project type.
 *
 * <p>将经审阅的请求路由到唯一能够计划其选定单组件项目类型的适配器。
 */
public final class ReviewedDeploymentPlanner {
    private final DeploymentAdapterRegistry adapters;

    /**
     * Creates a planner with every typed deployment adapter.
     *
     * <p>使用每个部署适配器创建计划器。
     */
    public ReviewedDeploymentPlanner() {
        this(DeploymentAdapterRegistry.defaults());
    }

    ReviewedDeploymentPlanner(DeploymentAdapterRegistry adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    /**
     * Produces the type-specific deterministic plan.
     *
     * <p>生成类型专属确定性计划。
     *
     * @param request the reviewed request / 经审阅的请求
     * @return the deployment plan / 部署计划
     */
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        request = Objects.requireNonNull(request, "request");
        gold.debug.windowstolinux.shared.deploy.input.ApplicationDeclaration.verifyBuildOwnership(request.facts(), request.runtime());
        return adapters.require(request.facts().projectType()).plan(request);
    }
}

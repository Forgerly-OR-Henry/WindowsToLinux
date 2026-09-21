package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.extension.registry.DeploymentAdapterRegistry;
import java.util.Objects;

/**
 * Routes a reviewed request to the only adapter that can plan its selected single-component project type.
 *
 *  <p>将经审阅的请求路由到唯一能够计划其选定单组件项目类型的适配器。
 */
public final class ReviewedDeploymentPlanner {
    /**
     * Adapters.
     * <p>适配器集合。
     */
    private final DeploymentAdapterRegistry adapters;

    /**
     * Creates a planner with every typed deployment adapter.
     *
     *  <p>使用每个部署适配器创建计划器。
     */
    public ReviewedDeploymentPlanner() {
        this(DeploymentAdapterRegistry.defaults());
    }

    /**
     * Validates and binds the inputs required by reviewed deployment planner.
     * <p>校验并绑定已审阅部署规划器所需输入。
     *
     * @param adapters adapters / 适配器集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    ReviewedDeploymentPlanner(DeploymentAdapterRegistry adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    /**
     * Produces the type-specific deterministic plan.
     *
     *  <p>生成类型专属确定性计划。
     *
     * @param request the reviewed request / 经审阅的请求
     * @return the deployment plan / 部署计划
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        request = Objects.requireNonNull(request, "request");
        gold.debug.windowstolinux.shared.deploy.input.ApplicationDeclaration.verifyBuildOwnership(request.facts(), request.runtime());
        return adapters.require(request.facts().projectType()).plan(request);
    }
}

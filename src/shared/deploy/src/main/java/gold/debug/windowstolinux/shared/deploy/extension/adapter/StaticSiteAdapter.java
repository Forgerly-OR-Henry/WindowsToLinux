package gold.debug.windowstolinux.shared.deploy.extension.adapter;

import gold.debug.windowstolinux.shared.deploy.contract.spi.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.extension.adapter.DeploymentPlanFactory;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Plans static-site publication only after verifying the declared generated output directory.
 *
 * <p>只在验证声明的生成输出目录后计划静态站点发布。
 */
public final class StaticSiteAdapter implements DeploymentAdapter {
    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.STATIC_SITE; }
    /** Builds the reviewed deployment plan. / 构建经审阅的部署计划。 */
    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        DeploymentRuntimeSpecification.StaticSite runtime = (DeploymentRuntimeSpecification.StaticSite) request.runtime();
        boolean nodeBuild = request.facts().buildTool() == DeploymentBuildToolType.NPM
                || request.facts().buildTool() == DeploymentBuildToolType.PNPM
                || request.facts().buildTool() == DeploymentBuildToolType.YARN;
        if (nodeBuild != runtime.nodeMajorVersion().isPresent()) {
            throw new IllegalArgumentException("built static sites require an explicit Node.js major and pure static sites forbid one");
        }
        return DeploymentPlanFactory.plan(request, projectType(), true, false);
    }
}

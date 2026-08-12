package gold.debug.windowstolinux.shared.deploy.adapter.staticweb;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Plans static-site publication only after verifying the declared generated output directory.
 *
 * <p>只在验证声明的生成输出目录后计划静态站点发布。
 */
public final class StaticSiteAdapter implements DeploymentAdapter {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.STATIC_SITE; }
    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        DeploymentRuntimeSpecification.StaticSite runtime = (DeploymentRuntimeSpecification.StaticSite) request.runtime();
        boolean nodeBuild = request.facts().buildTool() == DeploymentBuildTool.NPM
                || request.facts().buildTool() == DeploymentBuildTool.PNPM
                || request.facts().buildTool() == DeploymentBuildTool.YARN;
        if (nodeBuild != runtime.nodeMajorVersion().isPresent()) {
            throw new IllegalArgumentException("built static sites require an explicit Node.js major and pure static sites forbid one");
        }
        return DeploymentPlanSupport.plan(request, projectType(), true, false);
    }
}

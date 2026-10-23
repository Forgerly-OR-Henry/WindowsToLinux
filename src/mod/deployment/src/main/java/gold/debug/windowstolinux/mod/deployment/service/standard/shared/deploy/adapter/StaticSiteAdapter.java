package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.adapter;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentAdapter;

/**
 * Plans static-site publication only after verifying the declared generated output directory.
 *
 *  <p>只在验证声明的生成输出目录后计划静态站点发布。
 */
public final class StaticSiteAdapter implements DeploymentAdapter {
    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.STATIC_SITE;
    }

    /**
     * Builds the reviewed deployment plan. / 构建经审阅的部署计划。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return the reviewed deployment plan / 经审阅的部署计划
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        DeploymentRuntimeDefinition.StaticSite runtime = (DeploymentRuntimeDefinition.StaticSite) request.runtime();
        boolean nodeBuild = request.facts().buildTool() == DeploymentBuildToolType.NPM
            || request.facts().buildTool() == DeploymentBuildToolType.PNPM
            || request.facts().buildTool() == DeploymentBuildToolType.YARN;
        if (nodeBuild != runtime.nodeMajorVersion().isPresent()) {
            throw new IllegalArgumentException(
                "built static sites require an explicit Node.js major and pure static sites forbid one");
        }
        return DeploymentPlanFactory.plan(request, projectType(), true, false);
    }
}

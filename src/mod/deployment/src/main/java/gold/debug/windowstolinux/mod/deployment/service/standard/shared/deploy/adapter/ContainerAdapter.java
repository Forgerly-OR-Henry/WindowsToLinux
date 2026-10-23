package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.adapter;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentAdapter;

/**
 * Plans one Dockerfile image and one managed Docker or Podman container after policy validation.
 *
 *  <p>在策略验证后计划一个 Dockerfile 镜像和一个受管 Docker 或 Podman 容器。
 */
public final class ContainerAdapter implements DeploymentAdapter {
    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.DOCKERFILE_CONTAINER;
    }

    /**
     * Builds the reviewed deployment plan. / 构建经审阅的部署计划。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return the reviewed deployment plan / 经审阅的部署计划
     */
    @Override
    public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanFactory.plan(request, projectType(), false, true);
    }
}

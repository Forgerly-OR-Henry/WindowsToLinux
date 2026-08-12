package gold.debug.windowstolinux.shared.deploy.adapter.container;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/**
 * Plans one Dockerfile image and one managed Docker or Podman container after policy validation.
 *
 * <p>在策略验证后计划一个 Dockerfile 镜像和一个受管 Docker 或 Podman 容器。
 */
public final class ContainerAdapter implements DeploymentAdapter {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOCKERFILE_CONTAINER; }
    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanSupport.plan(request, projectType(), false, true);
    }
}

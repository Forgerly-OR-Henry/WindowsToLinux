package gold.debug.windowstolinux.shared.deploy.adapter.container;

import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoDeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

/**
 * Plans one Dockerfile image and one managed Docker or Podman container after policy validation.
 *
 * <p>在策略验证后计划一个 Dockerfile 镜像和一个受管 Docker 或 Podman 容器。
 */
public final class ContainerAdapter implements PhaseTwoDeploymentAdapter {
    @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.DOCKERFILE_CONTAINER; }
    @Override public PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request) {
        return PhaseTwoPlanSupport.plan(request, projectType(), false, true);
    }
}

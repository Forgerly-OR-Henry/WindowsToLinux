package gold.debug.windowstolinux.shared.deploy.adapter.node;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/**
 * Plans a lockfile-backed Node.js service through its fixed package-manager entrypoint.
 *
 * <p>通过固定包管理器入口计划由锁文件支持的 Node.js 服务。
 */
public final class NodeServiceAdapter implements DeploymentAdapter {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.NODE_SERVICE; }
    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanSupport.plan(request, projectType(), false, false);
    }
}

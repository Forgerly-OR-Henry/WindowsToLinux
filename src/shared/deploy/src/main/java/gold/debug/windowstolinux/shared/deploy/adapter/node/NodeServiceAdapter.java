package gold.debug.windowstolinux.shared.deploy.adapter.node;

import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoDeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

/**
 * Plans a lockfile-backed Node.js service through its fixed package-manager entrypoint.
 *
 * <p>通过固定包管理器入口计划由锁文件支持的 Node.js 服务。
 */
public final class NodeServiceAdapter implements PhaseTwoDeploymentAdapter {
    @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.NODE_SERVICE; }
    @Override public PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request) {
        return PhaseTwoPlanSupport.plan(request, projectType(), false, false);
    }
}

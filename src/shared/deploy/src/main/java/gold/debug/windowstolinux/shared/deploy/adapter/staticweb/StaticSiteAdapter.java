package gold.debug.windowstolinux.shared.deploy.adapter.staticweb;

import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoDeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

/**
 * Plans static-site publication only after verifying the declared generated output directory.
 *
 * <p>只在验证声明的生成输出目录后计划静态站点发布。
 */
public final class StaticSiteAdapter implements PhaseTwoDeploymentAdapter {
    @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.STATIC_SITE; }
    @Override public PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request) {
        return PhaseTwoPlanSupport.plan(request, projectType(), true, false);
    }
}

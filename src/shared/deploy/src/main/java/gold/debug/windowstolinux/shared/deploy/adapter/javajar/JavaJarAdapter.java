package gold.debug.windowstolinux.shared.deploy.adapter.javajar;

import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoDeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

/**
 * Plans the typed Java JAR launcher path without accepting an arbitrary startup command.
 *
 * <p>计划类型化 Java JAR 启动器路径，不接受任意启动命令。
 */
public final class JavaJarAdapter implements PhaseTwoDeploymentAdapter {
    @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.JAVA_JAR; }
    @Override public PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request) {
        return PhaseTwoPlanSupport.plan(request, projectType(), false, false);
    }
}

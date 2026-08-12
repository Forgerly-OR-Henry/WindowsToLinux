package gold.debug.windowstolinux.shared.deploy.adapter.javajar;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/**
 * Plans the typed Java JAR launcher path without accepting an arbitrary startup command.
 *
 * <p>计划类型化 Java JAR 启动器路径，不接受任意启动命令。
 */
public final class JavaJarAdapter implements DeploymentAdapter {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_JAR; }
    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanSupport.plan(request, projectType(), false, false);
    }
}

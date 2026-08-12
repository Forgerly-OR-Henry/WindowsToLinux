package gold.debug.windowstolinux.shared.deploy.adapter.springboot;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/**
 * Plans the fixed Gradle Wrapper Spring Boot executable-JAR path.
 *
 * <p>计划固定的 Gradle Wrapper Spring Boot 可执行 JAR 路径。
 */
public final class GradleSpringBootAdapter implements DeploymentAdapter {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GRADLE_SPRING_BOOT; }
    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanSupport.plan(request, projectType(), false, false);
    }
}

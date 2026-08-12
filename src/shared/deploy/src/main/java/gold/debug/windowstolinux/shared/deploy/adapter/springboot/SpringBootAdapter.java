package gold.debug.windowstolinux.shared.deploy.adapter.springboot;

import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.DeploymentPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

/** Plans the reviewed Spring Boot executable-JAR path independent of its fixed build tool. / 计划与固定构建工具无关的经审阅 Spring Boot 可执行 JAR 路径。 */
public final class SpringBootAdapter implements DeploymentAdapter {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }
    @Override public ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request) {
        return DeploymentPlanSupport.plan(request, projectType(), false, false);
    }
}

package gold.debug.windowstolinux.shared.deploy.adapter.springboot;

import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoDeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoPlanSupport;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

/**
 * Plans the fixed Gradle Wrapper Spring Boot executable-JAR path.
 *
 * <p>计划固定的 Gradle Wrapper Spring Boot 可执行 JAR 路径。
 */
public final class GradleSpringBootAdapter implements PhaseTwoDeploymentAdapter {
    @Override public PhaseTwoProjectType projectType() { return PhaseTwoProjectType.GRADLE_SPRING_BOOT; }
    @Override public PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request) {
        return PhaseTwoPlanSupport.plan(request, projectType(), false, false);
    }
}

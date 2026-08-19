package gold.debug.windowstolinux.shared.model.project;

import java.util.Objects;

/** One exact deployable project-type and build-tool architecture pair. / 一个精确且可部署的项目类型与构建工具架构组合。 */
public record DeploymentArchitectureType(DeploymentProjectType projectType, DeploymentBuildToolType buildTool) {
    /** Validates that the architecture pair has a checked-in support claim. / 验证架构组合具有已检入支持声明。 */
    public DeploymentArchitectureType {
        projectType = Objects.requireNonNull(projectType, "projectType");
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        if (!projectType.deployable()) {
            throw new IllegalArgumentException("deployment architecture must use a deployable project type");
        }
        DeploymentSupportCatalog.forArchitecture(projectType, buildTool);
    }
}

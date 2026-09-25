package gold.debug.windowstolinux.shared.model.project;

import java.util.Objects;

/**
 * One exact deployable project-type and build-tool architecture pair. / 一个精确且可部署的项目类型与构建工具架构组合。
 *
 * @param projectType supported project deployment category / 受支持的项目部署类别
 * @param buildTool the fixed build entrypoint / 固定构建入口
 */
public record DeploymentArchitectureType(DeploymentProjectType projectType, DeploymentBuildToolType buildTool) {
    /**
     * Validates that the architecture pair has a checked-in support claim. / 验证架构组合具有已检入支持声明。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentArchitectureType {
        projectType = Objects.requireNonNull(projectType, "projectType");
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        if (!projectType.deployable()) {
            throw new IllegalArgumentException("deployment architecture must use a deployable project type");
        }
        DeploymentSupportCatalog.forArchitecture(projectType, buildTool);
    }
}

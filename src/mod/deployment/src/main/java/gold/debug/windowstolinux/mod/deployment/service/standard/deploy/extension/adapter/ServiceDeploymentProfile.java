package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.extension.adapter;

import java.util.EnumSet;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;

/**
 * Immutable profile for one ordinary systemd service project type.
 *
 *  <p>一个普通 systemd 服务项目类型的不可变 Profile。
 *
 * @param projectType supported project deployment category / 受支持的项目部署类别
 */
public record ServiceDeploymentProfile(DeploymentProjectKind projectType) {
    /**
     * SERVICE TYPES.
     * <p>服务类型集合。
     */
    private static final EnumSet<DeploymentProjectKind> SERVICE_TYPES = EnumSet.of(DeploymentProjectKind.SPRING_BOOT,
        DeploymentProjectKind.JAVA_JAR, DeploymentProjectKind.JAVA_SOURCE, DeploymentProjectKind.NODE_SERVICE,
        DeploymentProjectKind.PYTHON_SERVICE, DeploymentProjectKind.GO_SERVICE, DeploymentProjectKind.RUST_SERVICE,
        DeploymentProjectKind.DOTNET_SERVICE, DeploymentProjectKind.KOTLIN_SERVICE,
        DeploymentProjectKind.PHP_SERVICE, DeploymentProjectKind.RUBY_SERVICE, DeploymentProjectKind.CMAKE_SERVICE);

    /**
     * Validates that this profile represents a normal service rather than a workload. / 验证 Profile 表示普通服务而不是工作负载。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServiceDeploymentProfile {
        projectType = Objects.requireNonNull(projectType, "projectType");
        if (!SERVICE_TYPES.contains(projectType)) {
            throw new IllegalArgumentException(
                "service deployment profiles may not represent workloads or preview types");
        }
    }

    /**
     * Returns the complete fixed service profile set. / 返回完整固定的服务 Profile 集合。
     *
     * @return the complete fixed service profile set / 完整固定的服务 Profile 集合
     */
    public static java.util.List<ServiceDeploymentProfile> defaults() {
        return SERVICE_TYPES.stream().map(ServiceDeploymentProfile::new).toList();
    }
}

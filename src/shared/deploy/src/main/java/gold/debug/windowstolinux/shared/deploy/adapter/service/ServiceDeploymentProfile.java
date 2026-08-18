package gold.debug.windowstolinux.shared.deploy.adapter.service;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Immutable profile for one ordinary systemd service project type.
 *
 * <p>一个普通 systemd 服务项目类型的不可变 Profile。
 */
public record ServiceDeploymentProfile(DeploymentProjectType projectType) {
    private static final EnumSet<DeploymentProjectType> SERVICE_TYPES = EnumSet.of(
            DeploymentProjectType.SPRING_BOOT, DeploymentProjectType.JAVA_JAR,
            DeploymentProjectType.NODE_SERVICE, DeploymentProjectType.PYTHON_SERVICE,
            DeploymentProjectType.GO_SERVICE, DeploymentProjectType.RUST_SERVICE,
            DeploymentProjectType.DOTNET_SERVICE, DeploymentProjectType.KOTLIN_SERVICE,
            DeploymentProjectType.PHP_SERVICE, DeploymentProjectType.RUBY_SERVICE);

    /** Validates that this profile represents a normal service rather than a workload. / 验证 Profile 表示普通服务而不是工作负载。 */
    public ServiceDeploymentProfile {
        projectType = Objects.requireNonNull(projectType, "projectType");
        if (!SERVICE_TYPES.contains(projectType)) {
            throw new IllegalArgumentException("service deployment profiles may not represent workloads or preview types");
        }
    }

    /** Returns the complete fixed service profile set. / 返回完整固定的服务 Profile 集合。 */
    public static java.util.List<ServiceDeploymentProfile> defaults() {
        return SERVICE_TYPES.stream().map(ServiceDeploymentProfile::new).toList();
    }
}

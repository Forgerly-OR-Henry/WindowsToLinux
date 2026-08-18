package gold.debug.windowstolinux.shared.analyze.registry;

import gold.debug.windowstolinux.shared.analyze.ecosystem.ServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.SpringBootDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.JavaJarDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.PythonServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.preview.PreviewInspector;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.workload.ContainerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.workload.StaticWebDeploymentInspector;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the complete, validated assembly of source-type inspectors.
 *
 * <p>持有完整且已验证的源码类型检查器装配。
 */
public final class DeploymentTypeInspectorRegistry {
    private final Map<DeploymentProjectType, DeploymentTypeInspector> inspectors;

    /** Creates the default complete inspector assembly. / 创建默认的完整检查器装配。 */
    public static DeploymentTypeInspectorRegistry defaults() {
        return new DeploymentTypeInspectorRegistry(List.of(
                new SpringBootDeploymentInspector(), new JavaJarDeploymentInspector(),
                new NodeServiceDeploymentInspector(), new PythonServiceDeploymentInspector(),
                new StaticWebDeploymentInspector(), new ContainerDeploymentInspector(),
                new ServiceDeploymentInspector(DeploymentProjectType.GO_SERVICE),
                new ServiceDeploymentInspector(DeploymentProjectType.RUST_SERVICE),
                new ServiceDeploymentInspector(DeploymentProjectType.DOTNET_SERVICE),
                new ServiceDeploymentInspector(DeploymentProjectType.KOTLIN_SERVICE),
                new ServiceDeploymentInspector(DeploymentProjectType.PHP_SERVICE),
                new ServiceDeploymentInspector(DeploymentProjectType.RUBY_SERVICE),
                new PreviewInspector()));
    }

    /** Validates and indexes exactly one inspector for every project type. / 验证并索引每种项目类型恰好一个检查器。 */
    public DeploymentTypeInspectorRegistry(List<DeploymentTypeInspector> inspectors) {
        EnumMap<DeploymentProjectType, DeploymentTypeInspector> indexed = new EnumMap<>(DeploymentProjectType.class);
        for (DeploymentTypeInspector inspector : Objects.requireNonNull(inspectors, "inspectors")) {
            inspector = Objects.requireNonNull(inspector, "inspector");
            if (indexed.putIfAbsent(inspector.projectType(), inspector) != null) {
                throw new IllegalArgumentException("each deployment type requires exactly one inspector");
            }
        }
        if (!indexed.keySet().equals(java.util.EnumSet.allOf(DeploymentProjectType.class))) {
            throw new IllegalArgumentException("every deployment type requires an inspector");
        }
        this.inspectors = Map.copyOf(indexed);
    }

    /** Returns the inspector registered for one selected type. / 返回为所选类型注册的检查器。 */
    public DeploymentTypeInspector require(DeploymentProjectType projectType) {
        DeploymentTypeInspector inspector = inspectors.get(Objects.requireNonNull(projectType, "projectType"));
        if (inspector == null || inspector.projectType() != projectType) {
            throw new IllegalArgumentException("no matching deployment type inspector is registered");
        }
        return inspector;
    }
}

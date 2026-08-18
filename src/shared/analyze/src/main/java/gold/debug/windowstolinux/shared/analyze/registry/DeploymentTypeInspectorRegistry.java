package gold.debug.windowstolinux.shared.analyze.registry;

import gold.debug.windowstolinux.shared.analyze.ecosystem.dotnet.project.service.DotNetServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.go.project.service.GoServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.framework.springboot.SpringBootDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.project.jar.JavaJarDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.project.kotlin.KotlinServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.project.service.NodeServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.php.project.service.PhpServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.project.service.PythonServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.project.service.RubyServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.rust.project.service.RustServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.preview.PreviewInspector;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.workload.container.ContainerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.workload.staticweb.StaticWebDeploymentInspector;
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
                new GoServiceDeploymentInspector(), new RustServiceDeploymentInspector(),
                new DotNetServiceDeploymentInspector(), new KotlinServiceDeploymentInspector(),
                new PhpServiceDeploymentInspector(), new RubyServiceDeploymentInspector(),
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

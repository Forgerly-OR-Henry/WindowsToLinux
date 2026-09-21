package gold.debug.windowstolinux.shared.analyze.extension.registry;

import gold.debug.windowstolinux.shared.analyze.ecosystem.c.cmake.CmakeDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.dotnet.dotnetsdk.DotNetSdkDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.go.gomodule.GoModuleDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.KotlinServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.php.PhpServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.RubyServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.rust.cargo.RustCargoDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.java.SpringBootDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.java.jar.JavaJarDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.java.jdk.JavaJdkDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.PythonServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.preview.PreviewInspector;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
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
 *  <p>持有完整且已验证的源码类型检查器装配。
 */
public final class DeploymentTypeInspectorRegistry {
    /**
     * Inspectors.
     * <p>检查器集合。
     */
    private final Map<DeploymentProjectType, DeploymentTypeInspector> inspectors;

    /**
     * Creates the default complete inspector assembly. / 创建默认的完整检查器装配。
     *
     * @return the default complete inspector assembly / 默认的完整检查器装配
     */
    public static DeploymentTypeInspectorRegistry defaults() {
        return new DeploymentTypeInspectorRegistry(List.of(
                new SpringBootDeploymentInspector(), new JavaJarDeploymentInspector(), new JavaJdkDeploymentInspector(),
                new NodeServiceDeploymentInspector(), new PythonServiceDeploymentInspector(),
                new StaticWebDeploymentInspector(), new ContainerDeploymentInspector(),
                new GoModuleDeploymentInspector(), new RustCargoDeploymentInspector(),
                new DotNetSdkDeploymentInspector(), new KotlinServiceDeploymentInspector(),
                new PhpServiceDeploymentInspector(), new RubyServiceDeploymentInspector(), new CmakeDeploymentInspector(),
                new PreviewInspector()));
    }

    /**
     * Validates and indexes exactly one inspector for every project type. / 验证并索引每种项目类型恰好一个检查器。
     *
     * @param inspectors inspectors / 检查器集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Returns the inspector registered for one selected type. / 返回为所选类型注册的检查器。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return the inspector registered for one selected type / 为所选类型注册的检查器
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentTypeInspector require(DeploymentProjectType projectType) {
        DeploymentTypeInspector inspector = inspectors.get(Objects.requireNonNull(projectType, "projectType"));
        if (inspector == null || inspector.projectType() != projectType) {
            throw new IllegalArgumentException("no matching deployment type inspector is registered");
        }
        return inspector;
    }
}

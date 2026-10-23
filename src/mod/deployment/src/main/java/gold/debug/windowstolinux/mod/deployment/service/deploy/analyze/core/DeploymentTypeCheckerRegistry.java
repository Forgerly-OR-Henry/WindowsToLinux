package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.contract.DeploymentTypeChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.c.cmake.CmakeDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.dotnet.dotnetsdk.DotNetSdkDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.go.gomodule.GoModuleDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.java.SpringBootDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.java.jar.JavaJarDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.java.jdk.JavaJdkDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.kotlin.KotlinServiceDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.node.NodeServiceDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.php.PhpServiceDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.python.PythonServiceDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.ruby.RubyServiceDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.rust.cargo.RustCargoDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.preview.PreviewChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.workload.ContainerDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.workload.StaticWebDeploymentChecker;

/**
 * Owns the complete, validated assembly of source-type inspectors.
 *
 *  <p>持有完整且已验证的源码类型检查器装配。
 */
public final class DeploymentTypeCheckerRegistry {
    /**
     * Inspectors.
     * <p>检查器集合。
     */
    private final Map<DeploymentProjectKind, DeploymentTypeChecker> inspectors;

    /**
     * Creates the default complete inspector assembly. / 创建默认的完整检查器装配。
     *
     * @return the default complete inspector assembly / 默认的完整检查器装配
     */
    public static DeploymentTypeCheckerRegistry defaults() {
        return new DeploymentTypeCheckerRegistry(
            List.of(new SpringBootDeploymentChecker(), new JavaJarDeploymentChecker(),
                new JavaJdkDeploymentChecker(), new NodeServiceDeploymentChecker(),
                new PythonServiceDeploymentChecker(), new StaticWebDeploymentChecker(),
                new ContainerDeploymentChecker(), new GoModuleDeploymentChecker(),
                new RustCargoDeploymentChecker(), new DotNetSdkDeploymentChecker(),
                new KotlinServiceDeploymentChecker(), new PhpServiceDeploymentChecker(),
                new RubyServiceDeploymentChecker(), new CmakeDeploymentChecker(), new PreviewChecker()));
    }

    /**
     * Validates and indexes exactly one inspector for every project type. / 验证并索引每种项目类型恰好一个检查器。
     *
     * @param inspectors inspectors / 检查器集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentTypeCheckerRegistry(List<DeploymentTypeChecker> inspectors) {
        EnumMap<DeploymentProjectKind, DeploymentTypeChecker> indexed = new EnumMap<>(DeploymentProjectKind.class);
        for (DeploymentTypeChecker inspector : Objects.requireNonNull(inspectors, "inspectors")) {
            inspector = Objects.requireNonNull(inspector, "inspector");
            if (indexed.putIfAbsent(inspector.projectType(), inspector) != null) {
                throw new IllegalArgumentException("each deployment type requires exactly one inspector");
            }
        }
        var required = java.util.EnumSet.copyOf(DeploymentProjectKind.deployableTypes());
        required.add(DeploymentProjectKind.RECOGNITION_PREVIEW);
        if (!indexed.keySet().equals(required)) {
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
    public DeploymentTypeChecker require(DeploymentProjectKind projectType) {
        DeploymentTypeChecker inspector = inspectors.get(Objects.requireNonNull(projectType, "projectType"));
        if (inspector == null || inspector.projectType() != projectType) {
            throw new IllegalArgumentException("no matching deployment type inspector is registered");
        }
        return inspector;
    }
}

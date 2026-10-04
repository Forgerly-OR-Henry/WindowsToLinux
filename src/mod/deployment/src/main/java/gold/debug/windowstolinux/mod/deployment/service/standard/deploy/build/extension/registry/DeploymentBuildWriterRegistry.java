package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.extension.registry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentArchitectureType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentSupportCatalog;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.contract.spi.DeploymentBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.ecosystem.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.ecosystem.java.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.ecosystem.kotlin.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.ecosystem.node.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.ecosystem.php.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.ecosystem.python.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.ecosystem.ruby.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.workload.*;

/**
 * Registers exactly one named renderer for every deployable project architecture. / 为每种可部署项目架构恰好注册一个具名 Renderer。
 */
public final class DeploymentBuildWriterRegistry {
    /** Creates the complete fixed renderer registry. / 创建完整固定渲染器登记。
     * @return one renderer for every deployable architecture / 每种可部署架构各一渲染器 */
    public static DeploymentBuildWriterRegistry defaults() {
        return new DeploymentBuildWriterRegistry(List.of(new GradleBuildWriter(), new MavenBuildWriter(),
            new JavaJarBuildWriter(), new JdkBuildWriter(), new NpmBuildWriter(), new PnpmBuildWriter(),
            new YarnBuildWriter(), new PipBuildWriter(), new PipenvBuildWriter(), new PoetryBuildWriter(),
            new UvBuildWriter(), new StaticSiteBuildWriter(), new ContainerBuildWriter(),
            new GoBuildWriter(), new CargoBuildWriter(), new DotNetSdkBuildWriter(),
            new KotlinGradleBuildWriter(), new KotlinCompilerBuildWriter(), new ComposerBuildWriter(),
            new PhpCliBuildWriter(), new BundlerBuildWriter(), new RubyCliBuildWriter(),
            new CmakeBuildWriter()));
    }

    /**
     * Renderers.
     * <p>渲染器集合。
     */
    private final Map<DeploymentArchitectureType, DeploymentBuildWriter> renderers;

    /**
     * Validates and indexes exact project/build-tool ownership. / 验证并索引精确项目/构建工具归属。
     *
     * @param renderers renderers / 渲染器集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentBuildWriterRegistry(List<DeploymentBuildWriter> renderers) {
        Map<DeploymentArchitectureType, DeploymentBuildWriter> registered = new HashMap<>();
        for (DeploymentBuildWriter renderer : Objects.requireNonNull(renderers, "renderers")) {
            renderer = Objects.requireNonNull(renderer, "renderer");
            if (renderer.buildTools() == null || renderer.buildTools().isEmpty()) {
                throw new IllegalArgumentException("deployment build renderer must own at least one build tool");
            }
            for (DeploymentBuildToolType buildTool : renderer.buildTools()) {
                DeploymentArchitectureType architecture = new DeploymentArchitectureType(renderer.projectType(),
                    buildTool);
                if (registered.putIfAbsent(architecture, renderer) != null) {
                    throw new IllegalArgumentException("duplicate deployment build renderer for " + architecture);
                }
            }
        }
        if (!registered.keySet().equals(DeploymentSupportCatalog.deployableArchitectures())) {
            throw new IllegalArgumentException("one build renderer is required for every deployable architecture");
        }
        this.renderers = Map.copyOf(registered);
    }

    /**
     * Returns the renderer for one exact reviewed architecture. / 返回一个精确经审阅架构的 Renderer。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @return the renderer for one exact reviewed architecture / 一个精确经审阅架构的 Renderer
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentBuildWriter require(DeploymentProjectKind projectType, DeploymentBuildToolType buildTool) {
        DeploymentArchitectureType architecture = new DeploymentArchitectureType(
            Objects.requireNonNull(projectType, "projectType"), Objects.requireNonNull(buildTool, "buildTool"));
        DeploymentBuildWriter renderer = renderers.get(architecture);
        if (renderer == null || renderer.projectType() != projectType || !renderer.buildTools().contains(buildTool)) {
            throw new IllegalArgumentException("no matching deployment build renderer is registered");
        }
        return renderer;
    }
}

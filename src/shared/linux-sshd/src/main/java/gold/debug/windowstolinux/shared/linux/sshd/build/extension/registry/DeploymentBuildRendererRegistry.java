package gold.debug.windowstolinux.shared.linux.sshd.build.extension.registry;

import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.model.project.DeploymentArchitectureType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportCatalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Registers exactly one named renderer for every deployable project architecture. / 为每种可部署项目架构恰好注册一个具名 Renderer。 */
public final class DeploymentBuildRendererRegistry {
    private final Map<DeploymentArchitectureType, DeploymentBuildRenderer> renderers;

    /** Validates and indexes exact project/build-tool ownership. / 验证并索引精确项目/构建工具归属。 */
    public DeploymentBuildRendererRegistry(List<DeploymentBuildRenderer> renderers) {
        Map<DeploymentArchitectureType, DeploymentBuildRenderer> registered = new HashMap<>();
        for (DeploymentBuildRenderer renderer : Objects.requireNonNull(renderers, "renderers")) {
            renderer = Objects.requireNonNull(renderer, "renderer");
            if (renderer.buildTools() == null || renderer.buildTools().isEmpty()) {
                throw new IllegalArgumentException("deployment build renderer must own at least one build tool");
            }
            for (DeploymentBuildToolType buildTool : renderer.buildTools()) {
                DeploymentArchitectureType architecture = new DeploymentArchitectureType(renderer.projectType(), buildTool);
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

    /** Returns the renderer for one exact reviewed architecture. / 返回一个精确经审阅架构的 Renderer。 */
    public DeploymentBuildRenderer require(DeploymentProjectType projectType, DeploymentBuildToolType buildTool) {
        DeploymentArchitectureType architecture = new DeploymentArchitectureType(
                Objects.requireNonNull(projectType, "projectType"), Objects.requireNonNull(buildTool, "buildTool"));
        DeploymentBuildRenderer renderer = renderers.get(architecture);
        if (renderer == null || renderer.projectType() != projectType || !renderer.buildTools().contains(buildTool)) {
            throw new IllegalArgumentException("no matching deployment build renderer is registered");
        }
        return renderer;
    }
}

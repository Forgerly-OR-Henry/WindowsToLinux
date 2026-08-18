package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Registers exactly one build renderer for every supported project type. / 为每种支持的项目类型恰好注册一个构建渲染器。 */
public final class DeploymentBuildRendererRegistry {
    private final Map<DeploymentProjectType, DeploymentBuildRenderer> renderers;

    /** Creates an instance of this type. / 创建此类型的实例。 */
    public DeploymentBuildRendererRegistry(List<DeploymentBuildRenderer> renderers) {
        EnumMap<DeploymentProjectType, DeploymentBuildRenderer> registered = new EnumMap<>(DeploymentProjectType.class);
        for (DeploymentBuildRenderer renderer : Objects.requireNonNull(renderers, "renderers")) {
            Objects.requireNonNull(renderer, "renderer");
            if (registered.putIfAbsent(renderer.projectType(), renderer) != null) {
                throw new IllegalArgumentException("duplicate deployment build renderer for " + renderer.projectType());
            }
        }
        if (!registered.keySet().equals(DeploymentProjectType.deployableTypes())) {
            throw new IllegalArgumentException("one build renderer is required for every deployment project type");
        }
        this.renderers = Map.copyOf(registered);
    }

    /** Performs the {@code require} operation. / 执行 {@code require} 操作。 */
    public DeploymentBuildRenderer require(DeploymentProjectType projectType) {
        DeploymentBuildRenderer renderer = renderers.get(Objects.requireNonNull(projectType, "projectType"));
        if (renderer == null || renderer.projectType() != projectType) {
            throw new IllegalArgumentException("no matching deployment build renderer is registered");
        }
        return renderer;
    }
}

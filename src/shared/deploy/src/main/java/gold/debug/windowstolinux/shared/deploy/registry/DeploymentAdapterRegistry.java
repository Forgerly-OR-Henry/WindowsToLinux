package gold.debug.windowstolinux.shared.deploy.registry;

import gold.debug.windowstolinux.shared.deploy.adapter.ServiceDeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.ServiceDeploymentProfile;
import gold.debug.windowstolinux.shared.deploy.adapter.ContainerAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.StaticSiteAdapter;
import gold.debug.windowstolinux.shared.deploy.spi.DeploymentAdapter;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the complete validated deployment-adapter assembly.
 *
 * <p>持有完整且已验证的部署适配器装配。
 */
public final class DeploymentAdapterRegistry {
    private final Map<DeploymentProjectType, DeploymentAdapter> adapters;

    /** Creates the fixed service and workload adapter assembly. / 创建固定的服务和工作负载适配器装配。 */
    public static DeploymentAdapterRegistry defaults() {
        List<DeploymentAdapter> adapters = new java.util.ArrayList<>(ServiceDeploymentProfile.defaults().stream()
                .map(ServiceDeploymentAdapter::new).toList());
        adapters.add(new StaticSiteAdapter());
        adapters.add(new ContainerAdapter());
        return new DeploymentAdapterRegistry(adapters);
    }

    /** Validates one adapter for every deployable type. / 验证每个可部署类型恰好一个适配器。 */
    public DeploymentAdapterRegistry(List<DeploymentAdapter> adapters) {
        EnumMap<DeploymentProjectType, DeploymentAdapter> indexed = new EnumMap<>(DeploymentProjectType.class);
        for (DeploymentAdapter adapter : Objects.requireNonNull(adapters, "adapters")) {
            adapter = Objects.requireNonNull(adapter, "adapter");
            if (indexed.putIfAbsent(adapter.projectType(), adapter) != null) {
                throw new IllegalArgumentException("each typed deployment project type must have exactly one adapter");
            }
        }
        if (!indexed.keySet().equals(DeploymentProjectType.deployableTypes())) {
            throw new IllegalArgumentException("every typed deployment project type requires an adapter");
        }
        this.adapters = Map.copyOf(indexed);
    }

    /** Returns the only adapter for a deployable type. / 返回某个可部署类型的唯一适配器。 */
    public DeploymentAdapter require(DeploymentProjectType projectType) {
        DeploymentAdapter adapter = adapters.get(Objects.requireNonNull(projectType, "projectType"));
        if (adapter == null || adapter.projectType() != projectType) {
            throw new IllegalArgumentException("no matching deployment adapter is registered");
        }
        return adapter;
    }
}

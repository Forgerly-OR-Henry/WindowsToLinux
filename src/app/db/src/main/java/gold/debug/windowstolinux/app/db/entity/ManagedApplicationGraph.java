package gold.debug.windowstolinux.app.db.entity;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Durable secret-free topology for one successfully deployed application. / 一个已成功部署应用的持久且不含秘密的拓扑。 */
public record ManagedApplicationGraph(
        String applicationId,
        String healthComponentId,
        List<Component> components
) {
    /** Validates the exact bounded graph. / 验证精确且有界的图。 */
    public ManagedApplicationGraph {
        applicationId = identifier(applicationId, "applicationId");
        healthComponentId = identifier(healthComponentId, "healthComponentId");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.size() > 64) {
            throw new IllegalArgumentException("managed application graph requires 1..64 components");
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> applications = new LinkedHashSet<>();
        for (Component component : components) {
            if (!ids.add(component.componentId()) || !applications.add(component.application().id())) {
                throw new IllegalArgumentException("managed graph component and application identities must be unique");
            }
        }
        if (!ids.contains(healthComponentId) || components.stream()
                .anyMatch(component -> !ids.containsAll(component.dependencies()))) {
            throw new IllegalArgumentException("managed graph health owner and dependencies must belong to the graph");
        }
        String serverId = components.getFirst().application().server().id();
        if (components.stream().anyMatch(component -> !component.application().server().id().equals(serverId))) {
            throw new IllegalArgumentException("managed application graph must belong to one server");
        }
    }

    /** One persisted component identity, health contract, and dependency set. / 一个持久组件身份、健康契约和依赖集合。 */
    public record Component(
            String componentId,
            ManagedApplication application,
            ManagedApplicationRuntimeConfiguration runtimeConfiguration,
            List<String> dependencies
    ) {
        /** Validates the component without accepting build or secret values. / 验证组件且不接受构建值或秘密值。 */
        public Component {
            componentId = identifier(componentId, "componentId");
            application = Objects.requireNonNull(application, "application");
            runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies").stream().sorted().toList());
            if (dependencies.stream().distinct().count() != dependencies.size()
                    || dependencies.contains(componentId)) {
                throw new IllegalArgumentException("managed component dependencies must be unique and non-self");
            }
        }
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }
}

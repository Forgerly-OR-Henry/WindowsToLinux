package gold.debug.windowstolinux.shared.deploy.delivery;

import java.util.*;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Mode-neutral managed delivery graph; no source-language classification is required. / 无需源码语言分类的模式中立受管交付图。
 * @param components explicit components and dependency edges / 显式组件及依赖边
 * @param applicationHealth explicit whole-application probe / 显式整应用探针
 */
public record ManagedDelivery(List<Component> components,
        gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate applicationHealth) {
    /** Validates identities, dependency closure and topological order. / 校验身份、依赖闭包及拓扑顺序。
     * @param components proposed components / 提议组件
     * @param applicationHealth explicit application probe / 显式应用探针
     */
    public ManagedDelivery {
        components = List.copyOf(components);
        if (components.isEmpty() || components.size() > 16)
            throw new IllegalArgumentException("delivery component count");
        var indexed = new LinkedHashMap<String, Component>();
        for (var component : components)
            if (indexed.put(component.id(), component) != null)
                throw new IllegalArgumentException("duplicate component");
        var ordered = new ArrayList<Component>();
        var remaining = new LinkedHashMap<>(indexed);
        while (!remaining.isEmpty()) {
            var ready = remaining.values().stream().filter(c -> c.dependencies().stream()
                    .allMatch(id -> ordered.stream().anyMatch(done -> done.id().equals(id)))).toList();
            if (ready.isEmpty())
                throw new IllegalArgumentException("dependency cycle or missing component");
            for (var component : ready) {
                ordered.add(component);
                remaining.remove(component.id());
            }
        }
        components = List.copyOf(ordered);
        applicationHealth = Objects.requireNonNull(applicationHealth);
        if (!indexed.containsKey(applicationHealth.componentId()))
            throw new IllegalArgumentException("application health component missing");
    }
    /** One managed component with explicit runtime and evidence. / 具有显式运行时及证据的受管组件。
     * @param id application-local identity / 应用内身份
     * @param dependencies required component identities / 所需组件身份
     * @param runtime explicit managed process or container contract / 显式受管进程或容器契约
     * @param resources reviewed resource bindings / 已审阅资源绑定
     * @param configuration nonsecret runtime configuration / 非秘密运行配置
     * @param evidence source paths supporting this component / 支持该组件的源码路径
     */
    public record Component(String id, List<String> dependencies, DeploymentRuntimeSpecification runtime,
            List<String> evidence,
            gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings resources,
            List<gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry> configuration) {
        /** Constructs a component with explicitly empty resources and configuration. / 构造资源与配置明确为空的组件。
         * @param id component identity / 组件身份
         * @param dependencies dependencies / 依赖
         * @param runtime runtime contract / 运行契约
         * @param evidence actual source evidence / 实际源码证据
         */
        public Component(String id, List<String> dependencies, DeploymentRuntimeSpecification runtime,
                List<String> evidence) {
            this(id, dependencies, runtime, evidence,
                    new gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings(List.of(),
                            Optional.of(List.of())),
                    List.of());
        }

        /** Validates component execution without source-type inference. / 不推断源码类型地校验组件执行。
         * @param id component identity / 组件身份
         * @param dependencies dependency identities / 依赖身份
         * @param runtime execution contract / 执行契约
         * @param resources reviewed resource bindings / 已审阅资源绑定
         * @param configuration nonsecret runtime configuration / 非秘密运行配置
         * @param evidence observed source paths / 已观察源码路径
         */
        public Component {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,30}"))
                throw new IllegalArgumentException("invalid component id");
            Objects.requireNonNull(resources);
            configuration = List.copyOf(configuration);
            if (configuration.size() > 64
                    || configuration.stream()
                            .map(gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry::key).distinct()
                            .count() != configuration.size()
                    || configuration.stream().anyMatch(e -> e
                            .scope() != gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope.RUNTIME))
                throw new IllegalArgumentException("runtime configuration scope or duplicate");
            dependencies = List.copyOf(dependencies);
            evidence = List.copyOf(evidence);
            Objects.requireNonNull(runtime);
            if (dependencies.size() > 15 || new HashSet<>(dependencies).size() != dependencies.size()
                    || dependencies.contains(id) || evidence.isEmpty() || evidence.size() > 32
                    || evidence.stream().anyMatch(v -> v.isBlank() || v.length() > 500))
                throw new IllegalArgumentException("invalid component dependencies or evidence");
            if (!runtime.workload().reviewed() || runtime.workload().command().entrypoint().isBlank() || runtime
                    .identityPolicy() == gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.LEGACY_UNSPECIFIED)
                throw new IllegalArgumentException("managed delivery requires explicit command, workload and identity");
        }
    }
}

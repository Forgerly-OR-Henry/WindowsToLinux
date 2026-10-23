package gold.debug.windowstolinux.mod.deployment.service.core.model.application;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;

/** Complete reviewed application topology without secrets or platform implementation types. / 完整审阅的应用拓扑，不含秘密或平台实现类型。
 * @param applicationId stable application identity / 稳定应用身份
 * @param healthComponentId owner of the whole-application probe / 整应用探测所属组件
 * @param healthCheck independently reviewed application probe / 独立审阅的应用探测
 * @param components complete reviewed component graph / 完整审阅组件图 */
public record DeploymentApplicationGraph(String applicationId, String healthComponentId,
                                         DeploymentHealthCheck healthCheck, List<Part> components) {
    /** Validates the original bounded, single-server acyclic graph. / 校验原有界、单服务器无环图。
     * @param applicationId stable application identity / 稳定应用身份
     * @param healthComponentId owner of the whole-application probe / 整应用探测所属组件
     * @param healthCheck independently reviewed application probe / 独立审阅的应用探测
     * @param components complete reviewed component graph / 完整审阅组件图 */
    public DeploymentApplicationGraph {
        applicationId = identifier(applicationId);
        healthComponentId = identifier(healthComponentId);
        Objects.requireNonNull(healthCheck, "healthCheck");
        components = List.copyOf(components).stream().sorted(Comparator.comparing(Part::componentId)).toList();
        if (components.isEmpty() || components.size() > 64)
            throw new IllegalArgumentException("Application graph requires 1..64 components");
        Set<String> ids = new HashSet<>();
        Set<String> units = new HashSet<>();
        var server = components.getFirst().unit().server();
        for (var component : components) {
            if (!ids.add(component.componentId()) || !units.add(component.unit().id()))
                throw new IllegalArgumentException("Part and unit identities must be unique");
            if (!server.equals(component.unit().server()))
                throw new IllegalArgumentException("Application graph must belong to one server");
        }
        if (!ids.contains(healthComponentId) || components.stream().anyMatch(c -> !ids.containsAll(c.dependencies())))
            throw new IllegalArgumentException("Health owner and dependencies must belong to the graph");
        Set<String> resolved = new HashSet<>();
        while (resolved.size() < components.size()) {
            int before = resolved.size();
            for (var component : components)
                if (resolved.containsAll(component.dependencies()))
                    resolved.add(component.componentId());
            if (before == resolved.size())
                throw new IllegalArgumentException("Application dependencies contain a cycle");
        }
    }

    /** Complete component facts with no legacy missing-field substitutes. / 完整组件事实，不以旧版缺失字段替代。
     * @param componentId component identity within its application / 应用内组件身份
     * @param unit exact owned deployment unit / 精确所属部署单元
     * @param runtime persisted activation contract / 持久激活契约
     * @param dependencies components that must precede this one / 必须先执行的组件
     * @param reviewedRuntime original reviewed runtime definition / 原审阅运行定义
     * @param dataPaths original reviewed paths / 原审阅路径
     * @param resources original reviewed resource bindings / 原审阅资源绑定 */
    public record Part(String componentId, DeploymentUnitIdentity unit, DeploymentUnitRuntimeConfig runtime,
                       List<String> dependencies, DeploymentRuntimeDefinition reviewedRuntime,
                       List<DeploymentStoragePath> dataPaths, DeploymentResourceBindings resources) {
        /** Validates exact health and path-binding coverage. / 校验精确健康及路径绑定覆盖。
         * @param componentId component identity within its application / 应用内组件身份
         * @param unit exact owned deployment unit / 精确所属部署单元
         * @param runtime persisted activation contract / 持久激活契约
         * @param dependencies components that must precede this one / 必须先执行的组件
         * @param reviewedRuntime original reviewed runtime definition / 原审阅运行定义
         * @param dataPaths original reviewed paths / 原审阅路径
         * @param resources original reviewed resource bindings / 原审阅资源绑定 */
        public Part {
            componentId = identifier(componentId);
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(reviewedRuntime, "reviewedRuntime");
            Objects.requireNonNull(resources, "resources");
            dependencies = List.copyOf(dependencies).stream().map(DeploymentApplicationGraph::identifier).sorted()
                .toList();
            dataPaths = List.copyOf(dataPaths).stream().sorted(Comparator.comparing(DeploymentStoragePath::path))
                .toList();
            if (dependencies.size() != new HashSet<>(dependencies).size() || dependencies.contains(componentId))
                throw new IllegalArgumentException("Dependencies must be unique and non-self");
            if (!reviewedRuntime.healthCheck().equals(runtime.healthCheck()))
                throw new IllegalArgumentException("Reviewed runtime health differs from its unit contract");
            if (dataPaths.stream().map(DeploymentStoragePath::path).distinct().count() != dataPaths.size())
                throw new IllegalArgumentException("Reviewed paths must be unique");
            var bound = resources.fileBindings().stream().map(DeploymentFileBinding::dataPath)
                .sorted(Comparator.comparing(DeploymentStoragePath::path)).toList();
            if (!bound.equals(dataPaths))
                throw new IllegalArgumentException("Resource bindings must exactly cover reviewed paths");
        }
    }

    /** Preserves the bounded business identifier without case conversion. / 保留有界业务标识，不转换大小写。
     * @param value original business identifier / 原业务标识
     * @return validated identifier / 已验证标识 */
    private static String identifier(String value) {
        value = Objects.requireNonNull(value).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid managed application identifier");
        return value;
    }
}

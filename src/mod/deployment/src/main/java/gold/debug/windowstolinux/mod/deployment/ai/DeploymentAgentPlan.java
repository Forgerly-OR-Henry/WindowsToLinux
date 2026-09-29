package gold.debug.windowstolinux.mod.deployment.ai;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Evidence-backed neutral application graph proposed by the autonomous model. / 自主模型提出、由证据支持的中立应用图。
 * @param applicationId exact managed application / 精确受管应用
 * @param healthComponentId declared whole-health owner / 已声明整图健康归属
 * @param health independently reviewed whole probe / 独立审阅整图探测
 * @param components complete bounded graph / 完整有界图 */
public record DeploymentAgentPlan(String applicationId, String healthComponentId, DeploymentHealthCheck health,
        List<Component> components) {
    /** One planned component before real managed identity is reserved. / 预留真实受管身份前的一项计划组件。
     * @param id component identity / 组件身份
     * @param dependencies required predecessors / 必需前驱
     * @param evidence source files actually read / 实际读过的源码文件
     * @param runtime reviewed neutral runtime / 已审阅中立运行定义
     * @param resources explicit managed resource bindings / 明确受管资源绑定
     * @param configuration typed nonsecret runtime values / 类型化不含秘密运行值 */
    public record Component(String id, List<String> dependencies, List<String> evidence,
            DeploymentRuntimeDefinition runtime, DeploymentResourceBindings resources,
            List<DeploymentConfigurationEntry> configuration) {
        /** Copies and validates one component. / 复制并校验一个组件。
         * @param id component identity / 组件身份
         * @param dependencies predecessors / 前驱
         * @param evidence cited files / 引用文件
         * @param runtime typed runtime / 类型化运行定义
         * @param resources reviewed resources / 经审阅资源
         * @param configuration typed values / 类型化值 */
        public Component {
            if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
                throw new IllegalArgumentException("Invalid agent component ID");
            dependencies = List.copyOf(dependencies);
            evidence = List.copyOf(evidence);
            Objects.requireNonNull(runtime);
            Objects.requireNonNull(resources);
            configuration = List.copyOf(configuration);
            if (configuration.size() > 64)
                throw new IllegalArgumentException("Agent configuration exceeds budget");
            if (evidence.isEmpty() || evidence.size() > 32 || dependencies.size() > 15
                    || dependencies.size() != new HashSet<>(dependencies).size())
                throw new IllegalArgumentException("Invalid agent component evidence or dependencies");
        }
    }

    /** Validates a bounded acyclic graph independently of standard analysis. / 独立于标准分析校验有界无环图。
     * @param applicationId managed application identity / 受管应用身份
     * @param healthComponentId whole-application health owner / 整应用健康归属
     * @param health independently reviewed probe / 独立审阅探测
     * @param components complete declared graph / 完整声明图 */
    public DeploymentAgentPlan {
        if (!Objects.requireNonNull(applicationId).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid agent application ID");
        Objects.requireNonNull(health);
        components = List.copyOf(components);
        if (components.isEmpty() || components.size() > 16)
            throw new IllegalArgumentException("Agent supports one to sixteen components");
        var ids = new HashSet<String>();
        for (var component : components)
            if (!ids.add(component.id()))
                throw new IllegalArgumentException("Duplicate agent component");
        if (!ids.contains(healthComponentId))
            throw new IllegalArgumentException("Unknown application health owner");
        var resolved = new HashSet<String>();
        while (resolved.size() < components.size()) {
            int before = resolved.size();
            for (var component : components) {
                if (!ids.containsAll(component.dependencies()) || component.dependencies().contains(component.id()))
                    throw new IllegalArgumentException("Invalid agent dependency");
                if (resolved.containsAll(component.dependencies()))
                    resolved.add(component.id());
            }
            if (before == resolved.size())
                throw new IllegalArgumentException("Agent component dependency cycle");
        }
    }
}

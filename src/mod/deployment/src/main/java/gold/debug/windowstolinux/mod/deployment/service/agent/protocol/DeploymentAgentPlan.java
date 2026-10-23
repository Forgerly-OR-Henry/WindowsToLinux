package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;

/** Evidence-backed neutral application graph proposed by the autonomous model. / 自主模型提出、由证据支持的中立应用图。
 * @param applicationId exact managed application / 精确受管应用
 * @param healthComponentId declared whole-health owner / 已声明整图健康归属
 * @param health independently reviewed whole probe / 独立审阅整图探测
 * @param components complete bounded graph / 完整有界图 */
public record DeploymentAgentPlan(String applicationId, String healthComponentId, DeploymentHealthCheck health,
                                  List<Part> components) {
    /** Requires source-backed database declarations before publication starts. / 发布开始前要求来源有据的数据库声明。
     * @throws IllegalArgumentException if any declaration remains unknown / 任一声明仍未知时 */
    public void requireKnownDatabases() {
        for (var component : components)
            if (component.databaseIds().isEmpty())
                throw new IllegalArgumentException("Database requirements remain unknown for component " + component.id()
                    + "; inspect current source and database_inspect as needed, then submit a source-backed plan. "
                    + "Use databases:[] only for verified no database, or every prepared database ID; null is unknown. "
                    + "Replanning invalidates sealed artifacts; seal current output again before deliver.");
    }

    /** One planned component before real managed identity is reserved. / 预留真实受管身份前的一项计划组件。
     * @param id component identity / 组件身份
     * @param sourceRoot relative component source root / 相对组件来源根
     * @param databaseIds verified database IDs, absent means unknown / 经核实数据库标识，缺省表示未知
     * @param secretReferences exact selected revisions / 精确已选修订
     * @param dependencies required predecessors / 必需前驱
     * @param evidence actual evidence IDs / 实际证据标识
     * @param runtime reviewed neutral runtime / 已审阅中立运行定义
     * @param resources explicit managed resource bindings / 明确受管资源绑定
     * @param configuration typed nonsecret runtime values / 类型化不含秘密运行值 */
    public record Part(String id, String sourceRoot, List<String> dependencies, List<String> evidence,
                       DeploymentRuntimeDefinition runtime, DeploymentResourceBindings resources,
                       List<DeploymentConfigEntry> configuration, Optional<List<String>> databaseIds,
                       List<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef> secretReferences,
                       Optional<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request> workspaceRequest,
                       Optional<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection> workspaceSelection) {
        public Part(String id, String sourceRoot, List<String> dependencies, List<String> evidence,
                    DeploymentRuntimeDefinition runtime, DeploymentResourceBindings resources, List<DeploymentConfigEntry> configuration,
                    Optional<List<String>> databaseIds, List<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef> secretReferences) {
            this(id, sourceRoot, dependencies, evidence, runtime, resources, configuration, databaseIds, secretReferences, Optional.empty(), Optional.empty());
        }

        public Part withWorkspace(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection selected) {
            return new Part(id, sourceRoot, dependencies, evidence, runtime, resources, configuration, databaseIds, secretReferences, workspaceRequest, Optional.of(selected));
        }

        /** Copies and validates one component. / 复制并校验一个组件。
         * @param id component identity / 组件身份
         * @param sourceRoot relative source root / 相对来源根
         * @param databaseIds verified targets or unknown / 核实目标或未知
         * @param secretReferences selected revisions / 已选修订
         * @param dependencies predecessors / 前驱
         * @param evidence actual evidence IDs / 实际证据标识
         * @param runtime typed runtime / 类型化运行定义
         * @param resources reviewed resources / 经审阅资源
         * @param configuration typed values / 类型化值 */
        public Part {
            if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
                throw new IllegalArgumentException("Invalid agent component ID");
            dependencies = List.copyOf(dependencies);
            sourceRoot = DeploymentAgentIdentification.sourceRoot(sourceRoot);
            evidence = List.copyOf(evidence);
            Objects.requireNonNull(runtime);
            Objects.requireNonNull(resources);
            configuration = List.copyOf(configuration);
            databaseIds = Objects.requireNonNull(databaseIds).map(List::copyOf);
            secretReferences = List.copyOf(secretReferences);
            Objects.requireNonNull(workspaceRequest);
            Objects.requireNonNull(workspaceSelection);
            if (workspaceSelection.isPresent() && !workspaceRequest.orElseThrow().equals(workspaceSelection.orElseThrow().request()))
                throw new IllegalArgumentException("Workspace selection differs from declared demand");
            if (databaseIds.map(v -> v.size() > 32 || new HashSet<>(v).size() != v.size()).orElse(false)
                || secretReferences.size() > 64 || new HashSet<>(secretReferences).size() != secretReferences.size())
                throw new IllegalArgumentException("Duplicate or excessive agent resource references");
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

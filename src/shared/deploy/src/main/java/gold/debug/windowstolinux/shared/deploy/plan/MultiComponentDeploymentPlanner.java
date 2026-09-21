package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.assessment.MultiComponentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Produces deterministic graph ordering only after mixed-project safety admission.
 *
 *  <p>仅在混合项目通过安全准入后生成确定性图顺序。
 */
public final class MultiComponentDeploymentPlanner {
    /**
     * Plans independent candidates and dependency-ordered runtime actions. / 计划独立候选与依赖有序运行时动作。
     *
     * @param assessment the typed static assessment / 类型化静态评估
     * @return constructed or resolved multi component deployment plan / 构造或解析得到的多组件部署计划
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentPlan plan(MultiComponentProjectAssessment assessment) {
        assessment = Objects.requireNonNull(assessment, "assessment");
        if (assessment.admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING || !assessment.issues().isEmpty()) {
            throw new IllegalArgumentException("only a fully admitted mixed project can enter component planning");
        }
        Map<String, DeploymentComponent> components = new LinkedHashMap<>();
        assessment.components().stream().filter(component -> component.runtime().isPresent())
                .sorted(Comparator.comparing(DeploymentComponent::componentId))
                .forEach(component -> components.put(component.componentId(), component));
        if (components.isEmpty()) throw new IllegalArgumentException("component plans require deployable components");
        Map<String, String> namespaces = new LinkedHashMap<>();
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        components.forEach((id, component) -> {
            namespaces.put(id, component.facts().applicationId());
            dependencies.put(id, component.dependencies().stream().sorted().toList());
        });
        return restore(assessment.applicationId(), namespaces, dependencies);
    }

    /**
     * Restores deterministic ordering from a previously validated durable managed graph. / 从先前已验证的持久受管图恢复确定性顺序。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param candidateNamespaces candidate namespaces / 候选命名空间集合
     * @param componentDependencies component dependencies / 组件依赖
     * @return constructed or resolved multi component deployment plan / 构造或解析得到的多组件部署计划
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentPlan restore(String applicationId, Map<String, String> candidateNamespaces,
                                                Map<String, List<String>> componentDependencies) {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        Map<String, String> namespaces = Map.copyOf(Objects.requireNonNull(candidateNamespaces,
                "candidateNamespaces"));
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        Objects.requireNonNull(componentDependencies, "componentDependencies").entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> dependencies.put(entry.getKey(), List.copyOf(entry.getValue())));
        if (namespaces.isEmpty() || !namespaces.keySet().equals(dependencies.keySet())) {
            throw new IllegalArgumentException("durable graph namespaces and dependencies must exactly match");
        }
        List<List<String>> waves = topologicalWaves(dependencies);
        List<String> start = waves.stream().flatMap(List::stream).toList();
        List<String> reverse = new ArrayList<>(start);
        Collections.reverse(reverse);
        return new MultiComponentDeploymentPlan(applicationId, waves, reverse, start, start, reverse,
                namespaces, dependencies);
    }

    /**
     * Groups dependency-free components into deterministic execution waves and rejects unresolved cycles.
     * <p>将无剩余依赖的组件分为确定的执行批次，并拒绝未解析的依赖环。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static List<List<String>> topologicalWaves(Map<String, List<String>> components) {
        Map<String, Integer> remainingDependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (Map.Entry<String, List<String>> component : components.entrySet()) {
            Set<String> dependencies = new TreeSet<>(component.getValue());
            if (dependencies.size() != component.getValue().size()
                    || !components.keySet().containsAll(dependencies) || dependencies.contains(component.getKey())) {
                throw new IllegalArgumentException("deployable component graph contains a non-deployable dependency");
            }
            remainingDependencies.put(component.getKey(), dependencies.size());
            dependencies.forEach(dependency -> dependents.computeIfAbsent(dependency, ignored -> new TreeSet<>())
                    .add(component.getKey()));
        }
        TreeSet<String> ready = new TreeSet<>();
        remainingDependencies.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<List<String>> waves = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            List<String> wave = List.copyOf(ready);
            waves.add(wave);
            ready.clear();
            for (String id : wave) {
                emitted.add(id);
                for (String dependent : dependents.getOrDefault(id, Set.of())) {
                    int count = remainingDependencies.compute(dependent, (ignored, value) -> value - 1);
                    if (count == 0) ready.add(dependent);
                }
            }
        }
        if (emitted.size() != components.size()) {
            throw new IllegalArgumentException("component dependency graph must be acyclic");
        }
        return List.copyOf(waves);
    }
}

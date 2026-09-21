package gold.debug.windowstolinux.shared.deploy.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic build and runtime ordering for one reviewed component graph.
 *
 *  <p>一个经审阅组件图的确定性构建与运行顺序。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param buildWaves build waves / 构建批次集合
 * @param stopOrder stop order / 停止顺序
 * @param startOrder start order / 启动顺序
 * @param healthOrder health order / 健康顺序
 * @param rollbackOrder rollback order / 回滚顺序
 * @param candidateNamespaces candidate namespaces / 候选命名空间集合
 * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
 */
public record MultiComponentDeploymentPlan(
        String applicationId,
        List<List<String>> buildWaves,
        List<String> stopOrder,
        List<String> startOrder,
        List<String> healthOrder,
        List<String> rollbackOrder,
        Map<String, String> candidateNamespaces,
        Map<String, List<String>> dependencies
) {
    /**
     * Validates an internally consistent component transaction plan. / 验证内部一致的组件事务计划。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param buildWaves build waves / 构建批次集合
     * @param stopOrder stop order / 停止顺序
     * @param startOrder start order / 启动顺序
     * @param healthOrder health order / 健康顺序
     * @param rollbackOrder rollback order / 回滚顺序
     * @param candidateNamespaces candidate namespaces / 候选命名空间集合
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentPlan {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        buildWaves = List.copyOf(Objects.requireNonNull(buildWaves, "buildWaves").stream()
                .map(List::copyOf).toList());
        stopOrder = List.copyOf(Objects.requireNonNull(stopOrder, "stopOrder"));
        startOrder = List.copyOf(Objects.requireNonNull(startOrder, "startOrder"));
        healthOrder = List.copyOf(Objects.requireNonNull(healthOrder, "healthOrder"));
        rollbackOrder = List.copyOf(Objects.requireNonNull(rollbackOrder, "rollbackOrder"));
        LinkedHashMap<String, String> namespaces = new LinkedHashMap<>();
        Objects.requireNonNull(candidateNamespaces, "candidateNamespaces").entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> namespaces.put(entry.getKey(), entry.getValue()));
        candidateNamespaces = java.util.Collections.unmodifiableMap(namespaces);
        LinkedHashMap<String, List<String>> normalizedDependencies = new LinkedHashMap<>();
        Objects.requireNonNull(dependencies, "dependencies").entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    List<String> normalized = entry.getValue().stream().sorted().toList();
                    if (normalized.stream().distinct().count() != normalized.size()) {
                        throw new IllegalArgumentException("component dependencies must be unique");
                    }
                    normalizedDependencies.put(entry.getKey(), normalized);
                });
        dependencies = java.util.Collections.unmodifiableMap(normalizedDependencies);
        List<String> flattenedBuild = buildWaves.stream().flatMap(List::stream).toList();
        if (!flattenedBuild.equals(startOrder) || !healthOrder.equals(startOrder)) {
            throw new IllegalArgumentException("build, start, and health ordering must cover the same topological sequence");
        }
        List<String> reversed = new java.util.ArrayList<>(startOrder);
        java.util.Collections.reverse(reversed);
        if (!stopOrder.equals(reversed) || !rollbackOrder.equals(reversed)) {
            throw new IllegalArgumentException("stop and rollback ordering must reverse the start sequence");
        }
        Set<String> namespaceKeys = namespaces.keySet();
        if (!namespaceKeys.equals(new java.util.LinkedHashSet<>(startOrder))
                || namespaces.values().stream().distinct().count() != namespaces.size()) {
            throw new IllegalArgumentException("every component requires an independent candidate namespace");
        }
        if (!normalizedDependencies.keySet().equals(namespaceKeys) || normalizedDependencies.entrySet().stream()
                .anyMatch(entry -> entry.getValue().contains(entry.getKey())
                        || !namespaceKeys.containsAll(entry.getValue()))) {
            throw new IllegalArgumentException("component dependencies must exactly cover the planned graph");
        }
        Map<String, Integer> positions = new java.util.HashMap<>();
        for (int index = 0; index < startOrder.size(); index++) positions.put(startOrder.get(index), index);
        if (normalizedDependencies.entrySet().stream().anyMatch(entry -> entry.getValue().stream()
                .anyMatch(dependency -> positions.get(dependency) >= positions.get(entry.getKey())))) {
            throw new IllegalArgumentException("component dependencies must precede their dependents");
        }
    }
}

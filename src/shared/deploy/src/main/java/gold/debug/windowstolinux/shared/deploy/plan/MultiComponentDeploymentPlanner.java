package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.analysis.MultiComponentProjectAssessment;
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
 * <p>仅在混合项目通过安全准入后生成确定性图顺序。
 */
public final class MultiComponentDeploymentPlanner {
    /** Plans independent candidates and dependency-ordered runtime actions. / 计划独立候选与依赖有序运行时动作。 */
    public MultiComponentDeploymentPlan plan(MultiComponentProjectAssessment assessment) {
        assessment = Objects.requireNonNull(assessment, "assessment");
        if (assessment.admission() != DeploymentAdmission.READY_FOR_PLANNING || !assessment.issues().isEmpty()) {
            throw new IllegalArgumentException("only a fully admitted mixed project can enter component planning");
        }
        Map<String, DeploymentComponent> components = new LinkedHashMap<>();
        assessment.components().stream().filter(component -> component.runtime().isPresent())
                .sorted(Comparator.comparing(DeploymentComponent::componentId))
                .forEach(component -> components.put(component.componentId(), component));
        if (components.isEmpty()) throw new IllegalArgumentException("component plans require deployable components");
        List<List<String>> waves = topologicalWaves(components);
        List<String> start = waves.stream().flatMap(List::stream).toList();
        List<String> reverse = new ArrayList<>(start);
        Collections.reverse(reverse);
        Map<String, String> namespaces = new LinkedHashMap<>();
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        start.forEach(id -> namespaces.put(id, components.get(id).facts().applicationId()));
        start.forEach(id -> dependencies.put(id, components.get(id).dependencies().stream().sorted().toList()));
        return new MultiComponentDeploymentPlan(assessment.applicationId(), waves, reverse, start, start, reverse,
                namespaces, dependencies);
    }

    private static List<List<String>> topologicalWaves(Map<String, DeploymentComponent> components) {
        Map<String, Integer> remainingDependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (DeploymentComponent component : components.values()) {
            Set<String> dependencies = new TreeSet<>(component.dependencies());
            if (!components.keySet().containsAll(dependencies)) {
                throw new IllegalArgumentException("deployable component graph contains a non-deployable dependency");
            }
            remainingDependencies.put(component.componentId(), dependencies.size());
            dependencies.forEach(dependency -> dependents.computeIfAbsent(dependency, ignored -> new TreeSet<>())
                    .add(component.componentId()));
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

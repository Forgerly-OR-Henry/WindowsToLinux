package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.model.assessment.ComponentIssue;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Validates cross-component ownership, dependency, port, artifact, and data-path invariants. / 验证跨组件所有权、依赖、端口、制品及数据路径约束。 */
final class ComponentGraphValidator {
    private ComponentGraphValidator() {
    }

    static void validate(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        Map<String, DeploymentComponent> indexed = new LinkedHashMap<>();
        components.forEach(component -> indexed.put(component.componentId(), component));
        for (DeploymentComponent component : components) {
            if (component.required() && component.runtime().isEmpty()) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "REQUIRED_COMPONENT_PREVIEW_ONLY",
                        List.of(component.componentId()), "analysis.component.requiredPreview"));
            }
            if (component.isolation().arbitraryShell()) unsafe(component, issues, "ARBITRARY_SHELL_REQUIRED",
                    "analysis.component.arbitraryShell");
            if (component.isolation().hostPrivileges()) unsafe(component, issues, "HOST_PRIVILEGE_REQUIRED",
                    "analysis.component.hostPrivilege");
            if (component.isolation().deviceAccess()) unsafe(component, issues, "DEVICE_ACCESS_REQUIRED",
                    "analysis.component.deviceAccess");
            if (component.isolation().uncontrolledNetwork()) unsafe(component, issues, "UNCONTROLLED_NETWORK_REQUIRED",
                    "analysis.component.uncontrolledNetwork");
            for (String dependency : component.dependencies()) {
                if (dependency.equals(component.componentId())) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SELF_DEPENDENCY",
                            List.of(component.componentId()), "analysis.component.selfDependency"));
                } else if (!indexed.containsKey(dependency)) {
                    issues.add(issue(ComponentIssue.SeverityLevel.REQUIRES_INPUT, "UNKNOWN_DEPENDENCY",
                            List.of(component.componentId()), "analysis.component.unknownDependency"));
                } else if (component.runtime().isPresent() && indexed.get(dependency).runtime().isEmpty()) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "DEPENDS_ON_PREVIEW_COMPONENT",
                            List.of(component.componentId(), dependency), "analysis.component.previewDependency"));
                }
            }
        }
        validatePairs(components, issues);
        validateData(components, issues);
        Set<String> cycle = cycleMembers(indexed);
        if (!cycle.isEmpty()) {
            issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_DEPENDENCY_CYCLE",
                    List.copyOf(cycle), "analysis.component.dependencyCycle"));
        }
    }

    private static void validatePairs(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        for (int left = 0; left < components.size(); left++) {
            for (int right = left + 1; right < components.size(); right++) {
                DeploymentComponent first = components.get(left);
                DeploymentComponent second = components.get(right);
                List<String> ids = List.of(first.componentId(), second.componentId());
                if (overlap(first.sourceRoot(), second.sourceRoot())) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_ROOT_OVERLAP", ids,
                            "analysis.component.rootOverlap"));
                }
                if (pathsOverlap(first.artifactPaths(), second.artifactPaths())) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_ARTIFACT_OVERLAP", ids,
                            "analysis.component.artifactOverlap"));
                }
                Set<String> ports = new TreeSet<>(transportPorts(first));
                ports.retainAll(transportPorts(second));
                if (!ports.isEmpty()) {
                    issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_PORT_CONFLICT", ids,
                            "analysis.component.portConflict"));
                }
            }
        }
    }

    private static Set<String> transportPorts(DeploymentComponent component) {
        var endpoints = component.runtime().map(runtime -> runtime.workload().endpoints()).orElse(List.of());
        if (!endpoints.isEmpty()) return endpoints.stream().map(endpoint -> endpoint.portKey())
                .collect(java.util.stream.Collectors.toSet());
        String protocol = component.runtime().filter(runtime -> runtime.healthCheck()
                instanceof gold.debug.windowstolinux.shared.model.health.HealthCheck.Udp).isPresent() ? "udp" : "tcp";
        return component.ports().stream().map(port -> protocol + ":" + port)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void validateData(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        Map<String, List<Map.Entry<String, ComponentDataPath>>> byPath = new HashMap<>();
        for (DeploymentComponent component : components) {
            for (ComponentDataPath data : component.dataPaths()) {
                byPath.computeIfAbsent(data.path(), ignored -> new ArrayList<>())
                        .add(Map.entry(component.componentId(), data));
            }
        }
        byPath.values().stream().filter(values -> values.size() > 1).forEach(values -> {
            List<String> ids = values.stream().map(Map.Entry::getKey).distinct().sorted().toList();
            if (values.stream().map(entry -> entry.getValue().schemaId()).distinct().count() > 1) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SHARED_DATA_SCHEMA_CONFLICT", ids,
                        "analysis.component.dataSchemaConflict"));
            }
            List<ComponentDataPath> writers = values.stream().map(Map.Entry::getValue)
                    .filter(value -> value.access() == ComponentDataPath.AccessMode.READ_WRITE).toList();
            if (writers.size() > 1) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SHARED_DATA_MULTIPLE_WRITERS", ids,
                        "analysis.component.dataWriters"));
            }
            if (writers.stream().anyMatch(value -> !value.reversible())) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "SHARED_DATA_IRREVERSIBLE_WRITE", ids,
                        "analysis.component.dataIrreversible"));
            }
        });
    }

    private static Set<String> cycleMembers(Map<String, DeploymentComponent> indexed) {
        Map<String, Integer> state = new HashMap<>();
        List<String> stack = new ArrayList<>();
        Set<String> cycle = new TreeSet<>();
        for (String id : indexed.keySet().stream().sorted().toList()) visit(id, indexed, state, stack, cycle);
        return Set.copyOf(cycle);
    }

    private static void visit(String id, Map<String, DeploymentComponent> indexed, Map<String, Integer> state,
                              List<String> stack, Set<String> cycle) {
        if (state.getOrDefault(id, 0) != 0) return;
        state.put(id, 1);
        stack.add(id);
        for (String dependency : indexed.get(id).dependencies().stream().sorted().toList()) {
            if (!indexed.containsKey(dependency) || dependency.equals(id)) continue;
            if (state.getOrDefault(dependency, 0) == 0) {
                visit(dependency, indexed, state, stack, cycle);
            } else if (state.get(dependency) == 1) {
                cycle.addAll(stack.subList(stack.indexOf(dependency), stack.size()));
            }
        }
        stack.removeLast();
        state.put(id, 2);
    }

    private static boolean overlap(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static boolean pathsOverlap(List<String> first, List<String> second) {
        for (String left : first) for (String right : second) {
            Path leftPath = Path.of(left).normalize();
            Path rightPath = Path.of(right).normalize();
            if (leftPath.startsWith(rightPath) || rightPath.startsWith(leftPath)) return true;
        }
        return false;
    }

    private static void unsafe(DeploymentComponent component, List<ComponentIssue> issues, String code, String key) {
        issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, code, List.of(component.componentId()), key));
    }

    private static ComponentIssue issue(ComponentIssue.SeverityLevel severity, String code, List<String> components,
                                        String key) {
        return new ComponentIssue(severity, code, components, LocalizedMessage.of(key,
                "components", String.join(", ", components.stream().sorted().toList())));
    }
}

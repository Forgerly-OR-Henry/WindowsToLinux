package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.analysis.ComponentIssue;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.analysis.MultiComponentProjectAssessment;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Analyzes explicit component roots and rejects cross-component contradictions before target mutation.
 *
 * <p>分析显式组件根，并在修改目标机前拒绝跨组件矛盾。
 */
public final class MixedProjectAnalyzer {
    private final DeploymentAnalysisCoordinator coordinator;

    /** Creates the bounded mixed-project analyzer. / 创建有界混合项目分析器。 */
    public MixedProjectAnalyzer() {
        this(new DeploymentAnalysisCoordinator());
    }

    MixedProjectAnalyzer(DeploymentAnalysisCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Analyzes all declared components without executing source content. / 在不执行源码内容的情况下分析全部声明组件。 */
    public MultiComponentProjectAssessment analyze(Path selectedApplicationRoot, String applicationId,
                                                   List<ComponentAnalysisRequest> requests) {
        Path root = requireApplicationRoot(selectedApplicationRoot);
        applicationId = requireIdentifier(applicationId, "applicationId");
        requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
        if (requests.isEmpty() || requests.size() > 64) {
            throw new IllegalArgumentException("mixed-project analysis requires between 1 and 64 components");
        }
        List<ComponentIssue> issues = new ArrayList<>();
        Map<String, ComponentAnalysisRequest> unique = new LinkedHashMap<>();
        for (ComponentAnalysisRequest request : requests.stream()
                .sorted(Comparator.comparing(ComponentAnalysisRequest::componentId)).toList()) {
            if (unique.putIfAbsent(request.componentId(), request) != null) {
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "DUPLICATE_COMPONENT_ID",
                        List.of(request.componentId()), "analysis.component.duplicateId"));
            }
        }
        List<DeploymentComponent> components = new ArrayList<>();
        for (ComponentAnalysisRequest request : unique.values()) {
            Path componentRoot = root.resolve(request.relativeSourceRoot()).normalize();
            if (!componentRoot.startsWith(root) || Files.isSymbolicLink(componentRoot)
                    || !Files.isDirectory(componentRoot, LinkOption.NOFOLLOW_LINKS)) {
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "COMPONENT_SOURCE_INVALID",
                        List.of(request.componentId()), "analysis.component.sourceInvalid"));
                continue;
            }
            var assessment = coordinator.analyze(componentRoot, request.projectType());
            if (assessment.admission() == DeploymentAdmission.REJECTED) {
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "COMPONENT_ANALYSIS_REJECTED",
                        List.of(request.componentId()), "analysis.component.analysisRejected"));
                continue;
            }
            var facts = namespacedFacts(assessment.facts().orElseThrow(), applicationId, request.componentId());
            try {
                components.add(new DeploymentComponent(request.componentId(), componentRoot, facts, request.runtime(),
                        request.artifactPaths(), request.ports(), request.configurationKeys(), request.secretIdentifiers(),
                        request.dataPaths(), request.dependencies(), request.required(), request.isolation()));
            } catch (IllegalArgumentException exception) {
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "COMPONENT_DECLARATION_INVALID",
                        List.of(request.componentId()), "analysis.component.declarationInvalid"));
                continue;
            }
            if (assessment.admission() == DeploymentAdmission.REQUIRES_INPUT) {
                issues.add(issue(ComponentIssue.Severity.REQUIRES_INPUT, "COMPONENT_REQUIRES_INPUT",
                        List.of(request.componentId()), "analysis.component.requiresInput"));
            }
        }
        validateComponents(components, issues);
        DeploymentAdmission admission = admission(components, issues);
        return new MultiComponentProjectAssessment(admission, applicationId, root, components, issues);
    }

    private static void validateComponents(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        Map<String, DeploymentComponent> indexed = new LinkedHashMap<>();
        components.forEach(component -> indexed.put(component.componentId(), component));
        for (DeploymentComponent component : components) {
            if (component.required() && component.runtime().isEmpty()) {
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "REQUIRED_COMPONENT_PREVIEW_ONLY",
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
                    issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "SELF_DEPENDENCY",
                            List.of(component.componentId()), "analysis.component.selfDependency"));
                } else if (!indexed.containsKey(dependency)) {
                    issues.add(issue(ComponentIssue.Severity.REQUIRES_INPUT, "UNKNOWN_DEPENDENCY",
                            List.of(component.componentId()), "analysis.component.unknownDependency"));
                } else if (component.runtime().isPresent() && indexed.get(dependency).runtime().isEmpty()) {
                    issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "DEPENDS_ON_PREVIEW_COMPONENT",
                            List.of(component.componentId(), dependency), "analysis.component.previewDependency"));
                }
            }
        }
        validatePairs(components, issues);
        validateData(components, issues);
        Set<String> cycle = cycleMembers(indexed);
        if (!cycle.isEmpty()) {
            issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "COMPONENT_DEPENDENCY_CYCLE",
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
                    issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "COMPONENT_ROOT_OVERLAP", ids,
                            "analysis.component.rootOverlap"));
                }
                if (pathsOverlap(first.artifactPaths(), second.artifactPaths())) {
                    issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "COMPONENT_ARTIFACT_OVERLAP", ids,
                            "analysis.component.artifactOverlap"));
                }
                Set<Integer> ports = new TreeSet<>(first.ports());
                ports.retainAll(second.ports());
                if (!ports.isEmpty()) {
                    issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "COMPONENT_PORT_CONFLICT", ids,
                            "analysis.component.portConflict"));
                }
            }
        }
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
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "SHARED_DATA_SCHEMA_CONFLICT", ids,
                        "analysis.component.dataSchemaConflict"));
            }
            List<ComponentDataPath> writers = values.stream().map(Map.Entry::getValue)
                    .filter(value -> value.access() == ComponentDataPath.Access.READ_WRITE).toList();
            if (writers.size() > 1) {
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "SHARED_DATA_MULTIPLE_WRITERS", ids,
                        "analysis.component.dataWriters"));
            }
            if (writers.stream().anyMatch(value -> !value.reversible())) {
                issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, "SHARED_DATA_IRREVERSIBLE_WRITE", ids,
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
        issues.add(issue(ComponentIssue.Severity.SAFETY_REJECTION, code,
                List.of(component.componentId()), key));
    }

    private static DeploymentAdmission admission(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        if (issues.stream().anyMatch(issue -> issue.severity() == ComponentIssue.Severity.SAFETY_REJECTION)) {
            return DeploymentAdmission.REJECTED;
        }
        if (issues.stream().anyMatch(issue -> issue.severity() == ComponentIssue.Severity.REQUIRES_INPUT)) {
            return DeploymentAdmission.REQUIRES_INPUT;
        }
        return components.stream().anyMatch(component -> component.runtime().isPresent())
                ? DeploymentAdmission.READY_FOR_PLANNING : DeploymentAdmission.RECOGNITION_PREVIEW;
    }

    private static ComponentIssue issue(ComponentIssue.Severity severity, String code, List<String> components,
                                        String key) {
        return new ComponentIssue(severity, code, components, LocalizedMessage.of(key,
                "components", String.join(", ", components.stream().sorted().toList())));
    }

    private static Path requireApplicationRoot(Path value) {
        Path root = Objects.requireNonNull(value, "selectedApplicationRoot").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("selected application root must be a regular directory");
        }
        return root;
    }

    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }

    private static DeploymentProjectFacts namespacedFacts(DeploymentProjectFacts facts, String applicationId,
                                                           String componentId) {
        String managedId = managedComponentId(applicationId, componentId);
        return new DeploymentProjectFacts(facts.sourceRoot(), managedId, facts.projectType(), facts.buildTool(),
                facts.support(), facts.languageFacts(), facts.evidence(), facts.conflicts(), facts.missingInformation());
    }

    private static String managedComponentId(String applicationId, String componentId) {
        String value = applicationId + "-" + componentId;
        if (value.length() <= 63) return value;
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 8);
            return value.substring(0, 54).replaceAll("-+$", "") + "-" + digest;
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}

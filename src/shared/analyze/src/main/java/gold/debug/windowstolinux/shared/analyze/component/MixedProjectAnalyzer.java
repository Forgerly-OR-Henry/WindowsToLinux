package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.assessment.ComponentIssue;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmission;
import gold.debug.windowstolinux.shared.model.assessment.MultiComponentProjectAssessment;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        ComponentGraphValidator.validate(components, issues);
        DeploymentAdmission admission = admission(components, issues);
        return new MultiComponentProjectAssessment(admission, applicationId, root, components, issues);
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

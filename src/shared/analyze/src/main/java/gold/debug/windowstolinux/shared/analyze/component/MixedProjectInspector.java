package gold.debug.windowstolinux.shared.analyze.component;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.assessment.ComponentIssue;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
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
public final class MixedProjectInspector {
    private final DeploymentAnalysisCoordinator coordinator;

    /** Creates the bounded mixed-project analyzer. / 创建有界混合项目分析器。 */
    public MixedProjectInspector() {
        this(new DeploymentAnalysisCoordinator());
    }

    MixedProjectInspector(DeploymentAnalysisCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Analyzes all declared components without executing source content. / 在不执行源码内容的情况下分析全部声明组件。 */
    public MultiComponentProjectAssessment analyze(Path selectedApplicationRoot, String applicationId,
                                                   List<ComponentAnalysisRequest> requests) {
        return analyze(selectedApplicationRoot, applicationId, requests, Map.of(), false);
    }

    /** Checks graph conflicts while retaining the pending database review as an input requirement. / 检查组件图冲突，同时将未完成数据库审阅保留为输入要求。 */
    public MultiComponentProjectAssessment analyzeAutomatic(Path root, String applicationId, List<ComponentAnalysisRequest> requests) {
        return analyze(root, applicationId, requests, Map.of(), true);
    }

    /** Rechecks every component using source-bound database evidence. / 使用与源码绑定的数据库证据重新检查每个组件。 */
    public MultiComponentProjectAssessment analyze(Path root, String applicationId, List<ComponentAnalysisRequest> requests,
            Map<String, gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> reviews) {
        return analyze(root, applicationId, requests, reviews, false);
    }

    private MultiComponentProjectAssessment analyze(Path selectedApplicationRoot, String applicationId,
            List<ComponentAnalysisRequest> requests, Map<String, gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> reviews,
            boolean deferDatabase) {
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
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "DUPLICATE_COMPONENT_ID",
                        List.of(request.componentId()), "analysis.component.duplicateId"));
            }
        }
        List<DeploymentComponent> components = new ArrayList<>();
        for (ComponentAnalysisRequest request : unique.values()) {
            Path componentRoot = root.resolve(request.relativeSourceRoot()).normalize();
            if (!componentRoot.startsWith(root) || Files.isSymbolicLink(componentRoot)
                    || !Files.isDirectory(componentRoot, LinkOption.NOFOLLOW_LINKS)) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_SOURCE_INVALID",
                        List.of(request.componentId()), "analysis.component.sourceInvalid"));
                continue;
            }
            var assessment = deferDatabase ? coordinator.analyzeForDatabaseReview(componentRoot, request.projectType())
                    : reviews.containsKey(request.componentId()) ? coordinator.analyze(componentRoot, request.projectType(), reviews.get(request.componentId()))
                    : coordinator.analyze(componentRoot, request.projectType());
            if (assessment.admission() == DeploymentAdmissionStatus.REJECTED) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_ANALYSIS_REJECTED",
                        List.of(request.componentId()), "analysis.component.analysisRejected"));
                continue;
            }
            var facts = namespacedFacts(assessment.facts().orElseThrow(), applicationId, request.componentId());
            try {
                components.add(new DeploymentComponent(request.componentId(), componentRoot, facts, request.runtime(),
                        request.artifactPaths(), request.ports(), request.configurationKeys(), request.secretIdentifiers(),
                        request.dataPaths(), request.dependencies(), request.required(), request.isolation()));
            } catch (IllegalArgumentException exception) {
                issues.add(issue(ComponentIssue.SeverityLevel.SAFETY_REJECTION, "COMPONENT_DECLARATION_INVALID",
                        List.of(request.componentId()), "analysis.component.declarationInvalid"));
                continue;
            }
            if (assessment.admission() == DeploymentAdmissionStatus.REQUIRES_INPUT) {
                issues.add(issue(ComponentIssue.SeverityLevel.REQUIRES_INPUT, "COMPONENT_REQUIRES_INPUT",
                        List.of(request.componentId()), "analysis.component.requiresInput"));
            }
        }
        ComponentGraphValidator.validate(components, issues);
        DeploymentAdmissionStatus admission = admission(components, issues);
        return new MultiComponentProjectAssessment(admission, applicationId, root, components, issues);
    }

    private static DeploymentAdmissionStatus admission(List<DeploymentComponent> components, List<ComponentIssue> issues) {
        if (issues.stream().anyMatch(issue -> issue.severity() == ComponentIssue.SeverityLevel.SAFETY_REJECTION)) {
            return DeploymentAdmissionStatus.REJECTED;
        }
        if (issues.stream().anyMatch(issue -> issue.severity() == ComponentIssue.SeverityLevel.REQUIRES_INPUT)) {
            return DeploymentAdmissionStatus.REQUIRES_INPUT;
        }
        return components.stream().anyMatch(component -> component.runtime().isPresent())
                ? DeploymentAdmissionStatus.READY_FOR_PLANNING : DeploymentAdmissionStatus.RECOGNITION_PREVIEW;
    }

    private static ComponentIssue issue(ComponentIssue.SeverityLevel severity, String code, List<String> components,
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
                facts.support(), facts.languageFacts(), facts.evidence(), facts.conflicts(), facts.missingInformation(), facts.toolchainRequirements(), facts.buildDirectory());
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

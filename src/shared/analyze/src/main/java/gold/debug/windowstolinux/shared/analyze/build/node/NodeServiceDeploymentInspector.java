package gold.debug.windowstolinux.shared.analyze.build.node;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedProjectMetadata;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.project.LanguageFact;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces Node service build facts and exact-version runtime suggestions.
 *
 * <p>生成 Node 服务构建事实与精确版本运行时建议。
 */
public final class NodeServiceDeploymentInspector implements DeploymentTypeInspector {
    private final NodeProjectInspector node = new NodeProjectInspector();

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.NODE_SERVICE;
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        Optional<NodeProjectInspection> inspected = node.inspect(root);
        if (inspected.isEmpty()) {
            rejections.add(new RejectionReason("NODE_PACKAGE_JSON_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.nodePackageMissing"), "deployment"));
            return null;
        }
        NodeProjectInspection project = inspected.orElseThrow();
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (project.lockFiles().isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeLockfile"));
        } else if (project.lockFiles().size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleNodeLockfiles"));
        }
        if (!project.hasBuildScript()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeBuildScript"));
        }
        if (!project.hasStartScript()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.nodeStartScript"));
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, project.applicationId(), projectType(),
                project.buildTool(), languageFacts, List.of(BoundedProjectMetadata.evidence(
                "analysis.deployment.evidence.nodePackage", "package.json", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(projectType());
        String nodeVersion = languageFacts.values().get(LanguageFact.NODE_MAJOR_VERSION);
        if (nodeVersion != null) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION, nodeVersion);
        }
        List<LocalizedMessage> required = new ArrayList<>();
        required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.health"));
        if (nodeVersion == null) {
            required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.nodeVersion"));
        }
        DeploymentRuntimeSuggestion suggestion = new DeploymentRuntimeSuggestion(projectType(), values, Optional.empty(), Map.of(),
                List.of(), languageFacts.evidence().stream().filter(evidence -> evidence.subject().key().equals(
                "analysis.deployment.runtime.evidence.nodeVersion")).toList(), required);
        return new DeploymentTypeInspection(facts, suggestion);
    }
}

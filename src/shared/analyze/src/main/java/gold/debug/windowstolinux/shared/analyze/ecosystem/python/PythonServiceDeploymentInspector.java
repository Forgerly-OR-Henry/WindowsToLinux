package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

import gold.debug.windowstolinux.shared.analyze.ecosystem.python.PythonBuildFacts;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.PythonBuildInspector;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Produces Python build facts and exact-version/module runtime suggestions.
 *
 * <p>生成 Python 构建事实与精确版本/模块运行时建议。
 */
public final class PythonServiceDeploymentInspector implements DeploymentTypeInspector {
    private final PythonBuildInspector python = new PythonBuildInspector();

    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PYTHON_SERVICE;
    }

    /** Inspects source facts for this deployment type. / 检查此部署类型的源码事实。 */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        Optional<PythonBuildFacts> inspected = python.inspect(root);
        if (inspected.isEmpty()) {
            rejections.add(new RejectionReason("PYTHON_PYPROJECT_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.pythonPyprojectMissing"), "deployment"));
            return null;
        }
        PythonBuildFacts project = inspected.orElseThrow();
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (project.lockFiles().isEmpty() && project.buildTool() != gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.PYTHON_STDLIB) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.pythonLockfile"));
        } else if (project.lockFiles().size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multiplePythonLockfiles"));
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, project.applicationId(), projectType(),
                project.buildTool(), languageFacts, List.of(evidence(
                "analysis.deployment.evidence.pythonProject", "pyproject.toml", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values = DeploymentRuntimeAssessment.valuesFor(projectType());
        String version = languageFacts.values().get(LanguageFactKind.PYTHON_VERSION);
        String entrypoint = languageFacts.values().get(LanguageFactKind.PYTHON_ENTRYPOINT);
        if (version != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_VERSION, version);
        }
        if (entrypoint != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.PYTHON_ENTRYPOINT, entrypoint);
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (version == null) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.pythonVersion"));
        }
        if (entrypoint == null) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.pythonEntrypoint"));
        }
        Set<String> runtimeKeys = Set.of("analysis.deployment.runtime.evidence.pythonVersion",
                "analysis.deployment.runtime.evidence.pythonEntrypoint");
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), values, Optional.empty(), Map.of(),
                List.of(), languageFacts.evidence().stream().filter(item -> runtimeKeys.contains(item.subject().key())).toList(), required);
        return new DeploymentTypeAssessment(facts, suggestion);
    }
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel.HIGH);
    }
}

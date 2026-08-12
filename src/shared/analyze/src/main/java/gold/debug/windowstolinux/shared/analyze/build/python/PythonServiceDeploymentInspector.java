package gold.debug.windowstolinux.shared.analyze.build.python;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedProjectMetadata;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
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
import java.util.Set;

/**
 * Produces Python build facts and exact-version/module runtime suggestions.
 *
 * <p>生成 Python 构建事实与精确版本/模块运行时建议。
 */
public final class PythonServiceDeploymentInspector implements DeploymentTypeInspector {
    private final PythonProjectInspector python = new PythonProjectInspector();

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PYTHON_SERVICE;
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        Optional<PythonProjectInspection> inspected = python.inspect(root);
        if (inspected.isEmpty()) {
            rejections.add(new RejectionReason("PYTHON_PYPROJECT_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.pythonPyprojectMissing"), "deployment"));
            return null;
        }
        PythonProjectInspection project = inspected.orElseThrow();
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (project.lockFiles().isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.pythonLockfile"));
        } else if (project.lockFiles().size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multiplePythonLockfiles"));
        }
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, project.applicationId(), projectType(),
                DeploymentBuildTool.PYTHON_VENV, languageFacts, List.of(BoundedProjectMetadata.evidence(
                "analysis.deployment.evidence.pythonProject", "pyproject.toml", "analysis.deployment.evidence.detected")),
                conflicts, missing);
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(projectType());
        String version = languageFacts.values().get(LanguageFact.PYTHON_VERSION);
        String entrypoint = languageFacts.values().get(LanguageFact.PYTHON_ENTRYPOINT);
        if (version != null) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_VERSION, version);
        }
        if (entrypoint != null) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.PYTHON_ENTRYPOINT, entrypoint);
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(BoundedProjectMetadata.required("analysis.deployment.runtime.health")));
        if (version == null) {
            required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.pythonVersion"));
        }
        if (entrypoint == null) {
            required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.pythonEntrypoint"));
        }
        Set<String> runtimeKeys = Set.of("analysis.deployment.runtime.evidence.pythonVersion",
                "analysis.deployment.runtime.evidence.pythonEntrypoint");
        DeploymentRuntimeSuggestion suggestion = new DeploymentRuntimeSuggestion(projectType(), values, Optional.empty(), Map.of(),
                List.of(), languageFacts.evidence().stream().filter(item -> runtimeKeys.contains(item.subject().key())).toList(), required);
        return new DeploymentTypeInspection(facts, suggestion);
    }
}

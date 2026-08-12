package gold.debug.windowstolinux.shared.analyze.language.java;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedProjectMetadata;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.project.LanguageFact;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Produces Java JAR facts and manifest-backed runtime suggestions without loading the archive.
 *
 * <p>在不加载归档的情况下生成 Java JAR 事实与清单依据运行时建议。
 */
public final class JavaJarDeploymentInspector implements DeploymentTypeInspector {
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.JAVA_JAR;
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) {
        List<Path> jars = source.relativeFiles().stream().filter(path -> path.getNameCount() == 1)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
        List<LocalizedMessage> missing = new ArrayList<>();
        List<LocalizedMessage> conflicts = new ArrayList<>();
        if (jars.isEmpty()) {
            missing.add(LocalizedMessage.of("analysis.deployment.missing.javaJar"));
        } else if (jars.size() > 1) {
            conflicts.add(LocalizedMessage.of("analysis.deployment.conflict.multipleJavaJars"));
        }
        String evidenceSource = jars.isEmpty() ? "source root" : jars.getFirst().toString();
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, BoundedProjectMetadata.rootApplicationId(root),
                projectType(), DeploymentBuildTool.JAVA, languageFacts, List.of(BoundedProjectMetadata.evidence(
                "analysis.deployment.evidence.javaJar", evidenceSource, jars.isEmpty()
                        ? "analysis.deployment.evidence.notDetected" : "analysis.deployment.evidence.detected")), conflicts, missing);
        Map<DeploymentRuntimeSuggestion.RuntimeInput, String> values = DeploymentRuntimeSuggestion.valuesFor(projectType());
        List<AnalysisEvidence> runtimeEvidence = new ArrayList<>();
        if (jars.size() == 1) {
            values.put(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_JAR_PATH, jars.getFirst().toString().replace('\\', '/'));
            runtimeEvidence.add(BoundedProjectMetadata.evidence("analysis.deployment.runtime.evidence.javaJarPath",
                    jars.getFirst().toString(), "analysis.deployment.evidence.detected"));
        }
        put(values, DeploymentRuntimeSuggestion.RuntimeInput.JAVA_MAIN_CLASS, languageFacts, LanguageFact.JAVA_MAIN_CLASS);
        put(values, DeploymentRuntimeSuggestion.RuntimeInput.JAVA_VERSION, languageFacts, LanguageFact.JAVA_VERSION);
        Set<String> keys = Set.of("analysis.deployment.runtime.evidence.javaMainClass",
                "analysis.deployment.runtime.evidence.javaVersion");
        runtimeEvidence.addAll(languageFacts.evidence().stream().filter(item -> keys.contains(item.subject().key())).toList());
        List<LocalizedMessage> required = new ArrayList<>(List.of(BoundedProjectMetadata.required("analysis.deployment.runtime.health")));
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_MAIN_CLASS)) {
            required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.javaMainClass"));
        }
        if (!values.containsKey(DeploymentRuntimeSuggestion.RuntimeInput.JAVA_VERSION)) {
            required.add(BoundedProjectMetadata.required("analysis.deployment.runtime.javaVersion"));
        }
        DeploymentRuntimeSuggestion suggestion = new DeploymentRuntimeSuggestion(projectType(), values, Optional.empty(), Map.of(),
                List.of(), runtimeEvidence, required);
        return new DeploymentTypeInspection(facts, suggestion);
    }

    private static void put(Map<DeploymentRuntimeSuggestion.RuntimeInput, String> target,
                            DeploymentRuntimeSuggestion.RuntimeInput input, ProjectLanguageFacts facts,
                            LanguageFact fact) {
        String value = facts.values().get(fact);
        if (value != null) {
            target.put(input, value);
        }
    }
}

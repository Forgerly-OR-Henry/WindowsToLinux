package gold.debug.windowstolinux.shared.analyze.ecosystem.java.jar;

import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

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
    private final JavaJarManifestInspector manifestInspector = new JavaJarManifestInspector();

    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.JAVA_JAR;
    }

    /** Inspects source facts for this deployment type. / 检查此部署类型的源码事实。 */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
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
        if (jars.size() == 1) {
            languageFacts = ProjectLanguageFacts.merge(languageFacts, manifestInspector.inspect(root, jars.getFirst()));
        }
        String evidenceSource = jars.isEmpty() ? "source root" : jars.getFirst().toString();
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, ProjectIdentityResolver.rootApplicationId(root),
                projectType(), DeploymentBuildToolType.JAVA, languageFacts, List.of(evidence(
                "analysis.deployment.evidence.javaJar", evidenceSource, jars.isEmpty()
                        ? "analysis.deployment.evidence.notDetected" : "analysis.deployment.evidence.detected")), conflicts, missing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values = DeploymentRuntimeAssessment.valuesFor(projectType());
        List<AnalysisEvidence> runtimeEvidence = new ArrayList<>();
        if (jars.size() == 1) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_JAR_PATH, jars.getFirst().toString().replace('\\', '/'));
            runtimeEvidence.add(evidence("analysis.deployment.runtime.evidence.javaJarPath",
                    jars.getFirst().toString(), "analysis.deployment.evidence.detected"));
        }
        put(values, DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS, languageFacts, LanguageFactKind.JAVA_MAIN_CLASS);
        put(values, DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION, languageFacts, LanguageFactKind.JAVA_VERSION);
        Set<String> keys = Set.of("analysis.deployment.runtime.evidence.javaMainClass",
                "analysis.deployment.runtime.evidence.javaVersion");
        runtimeEvidence.addAll(languageFacts.evidence().stream().filter(item -> keys.contains(item.subject().key())).toList());
        List<LocalizedMessage> required = new ArrayList<>(List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (!values.containsKey(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_MAIN_CLASS)) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.javaMainClass"));
        }
        if (!values.containsKey(DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION)) {
            required.add(LocalizedMessage.of("analysis.deployment.runtime.javaVersion"));
        }
        DeploymentRuntimeAssessment suggestion = new DeploymentRuntimeAssessment(projectType(), values, Optional.empty(), Map.of(),
                List.of(), runtimeEvidence, required);
        return new DeploymentTypeAssessment(facts, suggestion);
    }

    private static void put(Map<DeploymentRuntimeAssessment.RuntimeInputType, String> target,
                            DeploymentRuntimeAssessment.RuntimeInputType input, ProjectLanguageFacts facts,
                            LanguageFactKind fact) {
        String value = facts.values().get(fact);
        if (value != null) {
            target.put(input, value);
        }
    }
    private static gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence(
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(subject), source,
                gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(conclusion),
                gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel.HIGH);
    }
}

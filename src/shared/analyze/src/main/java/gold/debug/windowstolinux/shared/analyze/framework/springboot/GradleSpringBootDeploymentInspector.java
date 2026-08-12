package gold.debug.windowstolinux.shared.analyze.framework.springboot;

import gold.debug.windowstolinux.shared.analyze.build.gradle.GradleProjectInspection;
import gold.debug.windowstolinux.shared.analyze.build.gradle.GradleProjectInspector;
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
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Combines Gradle entrypoint facts with Spring Boot plugin admission.
 *
 * <p>组合 Gradle 入口事实与 Spring Boot 插件准入。
 */
public final class GradleSpringBootDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern BOOT_PLUGIN = Pattern.compile("(?i)(org\\.springframework\\.boot|spring-boot)");
    private final GradleProjectInspector gradle = new GradleProjectInspector();

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.GRADLE_SPRING_BOOT;
    }

    @Override
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        Optional<GradleProjectInspection> inspected = gradle.inspect(root, rejections);
        if (inspected.isEmpty()) {
            return null;
        }
        GradleProjectInspection build = inspected.orElseThrow();
        if (!BOOT_PLUGIN.matcher(build.text()).find()) {
            rejections.add(new RejectionReason("GRADLE_SPRING_BOOT_PLUGIN_MISSING",
                    LocalizedMessage.of("analysis.deployment.rejection.gradleBootPluginMissing"), "deployment"));
            return null;
        }
        List<LocalizedMessage> missing = build.usableWrapper() ? List.of()
                : List.of(LocalizedMessage.of("analysis.deployment.missing.gradleWrapper"));
        List<AnalysisEvidence> evidence = List.of(BoundedProjectMetadata.evidence(
                "analysis.deployment.evidence.gradleBuild", build.script().getFileName().toString(),
                "analysis.deployment.evidence.detected"));
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, build.applicationId(),
                projectType(), DeploymentBuildTool.GRADLE_WRAPPER, languageFacts, evidence, List.of(), missing);
        DeploymentRuntimeSuggestion suggestion = new DeploymentRuntimeSuggestion(projectType(), Map.of(), Optional.empty(),
                Map.of(), List.of(), List.of(), List.of(BoundedProjectMetadata.required("analysis.deployment.runtime.health")));
        return new DeploymentTypeInspection(facts, suggestion);
    }
}

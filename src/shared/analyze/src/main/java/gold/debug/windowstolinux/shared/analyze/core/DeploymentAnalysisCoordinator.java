package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.ecosystem.ProjectLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.shared.analyze.registry.DeploymentTypeInspectorRegistry;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.analyze.workload.ContainerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.workload.StaticWebDeploymentInspector;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates bounded traversal, safety policy, type dispatch, and result aggregation only.
 *
 * <p>仅协调有界遍历、安全策略、类型分派与结果汇总。
 */
public final class DeploymentAnalysisCoordinator {
    private final BoundedSourceInspector sourceInspector;
    private final ProjectLanguageInspector languageInspector;
    private final DeploymentTypeInspectorRegistry inspectors;
    private final SourceMutationPolicy mutationPolicy;

    /** Creates a coordinator with exactly one inspector for every supported type. / 为每种支持类型各配置一个检查器。 */
    public DeploymentAnalysisCoordinator() {
        this(new BoundedSourceInspector(), new ProjectLanguageInspector(), DeploymentTypeInspectorRegistry.defaults(),
                new SourceMutationPolicy());
    }

    DeploymentAnalysisCoordinator(BoundedSourceInspector sourceInspector, ProjectLanguageInspector languageInspector,
                                  DeploymentTypeInspectorRegistry inspectors, SourceMutationPolicy mutationPolicy) {
        this.sourceInspector = Objects.requireNonNull(sourceInspector, "sourceInspector");
        this.languageInspector = Objects.requireNonNull(languageInspector, "languageInspector");
        this.inspectors = Objects.requireNonNull(inspectors, "inspectors");
        this.mutationPolicy = Objects.requireNonNull(mutationPolicy, "mutationPolicy");
    }

    /** Analyzes one explicitly selected type without executing source. / 在不执行源码的情况下分析一个显式选择的类型。 */
    public DeploymentProjectAssessment analyze(Path selectedSourceDirectory, DeploymentProjectType projectType) {
        List<RejectionReason> rejections = new ArrayList<>();
        Path root = normalizeRoot(selectedSourceDirectory, rejections);
        if (root == null) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        SourceInspection source = sourceInspector.inspect(root, rejections);
        mutationPolicy.validate(source, rejections);
        if (!rejections.isEmpty()) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        try {
            ProjectLanguageFacts languageFacts = languageInspector.inspect(root, source);
            DeploymentTypeInspection inspected = inspectors.require(Objects.requireNonNull(projectType, "projectType"))
                    .inspect(root, source, languageFacts, rejections);
            if (inspected == null || !rejections.isEmpty()) {
                return DeploymentProjectAssessment.rejected(rejections);
            }
            if (projectType == DeploymentProjectType.RECOGNITION_PREVIEW) {
                return DeploymentProjectAssessment.recognitionPreview(inspected.facts());
            }
            return inspected.facts().readyForPlanning()
                    ? DeploymentProjectAssessment.ready(inspected.facts(), inspected.runtimeSuggestion())
                    : DeploymentProjectAssessment.requiresInput(inspected.facts(), inspected.runtimeSuggestion());
        } catch (IOException exception) {
            return DeploymentProjectAssessment.rejected(List.of(rejection("DEPLOYMENT_SOURCE_READ_FAILED",
                    "analysis.deployment.rejection.sourceReadFailed")));
        }
    }

    private static Path normalizeRoot(Path source, List<RejectionReason> rejections) {
        if (source == null) {
            rejections.add(rejection("SOURCE_PATH_MISSING", "analysis.deployment.rejection.sourceMissing"));
            return null;
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            rejections.add(rejection("SOURCE_PATH_INVALID", "analysis.deployment.rejection.sourceInvalid"));
            return null;
        }
        return normalized;
    }

    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}

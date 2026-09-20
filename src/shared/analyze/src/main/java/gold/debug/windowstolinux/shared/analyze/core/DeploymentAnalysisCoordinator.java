package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.shared.analyze.extension.registry.DeploymentTypeInspectorRegistry;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.workload.ContainerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.workload.StaticWebDeploymentInspector;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

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
        return analyze(selectedSourceDirectory, projectType, java.util.Optional.empty(), false);
    }

    /** Collects deployment facts but preserves a mandatory DB review missing field for schema-changing source. / 收集部署事实，但对包含模式变更的源码保留强制数据库审阅字段。 */
    public DeploymentProjectAssessment analyzeForDatabaseReview(Path source, DeploymentProjectType type) {
        return analyze(source, type, java.util.Optional.empty(), true);
    }

    /** Applies a source-bound database review after the corresponding DB operation has been verified. / 对应数据库操作通过验证后，应用与源码绑定的数据库审阅。 */
    public DeploymentProjectAssessment analyze(Path source, DeploymentProjectType type,
            gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview review) {
        return analyze(source, type, java.util.Optional.of(review), false);
    }

    private DeploymentProjectAssessment analyze(Path selectedSourceDirectory, DeploymentProjectType projectType,
            java.util.Optional<gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> review, boolean deferDatabase) {
        List<RejectionReason> rejections = new ArrayList<>();
        Path root = normalizeRoot(selectedSourceDirectory, rejections);
        if (root == null) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        SourceInspectionFacts source = sourceInspector.inspect(root, rejections);
        boolean pendingDatabase = deferDatabase && mutationPolicy.requiresReview(source);
        if (!pendingDatabase) mutationPolicy.validate(source, rejections, review);
        if (!rejections.isEmpty()) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        try {
            var bundle = gold.debug.windowstolinux.shared.analyze.source.ApplicationBundleInspector.declaration(root);
            Path mainRoot = gold.debug.windowstolinux.shared.analyze.source.ApplicationBundleInspector.mainRoot(root, bundle);
            var declaredType = gold.debug.windowstolinux.shared.analyze.source.ApplicationBundleInspector.type(bundle);
            if (declaredType.isPresent() && declaredType.orElseThrow() != projectType) throw new IOException("application build type differs from selection");
            SourceInspectionFacts mainSource = mainRoot.equals(root) ? source : sourceInspector.inspect(mainRoot, rejections);
            var companionTools = new ArrayList<gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement>();
            for (Path companion : gold.debug.windowstolinux.shared.analyze.source.ApplicationBundleInspector.companions(root, bundle)) {
                var companionSource = sourceInspector.inspect(companion, rejections);
                var companionFacts = inspectors.require(DeploymentProjectType.CMAKE_SERVICE).inspect(companion, companionSource,
                        languageInspector.inspect(companion, companionSource), rejections);
                if (companionFacts == null || !companionFacts.facts().readyForPlanning()) throw new IOException("companion build declaration is incomplete");
                companionTools.addAll(new gold.debug.windowstolinux.shared.analyze.toolchain.ToolchainDeclarationInspector().inspect(companion));
            }
            ProjectLanguageFacts languageFacts = languageInspector.inspect(mainRoot, mainSource);
            DeploymentTypeAssessment inspected = inspectors.require(Objects.requireNonNull(projectType, "projectType"))
                    .inspect(mainRoot, mainSource, languageFacts, rejections);
            if (inspected == null || !rejections.isEmpty()) {
                return DeploymentProjectAssessment.rejected(rejections);
            }
            if (projectType == DeploymentProjectType.RECOGNITION_PREVIEW) {
                return DeploymentProjectAssessment.recognitionPreview(inspected.facts());
            }
            companionTools.addAll(new gold.debug.windowstolinux.shared.analyze.toolchain.ToolchainDeclarationInspector().inspect(mainRoot));
            inspected = new DeploymentTypeAssessment(inspected.facts().withToolchains(
                    companionTools).inBundle(root, root.relativize(mainRoot).toString().replace('\\', '/'),
                    mainRoot.equals(root) ? inspected.facts().applicationId()
                            : gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver.rootApplicationId(root)),
                    inspected.runtimeSuggestion());
            inspected = enrichToolchainSuggestion(inspected);
            if (pendingDatabase) {
                var facts = inspected.facts();
                var missing = new ArrayList<>(facts.missingInformation());
                missing.add(LocalizedMessage.of("analysis.db.reviewRequired"));
                return DeploymentProjectAssessment.requiresInput(new gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts(
                        facts.sourceRoot(), facts.applicationId(), facts.projectType(), facts.buildTool(), facts.support(), facts.languageFacts(),
                        facts.evidence(), facts.conflicts(), missing, facts.toolchainRequirements(), facts.buildDirectory()), inspected.runtimeSuggestion());
            }
            return inspected.facts().readyForPlanning()
                    ? DeploymentProjectAssessment.ready(inspected.facts(), inspected.runtimeSuggestion())
                    : DeploymentProjectAssessment.requiresInput(inspected.facts(), inspected.runtimeSuggestion());
        } catch (IOException | IllegalArgumentException exception) {
            return DeploymentProjectAssessment.rejected(List.of(rejection("DEPLOYMENT_SOURCE_READ_FAILED",
                    "analysis.deployment.rejection.sourceReadFailed")));
        }
    }

    private static DeploymentTypeAssessment enrichToolchainSuggestion(DeploymentTypeAssessment inspected) {
        var facts = inspected.facts();
        if (facts.projectType() != DeploymentProjectType.SPRING_BOOT
                && facts.projectType() != DeploymentProjectType.KOTLIN_SERVICE) return inspected;
        var java = facts.toolchainRequirements().stream()
                .filter(r -> r.ecosystem() == gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.JAVA)
                .flatMap(r -> r.version().stream()).map(gold.debug.windowstolinux.shared.model.toolchain.ToolchainVersion::branch)
                .distinct().toList();
        if (java.size() != 1) return inspected;
        var previous = inspected.runtimeSuggestion();
        var values = new java.util.EnumMap<gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment.RuntimeInputType, String>(
                gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment.RuntimeInputType.class);
        values.putAll(previous.values());
        values.put(facts.projectType() == DeploymentProjectType.SPRING_BOOT
                ? gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION
                : gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment.RuntimeInputType.KOTLIN_JVM_TARGET, java.getFirst());
        return new DeploymentTypeAssessment(facts, new gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment(
                previous.projectType(), values, previous.suggestedHealthPort(), previous.suggestedContainerPorts(),
                previous.suggestedManagedVolumes(), previous.evidence(), previous.requiredUserInput()));
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

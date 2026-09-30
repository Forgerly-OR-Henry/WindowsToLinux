package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.task.CancellationToken;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.message.DeploymentLocalizedMessage;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.contract.policy.SourceMutationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.extension.registry.DeploymentTypeInspectorRegistry;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.workload.ContainerDeploymentInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.workload.StaticWebDeploymentInspector;

/**
 * Coordinates bounded traversal, safety policy, type dispatch, and result aggregation only.
 *
 *  <p>仅协调有界遍历、安全策略、类型分派与结果汇总。
 */
public final class DeploymentAnalysisCoordinator {
    /**
     * Bound bounded source inspector collaborator for source inspector.
     * <p>处理源码检查器的有界源码检查器协作对象。
     */
    private final BoundedSourceInspector sourceInspector;

    /**
     * Bound project language inspector collaborator for language inspector.
     * <p>处理语言检查器的项目语言检查器协作对象。
     */
    private final ProjectLanguageInspector languageInspector;

    /**
     * Inspectors.
     * <p>检查器集合。
     */
    private final DeploymentTypeInspectorRegistry inspectors;

    /**
     * Bound source mutation policy collaborator for mutation policy.
     * <p>处理变更策略的源码变更策略协作对象。
     */
    private final SourceMutationPolicy mutationPolicy;

    /**
     * Creates a coordinator with exactly one inspector for every supported type. / 为每种支持类型各配置一个检查器。
     */
    public DeploymentAnalysisCoordinator() {
        this(new BoundedSourceInspector(), new ProjectLanguageInspector(), DeploymentTypeInspectorRegistry.defaults(),
                new SourceMutationPolicy());
    }

    /**
     * Validates and binds the inputs required by deployment analysis coordinator.
     * <p>校验并绑定部署分析协调器所需输入。
     *
     * @param sourceInspector source inspector / 源码检查器
     * @param languageInspector language inspector / 语言检查器
     * @param inspectors inspectors / 检查器集合
     * @param mutationPolicy mutation policy / 变更策略
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    DeploymentAnalysisCoordinator(BoundedSourceInspector sourceInspector, ProjectLanguageInspector languageInspector,
            DeploymentTypeInspectorRegistry inspectors, SourceMutationPolicy mutationPolicy) {
        this.sourceInspector = Objects.requireNonNull(sourceInspector, "sourceInspector");
        this.languageInspector = Objects.requireNonNull(languageInspector, "languageInspector");
        this.inspectors = Objects.requireNonNull(inspectors, "inspectors");
        this.mutationPolicy = Objects.requireNonNull(mutationPolicy, "mutationPolicy");
    }

    /**
     * Runs bounded source and language inspectors, selects the matching deployment strategy and returns facts with explicit rejection evidence.
     * <p>运行有界源码及语言检查器、选择匹配部署策略，并返回事实及显式拒绝证据。
     *
     * @param selectedSourceDirectory selected source directory / 已选源码目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved deployment project assessment / 构造或解析得到的部署项目评估
     */
    public DeploymentProjectAssessment analyze(Path selectedSourceDirectory, DeploymentProjectKind projectType) {
        return analyze(selectedSourceDirectory, projectType, java.util.Optional.empty(), false, () -> false);
    }

    /**
     * Collects deployment facts but preserves a mandatory DB review missing field for schema-changing source. / 收集部署事实，但对包含模式变更的源码保留强制数据库审阅字段。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @return constructed or resolved deployment project assessment / 构造或解析得到的部署项目评估
     */
    public DeploymentProjectAssessment analyzeForDatabaseReview(Path source, DeploymentProjectKind type) {
        return analyze(source, type, java.util.Optional.empty(), true, () -> false);
    }

    /**
     * Runs bounded source and language inspectors, selects the matching deployment strategy and returns facts with explicit rejection evidence.
     * <p>运行有界源码及语言检查器、选择匹配部署策略，并返回事实及显式拒绝证据。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param review review / 审阅
     * @return constructed or resolved deployment project assessment / 构造或解析得到的部署项目评估
     */
    public DeploymentProjectAssessment analyze(Path source, DeploymentProjectKind type,
            gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseSchemaReview review) {
        return analyze(source, type, java.util.Optional.of(review), false, () -> false);
    }

    /**
     * Runs bounded source and language inspectors, selects the matching deployment strategy and returns facts with explicit rejection evidence.
     * <p>运行有界源码及语言检查器、选择匹配部署策略，并返回事实及显式拒绝证据。
     *
     * @param selectedSourceDirectory selected source directory / 已选源码目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param review review / 审阅
     * @param deferDatabase defer database / 延后数据库
     * @param cancellation original cooperative token / 原协作取消令牌
     * @return constructed or resolved deployment project assessment / 构造或解析得到的部署项目评估
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     * @throws CancellationException if original cancellation is requested / 原任务请求取消时
     */
    public DeploymentProjectAssessment analyze(Path selectedSourceDirectory, DeploymentProjectKind projectType,
            java.util.Optional<gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseSchemaReview> review,
            boolean deferDatabase, CancellationToken cancellation) {
        return analyze(selectedSourceDirectory, projectType, review, deferDatabase, cancellation,
                selectedSourceDirectory == null
                        ? "application"
                        : gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ProjectIdentityResolver
                                .rootName(selectedSourceDirectory));
    }

    /** Analyzes physical source with a separately reviewed logical root identity. / 使用另行审阅的逻辑根身份分析物理源码。
     * @param selectedSourceDirectory frozen physical source / 冻结物理源码
     * @param projectType reviewed project type / 经审阅项目类型
     * @param review optional exact database review / 可选精确数据库审阅
     * @param deferDatabase retain pending database review / 保留数据库待审
     * @param cancellation original task token / 原任务取消令牌
     * @param logicalRootName original reviewed project name / 原审阅项目名称
     * @return complete typed assessment / 完整类型化评估 */
    public DeploymentProjectAssessment analyze(Path selectedSourceDirectory, DeploymentProjectKind projectType,
            java.util.Optional<gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseSchemaReview> review,
            boolean deferDatabase, CancellationToken cancellation, String logicalRootName) {
        if (logicalRootName == null || logicalRootName.isBlank())
            throw new IllegalArgumentException("logicalRootName is required");
        requireActive(cancellation);
        List<RejectionReason> rejections = new ArrayList<>();
        Path root = normalizeRoot(selectedSourceDirectory, rejections);
        if (root == null) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        SourceInspectionFacts source = sourceInspector.inspect(root, rejections, cancellation)
                .withReviewedRootName(logicalRootName);
        boolean pendingDatabase = deferDatabase && mutationPolicy.requiresReview(source);
        if (!pendingDatabase)
            mutationPolicy.validate(source, rejections, review);
        if (!rejections.isEmpty()) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        try {
            var bundle = gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ApplicationBundleInspector
                    .declaration(root);
            Path mainRoot = gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ApplicationBundleInspector
                    .mainRoot(root, bundle);
            var declaredType = gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ApplicationBundleInspector
                    .type(bundle);
            if (declaredType.isPresent() && declaredType.orElseThrow() != projectType)
                throw new IOException("application build type differs from selection");
            SourceInspectionFacts mainSource = mainRoot.equals(root)
                    ? source
                    : sourceInspector.inspect(mainRoot, rejections, cancellation).withReviewedRootName(
                            gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ProjectIdentityResolver
                                    .rootName(mainRoot));
            var companionTools = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement>();
            for (Path companion : gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ApplicationBundleInspector
                    .companions(root, bundle)) {
                requireActive(cancellation);
                var companionSource = sourceInspector.inspect(companion, rejections, cancellation).withReviewedRootName(
                        gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ProjectIdentityResolver
                                .rootName(companion));
                var companionFacts = inspectors.require(DeploymentProjectKind.CMAKE_SERVICE).inspect(companion,
                        companionSource, languageInspector.inspect(companion, companionSource), rejections);
                if (companionFacts == null || !companionFacts.facts().readyForPlanning())
                    throw new IOException("companion build declaration is incomplete");
                companionTools.addAll(
                        new gold.debug.windowstolinux.mod.deployment.service.standard.analyze.toolchain.ToolchainDeclarationInspector()
                                .inspect(companion));
            }
            requireActive(cancellation);
            ProjectLanguageFacts languageFacts = languageInspector.inspect(mainRoot, mainSource);
            DeploymentTypeAssessment inspected = inspectors.require(Objects.requireNonNull(projectType, "projectType"))
                    .inspect(mainRoot, mainSource, languageFacts, rejections);
            if (inspected == null || !rejections.isEmpty()) {
                return DeploymentProjectAssessment.rejected(rejections);
            }
            if (projectType == DeploymentProjectKind.RECOGNITION_PREVIEW) {
                return DeploymentProjectAssessment.recognitionPreview(inspected.facts());
            }
            companionTools.addAll(
                    new gold.debug.windowstolinux.mod.deployment.service.standard.analyze.toolchain.ToolchainDeclarationInspector()
                            .inspect(mainRoot));
            inspected = new DeploymentTypeAssessment(inspected.facts().withToolchains(companionTools)
                    .inBundle(root, root.relativize(mainRoot).toString().replace('\\', '/'), mainRoot.equals(root)
                            ? inspected.facts().applicationId()
                            : gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ProjectIdentityResolver
                                    .rootApplicationId(logicalRootName)),
                    inspected.runtimeSuggestion());
            inspected = enrichToolchainSuggestion(inspected);
            if (pendingDatabase) {
                var facts = inspected.facts();
                var missing = new ArrayList<>(facts.missingInformation());
                missing.add(DeploymentLocalizedMessage.of("analysis.db.reviewRequired"));
                return DeploymentProjectAssessment.requiresInput(
                        new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(
                                facts.sourceRoot(), facts.applicationId(), facts.projectType(), facts.buildTool(),
                                facts.support(), facts.languageFacts(), facts.evidence(), facts.conflicts(), missing,
                                facts.toolchainRequirements(), facts.buildDirectory()),
                        inspected.runtimeSuggestion());
            }
            requireActive(cancellation);
            return inspected.facts().readyForPlanning()
                    ? DeploymentProjectAssessment.ready(inspected.facts(), inspected.runtimeSuggestion())
                    : DeploymentProjectAssessment.requiresInput(inspected.facts(), inspected.runtimeSuggestion());
        } catch (IOException | IllegalArgumentException exception) {
            return DeploymentProjectAssessment.rejected(List
                    .of(rejection("DEPLOYMENT_SOURCE_READ_FAILED", "analysis.deployment.rejection.sourceReadFailed")));
        }
    }

    /** Checks the original cooperative cancellation at an analysis stage boundary. / 在分析阶段边界检查原协作取消。
     * @param cancellation original task token / 原任务令牌
     * @throws CancellationException if cancellation is requested / 请求取消时 */
    private static void requireActive(CancellationToken cancellation) {
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Original analysis was cancelled");
    }

    /**
     * Builds deployment type assessment from the supplied enrich toolchain suggestion inputs.
     * <p>根据所提供补充工具链Suggestion输入构建部署类型评估。
     *
     * @param inspected inspected / 已检查
     * @return deployment type assessment from the supplied enrich toolchain suggestion inputs / 根据所提供补充工具链Suggestion输入构建部署类型评估
     */
    private static DeploymentTypeAssessment enrichToolchainSuggestion(DeploymentTypeAssessment inspected) {
        var facts = inspected.facts();
        if (facts.projectType() != DeploymentProjectKind.SPRING_BOOT
                && facts.projectType() != DeploymentProjectKind.KOTLIN_SERVICE)
            return inspected;
        var java = facts.toolchainRequirements().stream().filter(r -> r
                .ecosystem() == gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentToolchainType.JAVA)
                .flatMap(r -> r.version().stream())
                .map(gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentToolchainVersion::branch)
                .distinct().toList();
        if (java.size() != 1)
            return inspected;
        var previous = inspected.runtimeSuggestion();
        var values = new java.util.EnumMap<gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment.RuntimeInputType, String>(
                gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment.RuntimeInputType.class);
        values.putAll(previous.values());
        values.put(facts.projectType() == DeploymentProjectKind.SPRING_BOOT
                ? gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment.RuntimeInputType.JAVA_VERSION
                : gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment.RuntimeInputType.KOTLIN_JVM_TARGET,
                java.getFirst());
        return new DeploymentTypeAssessment(facts,
                new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment(
                        previous.projectType(), values, previous.suggestedHealthPort(),
                        previous.suggestedContainerPorts(), previous.suggestedManagedVolumes(), previous.evidence(),
                        previous.requiredUserInput()));
    }

    /**
     * Normalizes root directory defining the filesystem boundary.
     * <p>规范化定义文件系统边界的根目录。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved path; null when no matching value is available / 构造或解析得到的路径；没有匹配值时为 null
     */
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

    /**
     * Builds the admission rejection associated with the supplied reason.
     * <p>构建与所提供原因关联的准入拒绝。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the admission rejection associated with the supplied reason / 与所提供原因关联的准入拒绝
     */
    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, DeploymentLocalizedMessage.of(key), "deployment");
    }
}

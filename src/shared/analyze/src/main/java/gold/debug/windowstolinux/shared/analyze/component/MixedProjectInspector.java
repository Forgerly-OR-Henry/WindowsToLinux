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
 *  <p>分析显式组件根，并在修改目标机前拒绝跨组件矛盾。
 */
public final class MixedProjectInspector {
    /**
     * Bound deployment analysis coordinator collaborator for coordinator.
     * <p>处理协调器的部署分析协调器协作对象。
     */
    private final DeploymentAnalysisCoordinator coordinator;

    /**
     * Creates the bounded mixed-project analyzer. / 创建有界混合项目分析器。
     */
    public MixedProjectInspector() {
        this(new DeploymentAnalysisCoordinator());
    }

    /**
     * Validates and binds the inputs required by mixed project inspector.
     * <p>校验并绑定Mixed项目检查器所需输入。
     *
     * @param coordinator coordinator / 协调器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    MixedProjectInspector(DeploymentAnalysisCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * Combines deterministic component discovery and per-component assessments into one dependency graph and admission result.
     * <p>将确定性组件发现及各组件评估组合为依赖图及准入结果。
     *
     * @param selectedApplicationRoot selected application root / 已选应用根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param requests requests / 请求集合
     * @return constructed or resolved multi component project assessment / 构造或解析得到的多组件项目评估
     */
    public MultiComponentProjectAssessment analyze(Path selectedApplicationRoot, String applicationId,
                                                   List<ComponentAnalysisRequest> requests) {
        return analyze(selectedApplicationRoot, applicationId, requests, Map.of(), false);
    }

    /**
     * Checks graph conflicts while retaining the pending database review as an input requirement. / 检查组件图冲突，同时将未完成数据库审阅保留为输入要求。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param requests requests / 请求集合
     * @return constructed or resolved multi component project assessment / 构造或解析得到的多组件项目评估
     */
    public MultiComponentProjectAssessment analyzeAutomatic(Path root, String applicationId, List<ComponentAnalysisRequest> requests) {
        return analyze(root, applicationId, requests, Map.of(), true);
    }

    /**
     * Combines deterministic component discovery and per-component assessments into one dependency graph and admission result.
     * <p>将确定性组件发现及各组件评估组合为依赖图及准入结果。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param requests requests / 请求集合
     * @param reviews reviews / 审阅集合
     * @return constructed or resolved multi component project assessment / 构造或解析得到的多组件项目评估
     */
    public MultiComponentProjectAssessment analyze(Path root, String applicationId, List<ComponentAnalysisRequest> requests,
            Map<String, gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseSchemaReview> reviews) {
        return analyze(root, applicationId, requests, reviews, false);
    }

    /**
     * Combines deterministic component discovery and per-component assessments into one dependency graph and admission result.
     * <p>将确定性组件发现及各组件评估组合为依赖图及准入结果。
     *
     * @param selectedApplicationRoot selected application root / 已选应用根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param requests requests / 请求集合
     * @param reviews reviews / 审阅集合
     * @param deferDatabase defer database / 延后数据库
     * @return constructed or resolved multi component project assessment / 构造或解析得到的多组件项目评估
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Derives deployment admission from component support and the highest-severity discovered issues.
     * <p>根据组件支持情况及最高严重程度的已发现问题推导部署准入。
     *
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param issues issues / 问题集合
     * @return constructed or resolved deployment admission status / 构造或解析得到的部署准入状态
     */
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

    /**
     * Builds component issue from the supplied issue inputs.
     * <p>根据所提供问题输入构建组件问题。
     *
     * @param severity severity level assigned to the failure definition / 分配给失败定义的严重级别
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return component issue from the supplied issue inputs / 根据所提供问题输入构建组件问题
     */
    private static ComponentIssue issue(ComponentIssue.SeverityLevel severity, String code, List<String> components,
                                        String key) {
        return new ComponentIssue(severity, code, components, LocalizedMessage.of(key,
                "components", String.join(", ", components.stream().sorted().toList())));
    }

    /**
     * Validates and returns application root and rejects inputs outside the declared constraints.
     * <p>校验并返回应用根目录并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static Path requireApplicationRoot(Path value) {
        Path root = Objects.requireNonNull(value, "selectedApplicationRoot").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("selected application root must be a regular directory");
        }
        return root;
    }

    /**
     * Validates and returns the stable secret identifier and rejects inputs outside the declared constraints.
     * <p>校验并返回稳定的秘密标识并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require identifier text / 要求标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a bounded managed identifier");
        }
        return value;
    }

    /**
     * Builds deployment project facts from the supplied namespaced facts inputs.
     * <p>根据所提供命名空间内事实输入构建部署项目事实。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param applicationId managed application identifier / 受管应用标识
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @return deployment project facts from the supplied namespaced facts inputs / 根据所提供命名空间内事实输入构建部署项目事实
     */
    private static DeploymentProjectFacts namespacedFacts(DeploymentProjectFacts facts, String applicationId,
                                                           String componentId) {
        String managedId = managedComponentId(applicationId, componentId);
        return new DeploymentProjectFacts(facts.sourceRoot(), managedId, facts.projectType(), facts.buildTool(),
                facts.support(), facts.languageFacts(), facts.evidence(), facts.conflicts(), facts.missingInformation(), facts.toolchainRequirements(), facts.buildDirectory());
    }

    /**
     * Checks managed component id syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查受管组件标识语法及边界。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @return managed component id text / 受管组件标识文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
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

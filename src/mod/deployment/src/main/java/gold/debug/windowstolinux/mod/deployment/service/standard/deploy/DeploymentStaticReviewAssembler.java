package gold.debug.windowstolinux.mod.deployment.service.standard.deploy;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.server.ServerService;

import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.MultiComponentProjectAssessment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer.SnapshotAssessment;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentSqlitePreparation;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentDatabaseReviewModeType;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticInputParser;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;

/** Completes deterministic reviewed inputs against the original task, frozen source and selected server. / 按原任务、冻结来源与选定服务器补全确定性审阅输入。 */
public final class DeploymentStaticReviewAssembler {
    /** Task-bound platform server lookup. / 绑定任务的平台服务器查询。 */
    private final ServerService servers;

    /** Existing deterministic planner. / 现有确定性计划器。 */
    private final DeploymentStandardPlanner planner;

    /** Existing source-backed runtime rules. / 现有源码依据运行规则。 */
    private final DeploymentStaticRuntimeResolver runtime = new DeploymentStaticRuntimeResolver();

    /** Binds the two capabilities required to form a real original-task plan. / 绑定生成真实原任务计划所需的两项能力。
     * @param servers task-bound server service / 绑定任务的服务器服务
     * @param planner deterministic planner / 确定性计划器 */
    public DeploymentStaticReviewAssembler(ServerService servers, DeploymentStandardPlanner planner) {
        this.servers = Objects.requireNonNull(servers);
        this.planner = Objects.requireNonNull(planner);
    }

    /** Plans one complete reviewed component without accepting a path as source authorization. / 不将路径视为来源授权，规划一个完整已审阅组件。
     * @param server exact selected server / 精确选定服务器
     * @param material original open material / 原开放素材
     * @param archive original derived archive / 原派生归档
     * @param analysis actual source assessment / 真实源码评估
     * @param answers explicit reviewed inputs / 显式审阅输入
     * @param task original publication task / 原发布任务
     * @return typed plan or known rejection / 类型化计划或已知拒绝 */
    public OperationResult<ReviewedDeploymentPlan> plan(ServerRef server, DeploymentSourceMaterial material,
                                                        DeploymentSourceArchive archive, SnapshotAssessment<DeploymentProjectAssessment> analysis,
                                                        DeploymentStaticReviewAnswers answers, TaskContext task) {
        return plan(server, material, archive, analysis, answers, Optional.empty(), task);
    }

    /** Plans with exact source-derived SQLite preparation in the same original task. / 在同一原任务中使用精确源码派生 SQLite 准备进行规划。
     * @param server selected server / 选定服务器
     * @param material original material / 原素材
     * @param archive original archive / 原归档
     * @param analysis source assessment / 来源评估
     * @param answers reviewed runtime / 经审阅运行输入
     * @param prepared source-derived SQLite facts / 源码派生 SQLite 事实
     * @param task original task / 原任务
     * @return typed plan or refusal / 类型化计划或拒绝 */
    public OperationResult<ReviewedDeploymentPlan> plan(ServerRef server, DeploymentSourceMaterial material,
                                                        DeploymentSourceArchive archive, SnapshotAssessment<DeploymentProjectAssessment> analysis,
                                                        DeploymentStaticReviewAnswers answers, DeploymentPreparedDatabase prepared, TaskContext task) {
        return plan(server, material, archive, analysis, answers, Optional.of(prepared), task);
    }

    /** Applies optional source-derived values after original identity checks. / 原身份检查后应用可选源码派生值。
     * @param server selected server / 选定服务器
     * @param material original material / 原素材
     * @param archive original archive / 原归档
     * @param analysis source assessment / 来源评估
     * @param answers reviewed runtime / 经审阅运行输入
     * @param prepared optional source-derived SQLite facts / 可选源码派生 SQLite 事实
     * @param task original task / 原任务
     * @return typed plan or refusal / 类型化计划或拒绝 */
    private OperationResult<ReviewedDeploymentPlan> plan(ServerRef server, DeploymentSourceMaterial material,
                                                         DeploymentSourceArchive archive, SnapshotAssessment<DeploymentProjectAssessment> analysis,
                                                         DeploymentStaticReviewAnswers answers, Optional<DeploymentPreparedDatabase> prepared, TaskContext task) {
        Objects.requireNonNull(server);
        Objects.requireNonNull(material);
        Objects.requireNonNull(archive);
        Objects.requireNonNull(analysis);
        Objects.requireNonNull(answers);
        Objects.requireNonNull(task);
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            return cancelled();
        try {
            var assessed = analysis.assessment();
            if (assessed.facts().isEmpty() || !assessed.facts().orElseThrow().readyForPlanning())
                return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
            var facts = assessed.facts().orElseThrow();
            if (!original(material, archive, analysis, facts))
                return task.cancelToken().requested() ? cancelled() : rejected("DEPLOYMENT_STATIC_SOURCE_UNAVAILABLE");
            var serverInfo = servers.get(server, task);
            if (!server.equals(serverInfo.ref()))
                return rejected("DEPLOYMENT_STATIC_SERVER_CHANGED");
            var request = request(server, facts, assessed.runtimeSuggestion().orElse(null), material, archive,
                serverInfo.host(), answers, prepared);
            return planner.plan(material, analysis, request, task);
        } catch (ApiException failure) {
            return capabilityFailure(failure);
        } catch (DeploymentConfigurationException failure) {
            return configurationFailure(failure);
        } catch (IllegalArgumentException failure) {
            return rejected("DEPLOYMENT_STATIC_REVIEW_INVALID");
        }
    }

    /** Plans every reviewed component under the same original source and task. / 在同一原来源与任务下规划全部已审阅组件。
     * @param server exact selected server / 精确选定服务器
     * @param material original open material / 原开放素材
     * @param archive original derived archive / 原派生归档
     * @param analysis complete mixed assessment / 完整混合评估
     * @param answers one reviewed answer set for each deployable component / 每个可部署组件的一份审阅答案
     * @param task original publication task / 原发布任务
     * @return original whole-graph plan or known rejection / 原整图计划或已知拒绝 */
    public OperationResult<DeploymentStandardPlanner.ComponentPlans> planComponents(ServerRef server,
                                                                                    DeploymentSourceMaterial material, DeploymentSourceArchive archive,
                                                                                    SnapshotAssessment<MultiComponentProjectAssessment> analysis,
                                                                                    Map<String, DeploymentStaticReviewAnswers> answers, TaskContext task) {
        var archives = new java.util.HashMap<String, DeploymentSourceArchive>();
        for (var component : analysis.assessment().components())
            archives.put(component.componentId(), archive);
        return planComponents(server, material, archives, analysis, answers, task);
    }

    /** Plans a whole graph with each component's exact archive subtree. / 使用各组件精确归档子树规划整图。
     * @param server original target / 原目标
     * @param material original material / 原素材
     * @param archives one original archive per component / 每组件一份原归档
     * @param analysis complete graph analysis / 完整整图分析
     * @param answers reviewed inputs / 经审阅输入
     * @param task original task / 原任务
     * @return complete graph plan or refusal / 完整整图计划或拒绝 */
    public OperationResult<DeploymentStandardPlanner.ComponentPlans> planComponents(ServerRef server,
                                                                                    DeploymentSourceMaterial material, Map<String, DeploymentSourceArchive> archives,
                                                                                    SnapshotAssessment<MultiComponentProjectAssessment> analysis,
                                                                                    Map<String, DeploymentStaticReviewAnswers> answers, TaskContext task) {
        return planComponents(server, material, archives, analysis, answers, Map.of(), task);
    }

    /** Plans source-derived databases per graph component without sharing their review. / 对各整图组件规划源码派生数据库，不混用各自批准。
     * @param server original target / 原目标
     * @param material original material / 原素材
     * @param archives component archives / 组件归档
     * @param analysis graph assessment / 整图评估
     * @param answers reviewed inputs / 经审阅输入
     * @param databases source-derived component databases / 源码派生组件数据库
     * @param task original task / 原任务
     * @return complete graph plan or refusal / 完整整图计划或拒绝 */
    public OperationResult<DeploymentStandardPlanner.ComponentPlans> planComponents(ServerRef server,
                                                                                    DeploymentSourceMaterial material, Map<String, DeploymentSourceArchive> archives,
                                                                                    SnapshotAssessment<MultiComponentProjectAssessment> analysis,
                                                                                    Map<String, DeploymentStaticReviewAnswers> answers,
                                                                                    Map<String, ? extends DeploymentPreparedDatabase> databases, TaskContext task) {
        Objects.requireNonNull(server);
        Objects.requireNonNull(material);
        archives = Map.copyOf(archives);
        Objects.requireNonNull(analysis);
        answers = Map.copyOf(answers);
        Objects.requireNonNull(task);
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            return cancelled();
        try {
            if (analysis.assessment()
                .admission() != gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus.READY_FOR_PLANNING)
                return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
            var components = analysis.assessment().components().stream().filter(value -> value.runtime().isPresent())
                .toList();
            if (!answers.keySet().equals(
                components.stream().map(value -> value.componentId()).collect(java.util.stream.Collectors.toSet())))
                return rejected("DEPLOYMENT_STATIC_COMPONENT_REVIEW_INCOMPLETE");
            var serverInfo = servers.get(server, task);
            if (!server.equals(serverInfo.ref()))
                return rejected("DEPLOYMENT_STATIC_SERVER_CHANGED");
            var requests = new java.util.TreeMap<String, ReviewedDeploymentRequest>();
            for (var component : components) {
                var archive = archives.get(component.componentId());
                if (archive == null)
                    return rejected("DEPLOYMENT_STATIC_COMPONENT_ARCHIVE_MISSING");
                if (!original(material, archive, analysis.snapshotId(), analysis.snapshotSha256(), component.facts()))
                    return task.cancelToken().requested()
                        ? cancelled()
                        : rejected("DEPLOYMENT_STATIC_SOURCE_UNAVAILABLE");
                var request = request(server, component.facts(), null, material, archive, serverInfo.host(),
                    answers.get(component.componentId()),
                    Optional.ofNullable(databases.get(component.componentId())));
                if (!request.runtime().equals(component.runtime().orElseThrow()))
                    return rejected("DEPLOYMENT_STATIC_COMPONENT_RUNTIME_CHANGED");
                requests.put(component.componentId(), request);
            }
            return planner.planComponents(material, analysis, requests, task);
        } catch (ApiException failure) {
            return capabilityFailure(failure);
        } catch (DeploymentConfigurationException failure) {
            return configurationFailure(failure);
        } catch (IllegalArgumentException failure) {
            return rejected("DEPLOYMENT_STATIC_REVIEW_INVALID");
        }
    }

    /** Checks original live handles before reading any claimed source-root path. / 读取任何声称的源码根路径前核对原开放句柄。
     * @param material original material / 原素材
     * @param archive derived archive / 派生归档
     * @param analysis source-bound assessment / 来源绑定评估
     * @param facts analyzed facts / 分析事实
     * @return whether every original identity remains live / 全部原身份是否仍存活 */
    private static boolean original(DeploymentSourceMaterial material, DeploymentSourceArchive archive,
                                    SnapshotAssessment<DeploymentProjectAssessment> analysis, DeploymentProjectFacts facts) {
        return original(material, archive, analysis.snapshotId(), analysis.snapshotSha256(), facts);
    }

    /** Shares the exact source check with a whole-graph assessment. / 与整图评估复用精确来源核对。
     * @param material original material / 原素材
     * @param archive original archive / 原归档
     * @param id analysis snapshot UUID / 分析快照UUID
     * @param sha analysis source SHA / 分析来源摘要
     * @param facts component facts / 组件事实
     * @return true only for the live original source / 仅原开放来源返回true */
    private static boolean original(DeploymentSourceMaterial material, DeploymentSourceArchive archive,
                                    java.util.UUID id, String sha, DeploymentProjectFacts facts) {
        try {
            var snapshot = material.snapshot();
            var root = snapshot.directory();
            archive.path();
            var expectedRoot = root.resolve(archive.relativeRoot()).normalize();
            return facts.sourceRoot().equals(expectedRoot)
                && facts.sourceRoot().toRealPath().equals(expectedRoot.toRealPath()) && snapshot.id().equals(id)
                && snapshot.sha256().equals(sha) && archive.originSnapshotId().equals(id)
                && archive.sourceManifestSha256().equals(sha);
        } catch (IOException | IllegalStateException closed) {
            return false;
        }
    }

    /** Assembles values only after original server lookup and complete static assessment. / 仅在原服务器查询及完整静态评估后组装输入。
     * @param server selected server / 选定服务器
     * @param facts analyzed facts / 分析事实
     * @param suggestion assessed runtime suggestion / 评估得到的运行建议
     * @param material original material / 原素材
     * @param archive original archive / 原归档
     * @param host actual server host / 实际服务器主机
     * @param answers actual reviewed answers / 真实审阅答案
     * @param prepared independently reviewed SQLite inputs / 独立审阅的 SQLite 输入
     * @return complete reviewed request / 完整已审阅请求 */
    private ReviewedDeploymentRequest request(ServerRef server, DeploymentProjectFacts facts,
                                              DeploymentRuntimeAssessment suggestion, DeploymentSourceMaterial material, DeploymentSourceArchive archive,
                                              String host, DeploymentStaticReviewAnswers answers, Optional<DeploymentPreparedDatabase> prepared) {
        Map<String, String> values = new LinkedHashMap<>(
            runtime.defaults(facts.projectType(), suggestion, facts.sourceRoot()));
        values.putAll(answers.values());
        if (!runtime.missing(facts.applicationId(), facts.projectType(), values).isEmpty())
            throw new IllegalArgumentException("Required runtime input remains unreviewed");
        var definition = runtime.runtime(facts.projectType(), values);
        var entries = new java.util.ArrayList<>(
            DeploymentConfigurationInput.parse(values.getOrDefault("configuration", "")));
        prepared.ifPresent(value -> entries.addAll(value.configuration()));
        var configuration = DeploymentConfigurationSnapshot.create(facts.applicationId(),
            answers.configurationRevision(), "1", answers.consent().confirmedAt(), entries);
        var secrets = new java.util.ArrayList<>(
            DeploymentConfigurationInput.secrets(values.getOrDefault("secrets", "")));
        prepared.ifPresent(value -> secrets.addAll(value.secrets()));
        var mode = DeploymentDatabaseReviewModeType.valueOf(values.getOrDefault("databaseMode", "UNREVIEWED"));
        var databases = prepared.filter(value -> !value.bindings().isEmpty()).map(value -> {
            if (mode != DeploymentDatabaseReviewModeType.NONE || !values.getOrDefault("databaseDetails", "").isBlank())
                throw new IllegalArgumentException("Source database and manual binding conflict");
            return Optional.of(value.bindings());
        }).orElseGet(
            () -> DeploymentStaticInputParser.databaseBindings(mode, values.getOrDefault("databaseDetails", "")));
        var resources = new DeploymentResourceBindings(answers.files(), databases);
        return new ReviewedDeploymentRequest(server, facts, material, archive, configuration, secrets, resources,
            definition, runtime.access(values, host), answers.limits(), answers.consent(),
            answers.containerDaemonRiskAccepted(), answers.experimentalAdapterRiskAccepted());
    }

    /** Preserves a platform cancellation or unknown rather than converting it into a known failure. / 保留平台取消或未知，不降级为已知失败。
     * @param <T> requested result type / 请求的结果类型
     * @param failure original capability failure / 原能力失败
     * @return original classified outcome / 原分类结果 */
    private static <T> OperationResult<T> capabilityFailure(ApiException failure) {
        var problem = failure.problem();
        var outcome = switch (problem.code()) {
            case CANCELLED -> Outcome.CANCELLED;
            case UNKNOWN -> Outcome.UNKNOWN;
            default -> Outcome.FAILED;
        };
        return new OperationResult<>(outcome, Optional.empty(), Optional.of(problem));
    }

    /** Preserves the existing public configuration diagnostic. / 保留现有公开配置诊断。
     * @param <T> requested result type / 请求的结果类型
     * @param failure structured configuration rejection / 结构化配置拒绝
     * @return original safe problem / 原有安全问题 */
    private static <T> OperationResult<T> configurationFailure(DeploymentConfigurationException failure) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(failure.problem()));
    }

    /** Returns one known invalid review result. / 返回已知无效审阅结果。
     * @param <T> requested result type / 请求的结果类型
     * @param event stable event code / 稳定事件码
     * @return known failure / 已知失败 */
    private static <T> OperationResult<T> rejected(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, event,
                "Reviewed static input is incomplete or invalid", Map.of(), Optional.empty())));
    }

    /** Preserves cooperative cancellation without claiming a plan exists. / 保留协作取消且不声称计划存在。
     * @param <T> requested result type / 请求的结果类型
     * @return cancelled input result / 已取消输入结果 */
    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
            "DEPLOYMENT_STATIC_REVIEW_CANCELLED", "Reviewed input was cancelled", Map.of(), Optional.empty())));
    }
}

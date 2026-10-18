package gold.debug.windowstolinux.mod.deployment.service.standard.shared;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentConfigStore;
import gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiver;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigInput;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.report.DeploymentProjectReport;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DatabaseSchemaReview;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeReport;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.part.PartIsolationSpec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrap;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrapActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.MixedProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.PartAnalysisRequest;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.ProjectPartFinder;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardModeSupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardRecoverySupport;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewAnswers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardReviewBuilder;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentDatabasePlanActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.database.DeploymentDatabaseStages;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentDatabaseReviewModeType;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewFieldException;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardInputParser;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyContext;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.single.DeploymentStandardSinglePublishRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardProjectSave;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSourceSetup;

/** Runs the shared deterministic source, review and publication pipeline for either standard mode. / 为两种标准模式执行共用确定性来源、审阅与发布流程。 */
public final class DeploymentStandardWorkflow {
    /** Original task-bound capabilities. / 原任务绑定能力。 */
    private final ModContext context;

    /** Existing formal publication transaction. / 既有正式发布事务。 */
    private final DeploymentRefFlow references;

    /** Deterministic source assessment. / 确定性来源评估。 */
    private final DeploymentStandardAnalyzer analyzer = new DeploymentStandardAnalyzer();

    /** Existing typed runtime questions. / 既有类型化运行问题。 */
    private final DeploymentStandardRuntimeResolver runtime = new DeploymentStandardRuntimeResolver();

    /** Exact managed ownership resolver. / 精确受管归属解析器。 */
    private final DeploymentStandardUnitResolver units;

    /** Existing original single publication. / 既有原单组件发布。 */
    private final DeploymentStandardSinglePublishRunner single;

    /** Existing original whole-graph publication. / 既有原整图发布。 */
    private final DeploymentStandardGraphPublishRunner multi;

    /** Original bounded assistance review and mode binding. / 原有界辅助审阅及模式绑定。 */
    private final DeploymentStandardModeSupport mode;

    /** Upper assembly route for captured original recovery. / 已捕获原恢复的上层装配路由。 */
    private final DeploymentStandardRecoverySupport recoverySupport;

    /** Assembled single-component publisher for this mode. / 当前模式已装配单组件发布器。 */
    private final java.util.function.BiFunction<ModContext, DeploymentRefFlow, DeploymentStandardSinglePublishRunner> singleFactory;

    /** Assembled whole-graph publisher for this mode. / 当前模式已装配整图发布器。 */
    private final java.util.function.BiFunction<ModContext, DeploymentRefFlow, DeploymentStandardGraphPublishRunner> graphFactory;

    private final gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Policy workspacePolicy =
        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().workspacePolicy();

    /** Binds the existing task, analyzer, planner and publisher without owning their resources. / 绑定既有任务、分析、规划与发布能力但不取得资源所有权。
     * @param context actual Mod activation / 实际Mod启用
     * @param references registered formal recovery / 已登记正式恢复
     * @param mode assembled standard mode adapter / 已装配标准模式适配
     * @param recoverySupport assembled original recovery route / 已装配原恢复路由
     * @param singleFactory assembled single-component publisher factory / 已装配单组件发布器工厂
     * @param graphFactory assembled graph publisher factory / 已装配整图发布器工厂 */
    public DeploymentStandardWorkflow(ModContext context, DeploymentRefFlow references, DeploymentStandardModeSupport mode, DeploymentStandardRecoverySupport recoverySupport,
                                      java.util.function.BiFunction<ModContext, DeploymentRefFlow, DeploymentStandardSinglePublishRunner> singleFactory,
                                      java.util.function.BiFunction<ModContext, DeploymentRefFlow, DeploymentStandardGraphPublishRunner> graphFactory) {
        this.context = Objects.requireNonNull(context);
        this.references = Objects.requireNonNull(references);
        this.units = new DeploymentStandardUnitResolver(context.db());
        this.singleFactory = Objects.requireNonNull(singleFactory);
        this.single = singleFactory.apply(context, references);
        this.graphFactory = Objects.requireNonNull(graphFactory);
        this.multi = graphFactory.apply(context, references);
        this.mode = Objects.requireNonNull(mode);
        this.recoverySupport = Objects.requireNonNull(recoverySupport);
    }

    /** Admits one publication task and performs all local review before formal effects. / 接纳一项发布任务，并在正式副作用前完成全部本机审阅。
     * @param server exact target / 精确目标
     * @param draft frozen choices / 冻结选择
     * @param git optional captured Git source / 可选已捕获 Git 来源
     * @param reviewer current owner view / 当前所属视图
     * @param approvalMode captured approval policy / 已捕获审批策略
     * @param rememberAuthorization captured exact-action reuse preference / 已捕获精确动作复用偏好
     * @return one original task / 一项原任务
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public TaskHandle<Void> submit(ServerId server, Draft draft, Optional<GitSource> git, Reviewer reviewer,
                                   String approvalMode, boolean rememberAuthorization) throws ApiException {
        boolean assisted = mode.assisted();
        Objects.requireNonNull(server);
        Objects.requireNonNull(draft);
        Objects.requireNonNull(git);
        Objects.requireNonNull(reviewer);
        DeploymentAuthorizationPolicy.requireAllowed(approvalMode, rememberAuthorization);
        if (git.isPresent() && draft.logicalRootName().isBlank())
            throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_GIT_NAME_REQUIRED",
                "Git source requires an explicit project name", Map.of(), Optional.empty()));
        if (git.isPresent() && draft.sourceOptions().localLocation().isPresent())
            throw new IllegalArgumentException("Publication captured two source routes");
        return references.submitPrepared(server, task -> {
            try {
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task,
                    new gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout(
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().paths()));
                new DeploymentPreferenceStore(context.db()).save(task,
                    new DeploymentPreferenceStore.Selection(
                        assisted ? "ASSISTED" : "STATIC", approvalMode, rememberAuthorization));
            } catch (SQLException unavailable) {
                return ioFailure("DEPLOYMENT_PREFERENCE_SAVE_FAILED");
            } catch (ApiException unavailable) {
                var code = unavailable.problem().code();
                return new OperationResult<>(
                    code == ProblemCode.UNKNOWN
                        ? TaskResult.UNKNOWN
                        : code == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                    Optional.empty(), Optional.of(unavailable.problem()));
            }
            try {
                var guarded = DeploymentPublishPolicyContext.begin(context, task, server, mode, approvalMode,
                    rememberAuthorization, draft + "\ngit=" + git.map(value -> value.request().toString()).orElse(""), recoverySupport);
                return guarded.prepare(original -> new DeploymentStandardWorkflow(guarded, references, mode, recoverySupport, singleFactory, graphFactory)
                    .prepare(server, draft, git, reviewer, original, assisted));
            } catch (DeploymentAiJson.CapabilityOutcomeException refused) {
                return new OperationResult<>(refused.outcome(), Optional.empty(), refused.problem());
            } catch (ApiException unavailable) {
                var code = unavailable.problem().code();
                return new OperationResult<>(
                    code == ProblemCode.UNKNOWN
                        ? TaskResult.UNKNOWN
                        : code == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                    Optional.empty(), Optional.of(unavailable.problem()));
            }
        });
    }

    /** Retains local evidence before original selection, then hands off only a complete reviewed publication. / 原选材前保留本机依据，且只交接完整审阅发布。
     * @param server original target / 原目标
     * @param draft immutable choices / 不可变选择
     * @param git optional reviewed Git source / 可选的已审阅 Git 来源
     * @param reviewer original review / 原审阅
     * @param task original task / 原任务
     * @return actual prepared publication / 实际已准备发布
     * @param assisted whether source advice and bounded form recovery were selected / 是否选择来源建议及有界表单恢复
     * @throws Exception if original cleanup or checkpoint cannot be confirmed / 原清理或检查点无法确认时 */
    private OperationResult<DeploymentRefFlow.PreparedPublish> prepare(ServerId server, Draft draft,
                                                                       Optional<GitSource> git, Reviewer reviewer, TaskContext task, boolean assisted) throws Exception {
        var marker = DeploymentStandardSetupActionLog.begin(context.tasks(), task, server);
        var capture = new DeploymentStandardSourceSetup.Capture();
        var archives = new ArrayList<DeploymentSourceArchive>();
        boolean transferred = false;
        boolean retainMarker = false;
        try {
            if (task.cancelToken().requested()) {
                marker.cancelled();
                return cancelled();
            }
            DeploymentDiagnostics.progress(context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "stage.freezeSource"));
            capture.prepare(context, draft, git, task);
            var material = capture.material();
            var pinned = capture.pinned();
            var localProject = capture.localProject();
            String logicalName = capture.logicalName();
            if (logicalName.isBlank())
                return rejected("DEPLOYMENT_STATIC_SOURCE_NAME_UNAVAILABLE");
            var discovered = new ProjectPartFinder().discover(material.snapshot().directory());
            var advice = mode.session(context, this::validateReview);
            advice.sourceCaptured(server, material, task);
            discovered = advice.compare(server, new DeploymentSourceBrowser(
                material.snapshot().directory(), material.snapshot().sha256()), discovered, task);
            DeploymentDiagnostics.progress(context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "stage.reviewGraph"));
            if (discovered.size() > 16)
                return rejected("DEPLOYMENT_STATIC_COMPONENT_LIMIT");
            var chosen = reviewer.select(server, material.snapshot().sha256(), discovered, task);
            if (chosen.outcome() == TaskResult.CANCELLED)
                marker.cancelled();
            if (chosen.outcome() != TaskResult.SUCCEEDED) {
                retainMarker = chosen.outcome() == TaskResult.UNKNOWN;
                return withoutValue(chosen);
            }
            var choices = chosen.value().orElseThrow();
            if (!validSelection(discovered, choices))
                return rejected("DEPLOYMENT_STATIC_SELECTION_INVALID");
            discovered = DeploymentComponentSelection.resolve(discovered, choices);
            OperationResult<DeploymentRefFlow.PreparedPublish> ready = discovered.size() == 1
                && choices.choices().get(discovered.getFirst().id()).inputs().isEmpty()
                ? prepareSingle(server, draft, logicalName, reviewer, task, material, discovered.getFirst(),
                choices, archives, pinned, advice, localProject)
                : prepareGraph(server, draft, logicalName, reviewer, task, material, discovered, choices, archives,
                pinned, advice, localProject);
            if (ready.outcome() == TaskResult.CANCELLED)
                marker.cancelled();
            if (ready.outcome() != TaskResult.SUCCEEDED) {
                retainMarker = ready.outcome() == TaskResult.UNKNOWN;
                return priorEffects(ready, task);
            }
            if (pinned != null)
                pinned.close();
            DeploymentDiagnostics.progress(context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "stage.closePreparation"));
            marker.close();
            transferred = true;
            return advice.publication(ready);
        } catch (DeploymentAiJson.CapabilityOutcomeException failure) {
            retainMarker = failure.outcome() == TaskResult.UNKNOWN;
            if (failure.outcome() == TaskResult.CANCELLED)
                marker.cancelled();
            return priorEffects(new OperationResult<>(failure.outcome(), Optional.empty(), failure.problem()), task);
        } catch (ApiException failure) {
            var code = failure.problem().code();
            retainMarker = code == ProblemCode.UNKNOWN;
            var outcome = code == ProblemCode.CANCELLED
                ? TaskResult.CANCELLED
                : code == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN : TaskResult.FAILED;
            if (outcome == TaskResult.CANCELLED)
                marker.cancelled();
            return priorEffects(new OperationResult<>(outcome, Optional.empty(), Optional.of(failure.problem())), task);
        } catch (CancellationException cancelled) {
            marker.cancelled();
            return priorEffects(cancelled(), task);
        } catch (SQLException readFailure) {
            return priorEffects(ioFailure("DEPLOYMENT_STATIC_METADATA_UNAVAILABLE"), task);
        } catch (IOException sourceFailure) {
            return priorEffects(ioFailure("DEPLOYMENT_STATIC_DISCOVERY_UNAVAILABLE"), task);
        } catch (SecurityException sourceRejected) {
            return priorEffects(rejected("DEPLOYMENT_STATIC_SOURCE_REJECTED"), task);
        } catch (IllegalArgumentException invalid) {
            return priorEffects(rejected("DEPLOYMENT_STATIC_REVIEW_INVALID"), task);
        } finally {
            if (!transferred) {
                retainMarker |= unresolvedRemotePreparation(task);
                DeploymentStandardSourceSetup.close(archives, capture.material(), capture.pinned(), marker, retainMarker);
            }
        }
    }

    /** Completes one discovered project on its original frozen subtree. / 在原冻结子树完成一个已发现项目。
     * @param server exact target / 精确目标
     * @param draft source limits / 来源限额
     * @param logicalName source-bound project name / 来源绑定项目名
     * @param reviewer owner view / 所属视图
     * @param task original task / 原任务
     * @param material live material / 开放素材
     * @param discovered discovered root / 已发现根
     * @param selection reviewed type / 经审阅类型
     * @param archives owned archives to release on failure / 失败时释放的所属归档
     * @param pinned optional original Git material / 可选原 Git 素材
     * @param advice optional original bounded assistance / 可选原有界辅助
     * @param localProject same frozen local project row if explicitly selected / 明确选择时的同一冻结本地项目行
     * @return prepared original publication / 已准备原发布
     * @throws Exception if original review or storage fails / 原审阅或存储失败时 */
    private OperationResult<DeploymentRefFlow.PreparedPublish> prepareSingle(ServerId server, Draft draft,
                                                                             String logicalName, Reviewer reviewer, TaskContext task, DeploymentSourceMaterial material,
                                                                             DiscoveredProjectPart discovered, Selection selection, List<DeploymentSourceArchive> archives,
                                                                             DeploymentGitSourceFlow.PublishSource pinned, DeploymentStandardModeSupport.Session advice,
                                                                             Optional<DeploymentProjectRecord> localProject) throws Exception {
        String id = discovered.id();
        var type = selection.choices().get(id).type();
        var initial = analyzer.analyzeComponent(material, discovered.relativeRoot(), logicalName, type,
            Optional.empty(), true, task);
        if (initial.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(initial);
        var assessed = initial.value().orElseThrow().assessment();
        if (assessed.admission() == DeploymentAdmissionStatus.REJECTED
            || assessed.admission() == DeploymentAdmissionStatus.RECOGNITION_PREVIEW)
            return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
        var facts = assessed.facts().orElseThrow();
        var database = prepareDatabase(server, id, facts.applicationId(), material, facts, reviewer, task);
        if (database.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(database);
        var databaseLocal = database.value().orElseThrow();
        var prompt = advice.prompt(server,
            prompt(id, facts, assessed.runtimeSuggestion().orElse(null), databaseLocal.hasBindings()),
            material, task);
        prompt = DeploymentStandardRuntimeDraft.apply(prompt, draft.sourceOptions().runtimeDraft());
        var reviewed = advice.review(server, material.snapshot().sha256(), List.of(prompt), reviewer, task);
        if (reviewed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(reviewed);
        var supplied = reviewed.value().orElseThrow();
        if (!supplied.keySet().equals(Set.of(id)))
            return rejected("DEPLOYMENT_STATIC_REVIEW_INCOMPLETE");
        var values = completed(prompt, supplied.get(id));
        validateReview(prompt, supplied.get(id));
        var stages = new DeploymentDatabaseStages(context);
        stages.validateRuntime(databaseLocal, supplied.get(id));
        var plan = stages.begin(server, material.snapshot().sha256(), List.of(databaseLocal), task);
        if (plan.isPresent()) {
            var helper = ensureHelper(server, task);
            if (helper.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(helper);
        }
        var prepared = stages.prepare(server, plan, databaseLocal, reviewer, task);
        if (prepared.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(prepared);
        var preparedDatabase = prepared.value().orElseThrow();
        if (plan.isPresent() && !stages.releaseKnown(task, plan.orElseThrow()))
            return unknownPreparation();
        var reanalyzed = analyzer.analyzeComponent(material, discovered.relativeRoot(), logicalName, type,
            preparedDatabase.schemaReview(), false, task);
        if (reanalyzed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(reanalyzed);
        if (schemaReviewMissing(reanalyzed.value().orElseThrow().assessment()))
            return schemaReviewRequired();
        if (reanalyzed.value().orElseThrow().assessment().admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING)
            return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
        DeploymentDiagnostics.progress(context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "stage.archiveComponent"));
        var archive = new DeploymentSourceArchiver(context.file()).prepare(material, relative(discovered),
            draft.archiveBytes(), task);
        archives.add(archive);
        supplied = selectWorkspaces(server, task, material, Map.of(id, archive), Map.of(id, facts), supplied, advice);
        var confirmed = reviewer.confirm(server, material.snapshot().sha256(), Map.of(id, archive.sha256()), supplied,
            Map.of(id, preparedDatabase), gitReview(pinned), task);
        if (confirmed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(confirmed);
        if (!confirmed.value().orElse(false))
            return cancelled();
        var answer = answers(task, server, facts.applicationId(), material, archive, supplied.get(id));
        var planned = reviewBuilder(task).plan(server, material, archive, reanalyzed.value().orElseThrow(), answer,
            preparedDatabase, task);
        if (planned.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(planned);
        var publication = planned.value().orElseThrow();
        var unit = units.resolve(task, facts.applicationId(), server, publication.request().layout());
        var credentials = units.credentials(task, publication.request().secretReferences());
        var helper = ensureHelper(server, task);
        if (helper.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(helper);
        DeploymentDiagnostics.progress(context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "stage.checkSourceRevision"));
        Optional<DeploymentProjectRecord> project = DeploymentStandardProjectSave.save(context, task, draft, pinned, localProject);
        return single.prepareWithinTask(task, unit, project, credentials,
            original -> new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(publication), Optional.empty()),
            !mode.assisted()
                ? Optional.empty()
                : Optional.of(advice.correction(prompt, supplied.get(id), reviewer)));
    }

    /** Completes a discovered graph only after each reviewed runtime has been reanalyzed. / 每份经审阅运行时重分析后才完成发现整图。
     * @param server exact target / 精确目标
     * @param draft source limits / 来源限额
     * @param logicalName source-bound project name / 来源绑定项目名
     * @param reviewer owner view / 所属视图
     * @param task original task / 原任务
     * @param material live material / 开放素材
     * @param discovered original roots / 原发现根
     * @param selection reviewed graph / 经审阅整图
     * @param archives owned archives / 所属归档
     * @param pinned optional original Git material / 可选原 Git 素材
     * @param advice optional original bounded assistance / 可选原有界辅助
     * @param localProject same frozen local project row if explicitly selected / 明确选择时的同一冻结本地项目行
     * @return prepared original publication / 已准备原发布
     * @throws Exception if review or storage fails / 审阅或存储失败时 */
    private OperationResult<DeploymentRefFlow.PreparedPublish> prepareGraph(ServerId server, Draft draft,
                                                                            String logicalName, Reviewer reviewer, TaskContext task, DeploymentSourceMaterial material,
                                                                            List<DiscoveredProjectPart> discovered, Selection selection, List<DeploymentSourceArchive> archives,
                                                                            DeploymentGitSourceFlow.PublishSource pinned, DeploymentStandardModeSupport.Session advice,
                                                                            Optional<DeploymentProjectRecord> localProject) throws Exception {
        var facts = new HashMap<String, DeploymentProjectFacts>();
        var databaseLocal = new HashMap<String, DeploymentDatabaseStages.Local>();
        var collected = graphPrompts(server, material, discovered, selection, reviewer, task, facts, databaseLocal,
            advice);
        if (collected.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(collected);
        var prompts = collected.value().orElseThrow().stream()
            .map(prompt -> DeploymentStandardRuntimeDraft.apply(prompt, draft.sourceOptions().runtimeDraft())).toList();
        var reviewed = advice.review(server, material.snapshot().sha256(), List.copyOf(prompts), reviewer,
            task);
        if (reviewed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(reviewed);
        var supplied = reviewed.value().orElseThrow();
        if (!supplied.keySet().equals(selection.choices().keySet()))
            return rejected("DEPLOYMENT_STATIC_COMPONENT_REVIEW_INCOMPLETE");
        for (var prompt : prompts)
            validateReview(prompt, supplied.get(prompt.componentId()));
        var stages = new DeploymentDatabaseStages(context);
        for (var prompt : prompts)
            stages.validateRuntime(databaseLocal.get(prompt.componentId()), supplied.get(prompt.componentId()));
        var requests = new ArrayList<PartAnalysisRequest>();
        for (int i = 0; i < discovered.size(); i++)
            requests.add(componentRequest(discovered.get(i), selection.choices().get(discovered.get(i).id()),
                facts.get(discovered.get(i).id()), prompts.get(i), supplied.get(discovered.get(i).id())));
        var preparedDatabases = graphDatabases(server, material, discovered, databaseLocal, reviewer, task);
        if (preparedDatabases.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(preparedDatabases);
        var databases = preparedDatabases.value().orElseThrow();
        var reviews = new HashMap<String, DatabaseSchemaReview>();
        databases.forEach((id, prepared) -> prepared.schemaReview().ifPresent(review -> reviews.put(id, review)));
        var analyzed = analyzer.analyzeComponents(material, logicalName, selection.applicationId(), requests, reviews,
            false, task);
        if (analyzed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(analyzed);
        var assessment = analyzed.value().orElseThrow();
        if (assessment.assessment().issues().stream().anyMatch(issue -> schemaReviewCode(issue.code())))
            return schemaReviewRequired();
        if (assessment.assessment().admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING)
            return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
        DeploymentDiagnostics.progress(context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "stage.archiveComponents"));
        var frozen = DeploymentStandardSourceSetup.archiveParts(material, context.file(), discovered,
            draft.archiveBytes(), archives, task);
        var byComponent = frozen.archives();
        supplied = selectWorkspaces(server, task, material, byComponent, facts, supplied, advice);
        var confirmed = reviewer.confirm(server, material.snapshot().sha256(), frozen.digests(), supplied, databases, gitReview(pinned), task);
        if (confirmed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(confirmed);
        if (!confirmed.value().orElse(false))
            return cancelled();
        var answers = new HashMap<String, DeploymentStandardReviewAnswers>();
        for (var component : assessment.assessment().components())
            answers.put(component.componentId(), answers(task, server, component.facts().applicationId(), material,
                byComponent.get(component.componentId()), supplied.get(component.componentId())));
        var planned = reviewBuilder(task).planComponents(server, material, byComponent, assessment, answers, databases, task);
        if (planned.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(planned);
        var plans = planned.value().orElseThrow();
        var bindings = units.resolveGraph(task, server, plans);
        var healthId = selection.healthComponent().orElse(discovered.getFirst().id());
        var healthPrompt = prompts.stream().filter(value -> value.componentId().equals(healthId)).findFirst()
            .orElseThrow();
        var helper = ensureHelper(server, task);
        if (helper.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(helper);
        DeploymentDiagnostics.progress(context, task, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "stage.checkSourceRevision"));
        Optional<DeploymentProjectRecord> project = DeploymentStandardProjectSave.save(context, task, draft, pinned, localProject);
        var corrections = new LinkedHashMap<String, DeploymentAssistedBuildCorrection>();
        if (mode.assisted())
            for (var prompt : prompts)
                corrections.put(prompt.componentId(),
                    advice.correction(prompt, supplied.get(prompt.componentId()), reviewer));
        return multi.prepareWithinTask(task, server, project, bindings.owners(), bindings.credentials(), healthId,
            runtime.health(completed(healthPrompt, supplied.get(healthId))),
            original -> new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(plans), Optional.empty()),
            Map.copyOf(corrections));
    }

    /** Prepares all locally verified databases under one durable original-task index. / 在一个持久原任务索引下准备全部本机已核数据库。
     * @param server exact target / 精确目标
     * @param material original frozen material / 原冻结素材
     * @param discovered original ordered components / 原有序组件
     * @param databaseLocal validated local plans / 已校验本机计划
     * @param reviewer owner view / 所属视图
     * @param task original task / 原任务
     * @return actual prepared facts or original refusal / 实际准备事实或原拒绝
     * @throws Exception if original preparation or journal fails / 原准备或日志失败时 */
    private OperationResult<Map<String, DeploymentPreparedDatabase>> graphDatabases(ServerId server,
                                                                                    DeploymentSourceMaterial material, List<DiscoveredProjectPart> discovered,
                                                                                    Map<String, DeploymentDatabaseStages.Local> databaseLocal, Reviewer reviewer, TaskContext task)
        throws Exception {
        var stages = new DeploymentDatabaseStages(context);
        var databasePlan = stages.begin(server, material.snapshot().sha256(), List.copyOf(databaseLocal.values()),
            task);
        if (databasePlan.isPresent()) {
            var helper = ensureHelper(server, task);
            if (helper.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(helper);
        }
        var databases = new HashMap<String, DeploymentPreparedDatabase>();
        for (var component : discovered) {
            var database = stages.prepare(server, databasePlan, databaseLocal.get(component.id()), reviewer, task);
            if (database.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(database);
            databases.put(component.id(), database.value().orElseThrow());
        }
        if (databasePlan.isPresent() && !stages.releaseKnown(task, databasePlan.orElseThrow()))
            return unknownPreparation();
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(Map.copyOf(databases)), Optional.empty());
    }

    private Map<String, Answers> selectWorkspaces(ServerId server, TaskContext task, DeploymentSourceMaterial material,
                                                  Map<String, DeploymentSourceArchive> archives, Map<String, DeploymentProjectFacts> facts,
                                                  Map<String, Answers> answers, DeploymentStandardModeSupport.Session advice) throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        var connected = context.linux().connect(server, task);
        long available;
        try (var session = connected) {
            var observed = new gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel(session, context.interaction(), server, task,
                material.snapshot().sha256(), new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget(120), layout).availableBytes();
            if (observed.outcome() != TaskResult.SUCCEEDED)
                throw new DeploymentAiJson.CapabilityOutcomeException(observed.outcome(), observed.problem());
            available = observed.value().orElseThrow();
        }
        var inputs = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input>();
        for (String id : new TreeSet<>(archives.keySet())) {
            var archive = archives.get(id);
            var demand = advice.workspaceDemand(facts.get(id), archive, answers.get(id), available, material, task);
            inputs.add(new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input(id, archive.byteCount(),
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.sourceFloor(archive.byteCount(), archive.uncompressedByteCount(), 1), demand));
        }
        var selected = gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.select(inputs, available, workspacePolicy,
            gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Cost.STANDARD);
        var result = new LinkedHashMap<String, Answers>();
        answers.forEach((id, answer) -> result.put(id, answer.withWorkspace(selected.get(id))));
        return Map.copyOf(result);
    }

    /** Collects source facts and locally checked database prompts for the entire graph. / 为整图收集源码事实及本机已核数据库问题。
     * @param server exact target / 精确目标
     * @param material live frozen material / 开放冻结素材
     * @param discovered original component roots / 原组件根
     * @param selection reviewed graph kinds / 经审阅整图类型
     * @param reviewer owner view / 所属视图
     * @param task original publication task / 原发布任务
     * @param facts collected actual component facts / 收集的实际组件事实
     * @param databaseLocal collected source-backed database plans / 收集的源码绑定数据库计划
     * @param advice optional assisted analysis / 可选辅助分析
     * @return complete local prompts or original refusal / 完整本机问题或原拒绝
     * @throws Exception if source inspection or review fails / 来源检查或审阅失败时 */
    private OperationResult<List<Prompt>> graphPrompts(ServerId server, DeploymentSourceMaterial material,
                                                       List<DiscoveredProjectPart> discovered, Selection selection, Reviewer reviewer, TaskContext task,
                                                       Map<String, DeploymentProjectFacts> facts, Map<String, DeploymentDatabaseStages.Local> databaseLocal,
                                                       DeploymentStandardModeSupport.Session advice) throws Exception {
        var prompts = new ArrayList<Prompt>();
        for (var component : discovered) {
            var initial = analyzer.analyzeComponent(material, component.relativeRoot(), component.id(),
                selection.choices().get(component.id()).type(), Optional.empty(), true, task);
            if (initial.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(initial);
            var assessed = initial.value().orElseThrow().assessment();
            if (assessed.admission() == DeploymentAdmissionStatus.REJECTED
                || assessed.admission() == DeploymentAdmissionStatus.RECOGNITION_PREVIEW)
                return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
            var observed = assessed.facts().orElseThrow();
            facts.put(component.id(), observed);
            var database = prepareDatabase(server, component.id(),
                MixedProjectChecker.managedComponentId(selection.applicationId(), component.id()), material,
                observed, reviewer, task);
            if (database.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(database);
            databaseLocal.put(component.id(), database.value().orElseThrow());
            prompts.add(advice.prompt(server, prompt(component.id(), observed,
                    assessed.runtimeSuggestion().orElse(null), database.value().orElseThrow().hasBindings()),
                material, task));
        }
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(List.copyOf(prompts)), Optional.empty());
    }

    /** Completes database declarations before runtime analysis and requires separate SQLite initialization consent. / 运行重分析前补齐数据库声明，并要求单独的 SQLite 初始化同意。
     * @param server exact target / 精确目标
     * @param componentId actual discovered component / 实际发现组件
     * @param applicationId final managed component identity / 最终受管组件身份
     * @param material original frozen source / 原冻结来源
     * @param facts actual component facts / 实际组件事实
     * @param reviewer original owner view / 原所属视图
     * @param task original publication task / 原发布任务
     * @return bound SQLite preparation or classified refusal / 绑定 SQLite 准备或分类拒绝
     * @throws Exception if source inspection or review fails / 来源检查或审阅失败时 */
    private OperationResult<DeploymentDatabaseStages.Local> prepareDatabase(ServerId server, String componentId,
                                                                            String applicationId, DeploymentSourceMaterial material, DeploymentProjectFacts facts, Reviewer reviewer,
                                                                            TaskContext task) throws Exception {
        return new DeploymentDatabaseStages(context).local(server, componentId, applicationId,
            material.snapshot().sha256(), facts.sourceRoot(), reviewer, task);
    }

    /** Review construction reads only the layout captured before original source selection. */
    private DeploymentStandardReviewBuilder reviewBuilder(TaskContext task) throws ApiException {
        return new DeploymentStandardReviewBuilder(context.servers(), new DeploymentStandardPlanner(),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
    }

    /** Ensures the fixed helper after final review but before ordinary publication effects. / 最终审阅后、普通发布副作用前确保固定 helper。
     * @param server exact reviewed target / 精确审阅目标
     * @param task original publication task / 原发布任务
     * @return known helper readiness or retained uncertainty / 已知 helper 就绪或留存未知
     * @throws ApiException if original access or persistence fails / 原访问或持久化失败时 */
    private OperationResult<Void> ensureHelper(ServerId server, TaskContext task) throws ApiException {
        try (var session = context.linux().connect(server, task)) {
            var checked = new DeploymentProtocolBootstrap(context.tasks(), task, server, session, context.interaction(),
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task))
                .ensure();
            if (checked.outcome() == TaskResult.FAILED && checked.problem()
                .filter(problem -> "DEPLOYMENT_ALTERNATIVE_REQUIRED".equals(problem.eventCode())).isPresent()) {
                var original = DeploymentProtocolBootstrapActionLog.find(context.tasks(), task);
                if (original.isPresent() && "INTENT".equals(original.orElseThrow().facts().phase())) {
                    var refusal = checked.problem().orElseThrow();
                    return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                        refusal.code(), refusal.eventCode(),
                        "Read-only helper verification failed; no supported alternative installer is available, so the refused installer was not sent",
                        refusal.context(), refusal.diagnosticId())));
                }
            }
            return new OperationResult<>(checked.outcome(), Optional.empty(), checked.problem());
        }
    }

    /** Detects an unresolved original remote preparation before releasing its marker or source. / 释放标记或来源前识别未决原远端准备。
     * @param task original publication task / 原发布任务
     * @return whether an effect still needs original recovery / 是否仍需原恢复核对效果 */
    private boolean unresolvedRemotePreparation(TaskContext task) {
        try {
            var helper = DeploymentProtocolBootstrapActionLog.find(context.tasks(), task);
            var database = DeploymentNativeDatabaseActionLog.find(context.tasks(), task);
            var indexed = DeploymentDatabasePlanActionLog.find(context.tasks(), task);
            if (indexed.isPresent() && !new DeploymentDatabaseStages(context).releaseKnown(task, indexed.orElseThrow()))
                return true;
            return helper.filter(value -> {
                try {
                    return "DISPATCHED".equals(value.facts().phase());
                } catch (ApiException unreadable) {
                    return true;
                }
            }).isPresent() || database.filter(value -> {
                try {
                    return "DISPATCHED".equals(value.facts().phase());
                } catch (ApiException unreadable) {
                    return true;
                }
            }).isPresent();
        } catch (ApiException | IllegalArgumentException unreadable) {
            return true;
        }
    }

    /** Retains the original task when an indexed preparation still lacks a confirmed terminal fact. / 索引准备仍缺确认终态事实时保留原任务。
     * @param <T> discarded prepared value / 丢弃的准备值
     * @return classified unknown with retained original recovery / 保留原恢复的已分类未知 */
    private static <T> OperationResult<T> unknownPreparation() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_DATABASE_PREPARATION_UNKNOWN",
                "Original database preparation needs exact receipt review", Map.of(), Optional.empty())));
    }

    /** Keeps prior helper effects visible when later local review ends without publication. / 后续本机审阅未发布即结束时保留先前 helper 效果提示。
     * @param result actual preparation result / 实际准备结果
     * @param task original publication task / 原发布任务
     * @return result with non-secret prior-effect context / 带非秘密先前效果上下文的结果
     * @throws ApiException if original evidence cannot be read / 原依据无法读取时 */
    private OperationResult<DeploymentRefFlow.PreparedPublish> priorEffects(
        OperationResult<DeploymentRefFlow.PreparedPublish> result, TaskContext task) throws ApiException {
        var retained = DeploymentProtocolBootstrapActionLog.find(context.tasks(), task);
        var plan = DeploymentDatabasePlanActionLog.find(context.tasks(), task);
        if (retained.isPresent() && "DISPATCHED".equals(retained.orElseThrow().facts().phase())) {
            var facts = retained.orElseThrow().facts();
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_HELPER_BOOTSTRAP_UNRESOLVED",
                    "Original helper installation needs exact receipt review",
                    Map.of("serverId", facts.serverId(), "operationId", facts.operationId().toString()),
                    Optional.empty())));
        }
        if (plan.isPresent() && !new DeploymentDatabaseStages(context).releaseKnown(task, plan.orElseThrow()))
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_DATABASE_PREPARATION_UNKNOWN",
                    "Original database effects need exact receipt review",
                    Map.of("serverId", plan.orElseThrow().facts().serverId()), Optional.empty())));
        if (retained.isEmpty() && plan.isEmpty())
            return result;
        var original = result.problem()
            .orElseGet(() -> new Problem(
                result.outcome() == TaskResult.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                "DEPLOYMENT_STATIC_REVIEW_ENDED", "Static review ended after preparation", Map.of(),
                Optional.empty()));
        var details = new HashMap<>(original.context());
        if (retained.isPresent() && !"INTENT".equals(retained.orElseThrow().facts().phase())) {
            var facts = retained.orElseThrow().facts();
            details.put("helperServerId", facts.serverId());
            details.put("helperOperationId", facts.operationId().toString());
            details.put("helperPhase", facts.phase());
        }
        if (plan.isPresent())
            details.putAll(new DeploymentDatabaseStages(context).effectDetails(task, plan.orElseThrow()));
        if (details.equals(original.context()))
            return result;
        return new OperationResult<>(result.outcome(), Optional.empty(),
            Optional.of(new Problem(original.code(), original.eventCode(),
                original.message() + "; review earlier helper, database or secret effects", details,
                original.diagnosticId())));
    }

    /** Projects exact selected and resolved Git identity into final consent. / 将精确选定并解析的 Git 身份投影到最终同意。
     * @param pinned original pinned source or null / 原固定来源或空值
     * @return final consent identity / 最终同意身份 */
    private static Optional<GitReview> gitReview(DeploymentGitSourceFlow.PublishSource pinned) {
        if (pinned == null)
            return Optional.empty();
        var material = pinned.material();
        return Optional
            .of(new GitReview(material.remote().location().toString(), pinned.reference(), material.commit()));
    }

    /** Checks exact frozen discovery choices before reading any component. / 读取组件前核对精确冻结发现选择。
     * @param discovered original source discovery / 原来源发现
     * @param selection reviewed selection / 经审阅选择
     * @return true only for complete bounded choices / 仅完整有界选择为真 */
    public static boolean validSelection(List<DiscoveredProjectPart> discovered, Selection selection) {
        try {
            DeploymentComponentSelection.resolve(discovered, selection);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Returns the canonical relative root used by both analysis and archive. / 返回分析与归档共用的规范相对根。
     * @param discovered original discovered root / 原发现根
     * @return slash-separated relative root / 斜线相对根 */
    private static String relative(DiscoveredProjectPart discovered) {
        return discovered.relativeRoot().toString().replace('\\', '/');
    }

    /** Applies edited answers to source defaults and rejects remaining missing inputs. / 将编辑答案应用于源码默认值并拒绝残留缺项。
     * @param prompt original source-backed prompt / 原源码依据问题
     * @param answer actual reviewer answer / 真实审阅答案
     * @return complete immutable runtime values / 完整不可变运行值 */
    private Map<String, String> completed(Prompt prompt, Answers answer) {
        var values = new LinkedHashMap<>(prompt.defaults());
        values.putAll(answer.values());
        if (!runtime.missing(prompt.componentId(), prompt.projectType(), values).isEmpty())
            throw new IllegalArgumentException("Required runtime fields remain unreviewed");
        return Map.copyOf(values);
    }

    /** Checks only local typed values so an owner can correct the same form before publication. / 仅校验本地类型化值，使所属者能在发布前修改同一表单。
     * @param prompt source-backed questions / 来源依据问题
     * @param answer edited non-secret values / 已编辑非秘密值
     * @throws IllegalArgumentException if the form is incomplete or malformed / 表单缺项或格式错误时 */
    public void validateReview(Prompt prompt, Answers answer) {
        var values = completed(prompt, answer);
        runtime.runtime(prompt.projectType(), values);
        try {
            DeploymentConfigInput.parse(values.getOrDefault("configuration", ""));
        } catch (IllegalArgumentException invalid) {
            throw new DeploymentReviewFieldException("configuration", invalid);
        }
        DeploymentConfigInput.secrets(values.getOrDefault("secrets", ""));
        var mode = DeploymentDatabaseReviewModeType.valueOf(values.getOrDefault("databaseMode", "UNREVIEWED"));
        DeploymentStandardInputParser.databaseBindings(mode, values.getOrDefault("databaseDetails", ""));
    }

    /** Derives a graph request from the exact reviewed runtime values. / 从精确审阅运行值派生整图请求。
     * @param component discovered component / 已发现组件
     * @param choice reviewed kind and edges / 经审阅类型与边
     * @param facts original source facts / 原来源事实
     * @param prompt source-backed defaults / 源码依据默认值
     * @param answer edited answer / 编辑答案
     * @return complete same-value analysis request / 完整同值分析请求 */
    private PartAnalysisRequest componentRequest(DiscoveredProjectPart component, Choice choice,
                                                 DeploymentProjectFacts facts, Prompt prompt, Answers answer) {
        var values = completed(prompt, answer);
        var definition = runtime.runtime(choice.type(), values);
        var configuration = DeploymentConfigInput.parse(values.getOrDefault("configuration", ""));
        var secrets = DeploymentConfigInput.secrets(values.getOrDefault("secrets", ""));
        var explicit = choice.inputs();
        var actualArtifacts = runtime.artifacts(component.relativeRoot(), facts, values);
        var artifacts = explicit.flatMap(ComponentInputs::artifactPaths).orElse(actualArtifacts);
        var actualPorts = definition.workload().endpoints().stream()
            .map(endpoint -> endpoint.hostPort()).collect(java.util.stream.Collectors.toSet());
        definition.healthCheck().portNumber().ifPresent(actualPorts::add);
        var ports = explicit.flatMap(ComponentInputs::ports).orElse(actualPorts);
        if (!artifacts.containsAll(actualArtifacts) || !ports.containsAll(actualPorts))
            throw new IllegalArgumentException("Manual declarations omit the actual reviewed runtime artifact or endpoint");
        return new PartAnalysisRequest(component.id(), relative(component).isEmpty() ? "." : relative(component),
            choice.type(), Optional.of(definition), artifacts, ports,
            configuration.stream().map(value -> value.key()).toList(),
            secrets.stream().map(DeploymentSecretRef::identifier).toList(),
            answer.files().stream().map(DeploymentFileBinding::dataPath).toList(), choice.dependencies(),
            explicit.map(ComponentInputs::required).orElse(true),
            PartIsolationSpec.managed());
    }

    /** Forms source-backed questions without treating defaults as user consent. / 形成来源依据问题而不把默认值当用户确认。
     * @param component component scope / 组件范围
     * @param facts actual analysis / 真实分析
     * @param suggestion optional analyzer recommendation / 可选分析建议
     * @param sourceDatabase whether a source-derived database replaces manual notation / 源码派生数据库是否取代手填记法
     * @return dynamic review prompt / 动态审阅问题 */
    private Prompt prompt(String component, DeploymentProjectFacts facts,
                          DeploymentRuntimeReport suggestion,
                          boolean sourceDatabase) {
        var defaults = runtime.defaults(facts.projectType(), suggestion, facts.sourceRoot());
        defaults = new LinkedHashMap<>(defaults);
        defaults.putIfAbsent("configuration", "");
        defaults.putIfAbsent("secrets", "");
        defaults.putIfAbsent("databaseMode", "NONE");
        defaults.putIfAbsent("databaseDetails", "");
        var fields = new ArrayList<DeploymentReviewField>(runtime.corrections(component, defaults).stream()
            .filter(field -> !sourceDatabase
                || !Set.of(component + "/databaseMode", component + "/databaseDetails").contains(field.id()))
            .toList());
        fields.addAll(runtime.missing(component, facts.projectType(), defaults));
        return new Prompt(component, facts.applicationId(), facts.projectType(), defaults, fields);
    }

    /** Binds an explicit decision to the actual manifest, tar bytes and server. / 将明确决定绑定真实清单、tar字节及服务器。
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param applicationId exact unit / 精确单元
     * @param material original material / 原素材
     * @param archive derived archive / 派生归档
     * @param answer confirmed non-secret input / 已确认非秘密输入
     * @return existing reviewed converter input / 既有审阅转换输入
     * @throws SQLException if configuration revision cannot be read / 配置修订无法读取时
     * @throws ApiException if the original task loses admission / 原任务失去准入时 */
    private DeploymentStandardReviewAnswers answers(TaskContext task, ServerId server, String applicationId,
                                                    DeploymentSourceMaterial material, DeploymentSourceArchive archive, Answers answer)
        throws SQLException, ApiException {
        var consent = new DeploymentReviewConsent(applicationId, server.id(), material.snapshot().sha256(),
            archive.sha256(), Instant.now());
        long revision = new DeploymentConfigStore(context.db()).nextRevision(task, applicationId);
        return new DeploymentStandardReviewAnswers(answer.values(), answer.files(), revision, answer.limits(), consent,
            answer.containerRisk(), answer.adapterRisk());
    }

    /** Keeps classified refusal while dropping an unavailable payload. / 保留分类拒绝且丢弃不可用载荷。
     * @param <T> expected payload / 预期载荷
     * @param result original result / 原结果
     * @return same classification without a payload / 相同分类且无载荷 */
    private static <T> OperationResult<T> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Rejects an incomplete local review without external effects. / 无外部副作用地拒绝不完整本机审阅。
     * @param <T> expected result type / 预期结果类型
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static <T> OperationResult<T> rejected(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Static review is incomplete", Map.of(),
                Optional.empty())));
    }

    /** Identifies source mutation guards that require independent verified database work. / 识别需要独立已验证数据库操作的源码变更门槛。
     * @param assessment actual reanalysis / 实际重分析
     * @return whether the missing evidence blocks publication / 缺依据是否阻止发布 */
    private static boolean schemaReviewMissing(DeploymentProjectReport assessment) {
        return assessment.rejections().stream().anyMatch(reason -> schemaReviewCode(reason.code()));
    }

    /** Matches only established source mutation diagnostic codes. / 仅匹配既有源码变更诊断码。
     * @param code established analyzer code / 既有分析码
     * @return whether schema evidence is needed / 是否需要模式依据 */
    private static boolean schemaReviewCode(String code) {
        return code.equals("AUTOMATIC_SCHEMA_MUTATION_DETECTED") || code.equals("DATABASE_MIGRATION_DETECTED");
    }

    /** Refuses publication until a real source-bound schema review exists. / 无真实来源绑定模式审阅前拒绝发布。
     * @return classified local failure / 已分类本机失败 */
    private static OperationResult<DeploymentRefFlow.PreparedPublish> schemaReviewRequired() {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SCHEMA_REVIEW_REQUIRED",
                "Source schema changes require verified database review before deployment", Map.of(),
                Optional.empty())));
    }

    /** Reports a confirmed local read failure without claiming a remote effect. / 报告确定的本机读取失败而不声称远端效果。
     * @param event stable safe event / 稳定安全事件
     * @return known local failure / 已知本机失败 */
    private static OperationResult<DeploymentRefFlow.PreparedPublish> ioFailure(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
            event, "Static source review could not be read", Map.of(), Optional.empty())));
    }

    /** Reports a known pre-effect cancellation. / 报告已知的效果前取消。
     * @return cancelled original preparation / 已取消原准备 */
    private static OperationResult<DeploymentRefFlow.PreparedPublish> cancelled() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
            "DEPLOYMENT_STATIC_REVIEW_CANCELLED", "Static review was cancelled", Map.of(), Optional.empty())));
    }
}

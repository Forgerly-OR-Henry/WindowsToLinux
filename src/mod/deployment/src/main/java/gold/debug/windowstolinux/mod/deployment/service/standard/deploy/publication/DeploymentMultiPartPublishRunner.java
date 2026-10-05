package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.MultiPartDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedPlanIdentity;

/** Runs a whole reviewed component graph under one original reference-recoverable task and borrowed session. / 在一个原可恢复引用任务和借用会话下运行完整已审阅组件图。 */
public final class DeploymentMultiPartPublishRunner {
    /** Actual original Mod activation. / 实际原Mod启用。 */
    private final ModContext context;

    /** Same registered reference transaction as single-component work. / 与单组件工作共用已登记引用事务。 */
    private final DeploymentRefFlow references;

    /** Original candidate implementation with independently prepared uploads. / 可独立准备上传的原候选实现。 */
    private final DeploymentStandardCandidateRunner candidates = new DeploymentStandardCandidateRunner();

    /** Binds the original publication capabilities without owning source or session. / 绑定原发布能力且不拥有来源或会话。
     * @param context actual Mod activation / 实际Mod启用
     * @param references registered original recovery flow / 已登记原恢复流程 */
    public DeploymentMultiPartPublishRunner(ModContext context, DeploymentRefFlow references) {
        this.context = Objects.requireNonNull(context);
        this.references = Objects.requireNonNull(references);
    }

    /** Submits one whole-graph publication whose reviewed source preparation runs within this same task. / 提交一项来源准备也在同一任务内运行的整图发布。
     * @param server exact admitted server / 精确准入服务器
     * @param project exact saved source or absence / 精确已保存来源或不存在
     * @param units exact component-to-owner mapping / 精确组件到归属映射
     * @param credentials exact saved secret revision identities / 精确已存秘密修订身份
     * @param healthComponent independently reviewed application gate owner / 独立审阅整应用门禁归属
     * @param health independently reviewed application probe / 独立审阅整应用探测
     * @param prepareSource original task source, archive and component planning / 原任务来源、归档及组件规划
     * @return original platform task / 原平台任务
     * @throws ApiException if task admission fails / 任务准入失败时 */
    public TaskHandle<Void> submit(ServerId server, Optional<DeploymentProjectRecord> project,
                                   Map<String, DeploymentUnitIdentity> units, Map<DeploymentSecretRef, SecretId> credentials,
                                   String healthComponent, DeploymentHealthCheck health,
                                   TaskWork<DeploymentStandardPlanner.PartPlans> prepareSource) throws ApiException {
        Objects.requireNonNull(server);
        Objects.requireNonNull(project);
        units = Map.copyOf(units);
        credentials = Map.copyOf(credentials);
        Objects.requireNonNull(healthComponent);
        Objects.requireNonNull(health);
        Objects.requireNonNull(prepareSource);
        var originalUnits = units;
        var originalCredentials = credentials;
        return references.submitPrepared(server, task -> prepareWithinTask(task, server, project, originalUnits,
            originalCredentials, healthComponent, health, prepareSource));
    }

    /** Creates the graph's complete reference intent before any remote candidate effect. / 任一远端候选副作用前创建整图完整引用意图。
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param project reviewed saved project / 已审阅已存项目
     * @param units exact component owners / 精确组件归属
     * @param credentials exact credentials / 精确凭据
     * @param healthComponent whole-health owner / 整图健康归属
     * @param health whole-health probe / 整图健康探测
     * @param prepareSource original source callback / 原来源回调
     * @return original prepared graph / 原已准备图
     * @throws Exception if original source work fails / 原来源工作失败时 */
    public OperationResult<DeploymentRefFlow.PreparedPublish> prepareWithinTask(TaskContext task,
                                                                                ServerId server, Optional<DeploymentProjectRecord> project, Map<String, DeploymentUnitIdentity> units,
                                                                                Map<DeploymentSecretRef, SecretId> credentials, String healthComponent, DeploymentHealthCheck health,
                                                                                TaskWork<DeploymentStandardPlanner.PartPlans> prepareSource) throws Exception {
        return prepareWithinTask(task, server, project, units, credentials, healthComponent, health, prepareSource,
            Map.of());
    }

    /** Binds bounded typed component corrections to the same original whole-graph publication. / 将有界类型化组件纠正绑定同一原整图发布。
     * @param task original authentic task / 原真实任务
     * @param server original exact server / 原精确服务器
     * @param project original saved source / 原已存来源
     * @param units exact original owners / 精确原归属
     * @param credentials original exact revisions / 原精确修订
     * @param healthComponent original whole-health owner / 原整图健康归属
     * @param health original whole-health gate / 原整图健康门禁
     * @param prepareSource original source preparation / 原来源准备
     * @param corrections same-task bounded review for each original component / 各原组件同任务有界审阅
     * @return original prepared publication / 原已准备发布
     * @throws Exception if original preparation is unavailable / 原准备不可用时 */
    public OperationResult<DeploymentRefFlow.PreparedPublish> prepareWithinTask(TaskContext task,
                                                                                ServerId server, Optional<DeploymentProjectRecord> project, Map<String, DeploymentUnitIdentity> units,
                                                                                Map<DeploymentSecretRef, SecretId> credentials, String healthComponent, DeploymentHealthCheck health,
                                                                                TaskWork<DeploymentStandardPlanner.PartPlans> prepareSource,
                                                                                Map<String, DeploymentAssistedBuildCorrection> corrections) throws Exception {
        Objects.requireNonNull(task);
        Objects.requireNonNull(server);
        Objects.requireNonNull(project);
        Objects.requireNonNull(healthComponent);
        Objects.requireNonNull(health);
        Objects.requireNonNull(prepareSource);
        var originalUnits = Map.copyOf(units);
        var originalCredentials = Map.copyOf(credentials);
        var prepared = prepareSource.run(task);
        if (prepared.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(prepared.outcome(), Optional.empty(), prepared.problem());
        var componentPlans = prepared.value().orElseThrow();
        var plans = componentPlans.components();
        var ordering = componentPlans.ordering();
        if (!corrections.isEmpty() && !corrections.keySet().equals(plans.keySet()))
            throw new IllegalArgumentException("Assisted review differs from original component set");
        boolean handedOff = false;
        try {
            if (ordering.startOrder().size() < 2 || ordering.startOrder().size() > 16
                || ordering.startOrder().size() != plans.size()
                || new HashSet<>(ordering.startOrder()).size() != ordering.startOrder().size()
                || !originalUnits.keySet().equals(plans.keySet())
                || !ordering.startOrder().contains(healthComponent)
                || !ordering.candidateNamespaces().keySet().equals(plans.keySet()))
                throw new IllegalArgumentException("Reviewed graph exceeds the 16-component execution bound");
            var material = plans.get(ordering.startOrder().getFirst()).request().material();
            var sourceId = material.snapshot().id();
            var sourceSha = material.snapshot().sha256();
            var union = new HashSet<DeploymentSecretRef>();
            var identities = new LinkedHashMap<String, String>();
            var intents = new ArrayList<DeploymentRefIntent>();
            intents.add(DeploymentRefKey.application(server, ordering.applicationId()));
            for (var id : ordering.startOrder()) {
                var request = plans.get(id).request();
                var unit = originalUnits.get(id);
                if (!server.equals(request.server()) || !server.equals(unit.server())
                    || !ordering.candidateNamespaces().get(id).equals(request.facts().applicationId())
                    || !unit.id().equals(request.facts().applicationId())
                    || !sourceId.equals(request.material().snapshot().id())
                    || !sourceSha.equals(request.material().snapshot().sha256())
                    || !sourceId.equals(request.archive().originSnapshotId())
                    || !sourceSha.equals(request.archive().sourceManifestSha256()))
                    throw new IllegalArgumentException("Component differs from the original graph or source");
                union.addAll(request.secretReferences());
                identities.put(id, ReviewedPlanIdentity.from(request));
                intents.add(DeploymentRefKey.unit(server, unit.id()));
            }
            if (!union.equals(originalCredentials.keySet())
                || project.isPresent() && !project.orElseThrow().source().sha256().equals(sourceSha))
                throw new IllegalArgumentException("Original graph secret or project identity differs");
            var repository = new DeploymentSecretStore(context.db());
            for (var entry : originalCredentials.entrySet()) {
                var actual = repository.findRevision(task, entry.getKey());
                if (actual.isPresent() && actual.orElseThrow().purpose().isEmpty())
                    return failed("DEPLOYMENT_SECRET_PURPOSE_UNAVAILABLE");
                if (actual.isEmpty() || !actual.orElseThrow().credentialId().equals(entry.getValue().id())
                    || !actual.orElseThrow().purpose().equals(Optional.of(entry.getValue().purpose())))
                    throw new IllegalArgumentException("Secret revision differs from its stored credential");
                intents.add(DeploymentRefKey.secretRevision(server, entry.getValue(), entry.getKey()));
            }
            var revision = graphRevision(ordering, identities, healthComponent, health);
            var plan = new DeploymentPublishPlan(UUID.randomUUID(), project, ordering.applicationId(), server,
                DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, revision, intents,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
            var root = new AtomicReference<DeploymentGraphPublishActionLog>();
            var journals = new AtomicReference<Map<String, DeploymentPublishCleanupActionLog>>(Map.of());
            var ready = new DeploymentRefFlow.PreparedPublish(plan,
                original -> author(ordering, plans, originalUnits, originalCredentials, healthComponent, health,
                    identities, original, root, journals, corrections),
                () -> closeOriginal(plans, root.get(), journals.get()));
            handedOff = true;
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(ready), Optional.empty());
        } catch (SQLException readFailure) {
            return failed("DEPLOYMENT_GRAPH_SECRET_METADATA_UNAVAILABLE");
        } catch (IllegalArgumentException invalid) {
            return failed("DEPLOYMENT_GRAPH_PREPARATION_INVALID");
        } finally {
            if (!handedOff)
                closeOriginal(plans, null, Map.of());
        }
    }

    /** Executes one shared-session graph only after the root and every component identity are persisted. / 仅在根及每个组件身份持久后以共享会话执行整图。
     * @param ordering original dependency plan / 原依赖计划
     * @param plans exact component plans / 精确组件计划
     * @param units exact managed owners / 精确受管归属
     * @param credentials saved opaque credentials / 已存不透明凭据
     * @param healthComponent whole-health owner / 整图健康归属
     * @param health independent whole-health gate / 独立整图健康门禁
     * @param identities component review digests / 组件审阅摘要
     * @param task original admitted task / 原准入任务
     * @param rootRef graph occupancy returned to cleanup / 交予清理的图占用
     * @param journalRef component occupancies returned to cleanup / 交予清理的组件占用
     * @param corrections original typed component review callbacks / 原类型化组件审阅回调
     * @return one verified graph or retained unknown / 一个已核实图或保留未知 */
    private OperationResult<DeploymentPublish> author(MultiPartDeploymentPlan ordering,
                                                      Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan> plans,
                                                      Map<String, DeploymentUnitIdentity> units, Map<DeploymentSecretRef, SecretId> credentials,
                                                      String healthComponent, DeploymentHealthCheck health, Map<String, String> identities, TaskContext task,
                                                      AtomicReference<DeploymentGraphPublishActionLog> rootRef,
                                                      AtomicReference<Map<String, DeploymentPublishCleanupActionLog>> journalRef,
                                                      Map<String, DeploymentAssistedBuildCorrection> corrections) {
        if (cancelled(task))
            return cancelled();
        try {
            var indexes = new ArrayList<DeploymentGraphPublishActionLog.PartIndex>();
            for (int index = 0; index < ordering.startOrder().size(); index++) {
                var id = ordering.startOrder().get(index);
                indexes.add(new DeploymentGraphPublishActionLog.PartIndex(id, units.get(id).id(),
                    "deployment-publication-unit-" + String.format(java.util.Locale.ROOT, "%02d", index),
                    identities.get(id)));
            }
            var graphPlan = graphRevision(ordering, identities, healthComponent, health);
            rootRef.set(DeploymentGraphPublishActionLog.begin(context, task,
                units.get(ordering.startOrder().getFirst()).server().id(), ordering, graphPlan, indexes));
            var journals = new LinkedHashMap<String, DeploymentPublishCleanupActionLog>();
            journalRef.set(journals);
            for (var index : indexes) {
                var request = plans.get(index.componentId()).request();
                journals.put(index.componentId(),
                    DeploymentPublishCleanupActionLog.begin(context, task, index.key(),
                        units.get(index.componentId()), request.archive().sha256(), index.planSha256(),
                        request.runtime()));
            }
        } catch (Exception unconfirmed) {
            return unknown("DEPLOYMENT_GRAPH_CHECKPOINT_UNCONFIRMED");
        }
        final RemoteSession session;
        try {
            session = context.linux().connect(units.get(ordering.startOrder().getFirst()).server(), task);
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            return unknown("DEPLOYMENT_GRAPH_SESSION_UNCONFIRMED");
        }
        OperationResult<DeploymentPublish> result;
        try {
            return remote(ordering, plans, units, credentials, healthComponent, health, session, task, rootRef.get(),
                journalRef.get(), corrections);
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            result = unknown("DEPLOYMENT_GRAPH_REMOTE_UNCONFIRMED");
        }
        try {
            session.close();
            if (result.outcome() == TaskResult.SUCCEEDED || result.outcome() == TaskResult.FAILED
                || result.outcome() == TaskResult.CANCELLED) {
                boolean all = true;
                for (var journal : journalRef.get().values())
                    all &= journal.sessionClosed();
                if (all)
                    rootRef.get().mark("SESSION_CLOSED");
            }
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            // The original graph and each component remain occupied for recovery.
        }
        return result;
    }

    /** Checks all components before any upload, then builds every candidate before one graph transaction. / 任一上传前检查全部组件，再构建全部候选后执行一次整图事务。
     * @param ordering original graph / 原图
     * @param plans original component plans / 原组件计划
     * @param units exact owners / 精确归属
     * @param credentials saved credentials / 已存凭据
     * @param healthComponent whole-health owner / 整图健康归属
     * @param health whole-health gate / 整图健康门禁
     * @param session same original borrowed session / 同一原借用会话
     * @param task original task / 原任务
     * @param root graph checkpoint / 图检查点
     * @param journals component checkpoints / 组件检查点
     * @param corrections original bounded typed review callbacks / 原有界类型化审阅回调
     * @return one graph publication or non-success / 一份图发布或非成功
     * @throws ApiException if host admission rejects / 宿主准入拒绝时
     * @throws Exception if the original reviewed callback or publication operation fails / 原审阅回调或发布操作失败时 */
    private OperationResult<DeploymentPublish> remote(MultiPartDeploymentPlan ordering,
                                                      Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan> plans,
                                                      Map<String, DeploymentUnitIdentity> units, Map<DeploymentSecretRef, SecretId> credentials,
                                                      String healthComponent, DeploymentHealthCheck health, RemoteSession session, TaskContext task,
                                                      DeploymentGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals,
                                                      Map<String, DeploymentAssistedBuildCorrection> corrections) throws Exception {
        try (var owned = new DeploymentPublishAuthorSession(session, journals, root)) {
            try {
                plans = new LinkedHashMap<>(plans);
                var assisted = corrections.isEmpty()
                    ? null
                    : new DeploymentAssistedPublishBuild(context, candidates);
                var prepared = new LinkedHashMap<String, DeploymentStandardCandidateRunner.Prepared>();
                long required = 0;
                long available = Long.MAX_VALUE;
                for (var id : ordering.startOrder()) {
                    var observed = candidates.prepare(plans.get(id), session, context.interaction(), task,
                        assisted == null || assisted.fallbackAvailable());
                    if (observed.outcome() != TaskResult.SUCCEEDED)
                        return observed.outcome() == TaskResult.UNKNOWN
                            ? unknown("DEPLOYMENT_GRAPH_PREPARE_UNCONFIRMED")
                            : beforeCandidateFailure(ordering, plans, units, session, task, root, journals,
                            observed);
                    var selection = observed.value().orElseThrow();
                    if (assisted != null && !assisted.prepared(selection))
                        return beforeCandidateFailure(ordering, plans, units, session, task, root, journals,
                            new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional
                                .of(problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_ASSISTED_RETRY_BUDGET"))));
                    try {
                        required = Math.addExact(required, selection.requiredSpace());
                    } catch (ArithmeticException overflow) {
                        return beforeCandidateFailure(ordering, plans, units, session, task, root, journals,
                            new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional
                                .of(problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_GRAPH_SPACE_OVERFLOW"))));
                    }
                    available = Math.min(available, selection.availableBytes());
                    prepared.put(id, selection);
                }
                if (required > available)
                    return beforeCandidateFailure(ordering, plans, units, session, task, root, journals,
                        new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                            Optional.of(problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_GRAPH_TARGET_SPACE"))));
                if (!root.mark("ALL_PREPARED"))
                    return unknown("DEPLOYMENT_GRAPH_PREPARE_BOUNDARY_UNCONFIRMED");
                while (true) {
                    gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.progress(context, task,
                        "Uploading and building the original component graph / 上传并构建原组件图");
                    var built = buildAll(ordering, plans, units, prepared, healthComponent, health, session, task, root,
                        journals, corrections, assisted);
                    if (built.outcome() != TaskResult.SUCCEEDED)
                        return new OperationResult<>(built.outcome(), Optional.empty(), built.problem());
                    var builds = built.value().orElseThrow();
                    var result = new DeploymentMultiPartPublishTransaction(context).publish(ordering, plans,
                        units, credentials, builds, healthComponent, health, session, task, root, journals);
                    if (result.outcome() == TaskResult.UNKNOWN || result.outcome() == TaskResult.CANCELLED)
                        return result;
                    boolean retired = owned.retire();
                    if (result.outcome() != TaskResult.FAILED || !retired || assisted == null
                        || result.problem().isEmpty())
                        return result;
                    String selectedUnit = result.problem().orElseThrow().context().get("publicationHealthFailure");
                    var selected = units.entrySet().stream().filter(entry -> entry.getValue().id().equals(selectedUnit))
                        .findFirst();
                    if (selected.isEmpty())
                        return result;
                    var next = new DeploymentAssistedPublishAttempts(context, assisted, candidates).advance(plans,
                        units, corrections, selected.orElseThrow().getKey(),
                        graphDescription(ordering, plans, healthComponent, health), root, journals,
                        result.problem().orElseThrow(), task);
                    if (next.outcome() != TaskResult.SUCCEEDED)
                        return new OperationResult<>(next.outcome(), Optional.empty(), next.problem());
                    var continuation = next.value().orElseThrow();
                    owned.next(continuation.session());
                    session = owned.current();
                    plans.clear();
                    plans.putAll(continuation.plans());
                    prepared.clear();
                    prepared.putAll(continuation.prepared());
                }
            } finally {
                owned.retire();
            }
        }
    }

    /** Builds every current reviewed component with the original shared correction allowance. / 使用原共用纠正额度构建每个当前已审阅组件。
     * @param ordering original dependency ordering / 原依赖顺序
     * @param plans current complete plans / 当前完整计划
     * @param units original exact units / 原精确单元
     * @param prepared actual current preparations / 实际当前准备
     * @param healthComponent whole-health owner / 整图健康归属
     * @param health exact whole-health check / 精确整图健康检查
     * @param session actual current session / 实际当前会话
     * @param task authentic original task / 真实原任务
     * @param root original graph checkpoint / 原图检查点
     * @param journals original child checkpoints / 原子检查点
     * @param corrections actual typed human reviewers / 实际类型化人工审阅器
     * @param assisted original shared loop or absence / 原共用循环或空值
     * @return complete verified candidates or original failure / 完整已核候选或原失败
     * @throws Exception if original evidence is unconfirmed / 原依据未确认时 */
    private OperationResult<Map<String, DeploymentVerifiedBuildResult>> buildAll(MultiPartDeploymentPlan ordering,
                                                                                 Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan> plans,
                                                                                 Map<String, DeploymentUnitIdentity> units,
                                                                                 Map<String, DeploymentStandardCandidateRunner.Prepared> prepared, String healthComponent,
                                                                                 DeploymentHealthCheck health, RemoteSession session, TaskContext task,
                                                                                 DeploymentGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals,
                                                                                 Map<String, DeploymentAssistedBuildCorrection> corrections, DeploymentAssistedPublishBuild assisted)
        throws Exception {
        var builds = new LinkedHashMap<String, DeploymentVerifiedBuildResult>();
        for (var wave : ordering.buildWaves())
            for (var id : wave) {
                if (cancelled(task))
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GRAPH_CANDIDATE_CANCELLED")));
                OperationResult<DeploymentVerifiedBuildResult> observed;
                if (assisted == null)
                    observed = candidates.build(prepared.get(id), session, task);
                else {
                    var completed = assisted.build(prepared.get(id), corrections.get(id), units.get(id),
                        journals.get(id), root, () -> graphDescription(ordering, plans, healthComponent, health),
                        session, task);
                    plans.put(id, completed.plan());
                    observed = completed.result();
                }
                if (observed.outcome() != TaskResult.SUCCEEDED) {
                    var failure = observed.outcome() == TaskResult.UNKNOWN || observed.outcome() == TaskResult.CANCELLED
                        ? unknown("DEPLOYMENT_GRAPH_CANDIDATE_UNCONFIRMED")
                        : candidateFailure(ordering, plans, units, session, task, root, journals, observed);
                    return new OperationResult<>(failure.outcome(), Optional.empty(), failure.problem());
                }
                builds.put(id, observed.value().orElseThrow());
            }
        if (!root.mark("ALL_BUILT"))
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GRAPH_BUILD_BOUNDARY_UNCONFIRMED")));
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(builds), Optional.empty());
    }

    /** Releases original checkpoints only when no candidate creation was ever attempted. / 仅在从未尝试创建候选时释放原检查点。
     * @param ordering original graph / 原图
     * @param plans original plans / 原计划
     * @param units owners / 归属
     * @param session borrowed session / 借用会话
     * @param task original task / 原任务
     * @param root graph occupancy / 图占用
     * @param journals component occupancies / 组件占用
     * @param failure known pre-candidate failure / 已知候选前失败
     * @return original known outcome / 原已知结果 */
    private OperationResult<DeploymentPublish> beforeCandidateFailure(MultiPartDeploymentPlan ordering,
                                                                      Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan> plans,
                                                                      Map<String, DeploymentUnitIdentity> units, RemoteSession session, TaskContext task,
                                                                      DeploymentGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals,
                                                                      OperationResult<?> failure) {
        if (!root.verified(failure.outcome(), failure.problem()))
            return unknown("DEPLOYMENT_GRAPH_PRECHECK_RESULT_UNCONFIRMED");
        for (var journal : journals.values())
            if (!journal.remoteClean())
                return unknown("DEPLOYMENT_GRAPH_PRECHECK_CLEANUP_UNCONFIRMED");
        root.mark("REMOTE_CLEANUP_VERIFIED");
        return new OperationResult<>(failure.outcome(), Optional.empty(), failure.problem());
    }

    /** Removes only known candidate effects after a confirmed build failure. / 仅在构建明确失败后清除已知候选副作用。
     * @param ordering original graph / 原图
     * @param plans original plans / 原计划
     * @param units owners / 归属
     * @param session borrowed session / 借用会话
     * @param task original task / 原任务
     * @param root graph checkpoint / 图检查点
     * @param journals component checkpoints / 组件检查点
     * @param failure known build failure / 已知构建失败
     * @return failed after confirmed cleanup or unknown / 清理确认后的失败或未知 */
    private OperationResult<DeploymentPublish> candidateFailure(MultiPartDeploymentPlan ordering,
                                                                Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan> plans,
                                                                Map<String, DeploymentUnitIdentity> units, RemoteSession session, TaskContext task,
                                                                DeploymentGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals,
                                                                OperationResult<?> failure) {
        if (!root.verified(TaskResult.FAILED, failure.problem()))
            return unknown("DEPLOYMENT_GRAPH_BUILD_FAILURE_UNCONFIRMED");
        for (var id : ordering.startOrder()) {
            var request = plans.get(id).request();
            var journal = journals.get(id);
            var channel = new DeploymentPublishChannel(session, context.interaction(), units.get(id).server(), task,
                journal.facts().path("planSha256").asText());
            try {
                if (!DeploymentPublishCleanupAttempt.run(journal, "CANDIDATE_CLEANUP_MAY_HAVE_RUN",
                    () -> channel.cleanupCandidate(units.get(id), journal.facts().path("candidate").asText()))
                    || !journal.mark("CANDIDATE_CLEANED") || !journal.remoteClean())
                    return unknown("DEPLOYMENT_GRAPH_CANDIDATE_CLEANUP_UNCONFIRMED");
            } catch (ApiException unconfirmed) {
                return unknown("DEPLOYMENT_GRAPH_CANDIDATE_CLEANUP_UNCONFIRMED");
            }
        }
        if (!root.mark("REMOTE_CLEANUP_VERIFIED"))
            return unknown("DEPLOYMENT_GRAPH_BUILD_FAILURE_UNCONFIRMED");
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), failure.problem());
    }

    /** Closes each actual source/archive object once after the entire graph and releases only verified occupations. / 整图结束后各实际来源和归档对象仅关闭一次，且仅释放已核实占用。
     * @param plans original source-bearing plans / 原承载来源计划
     * @param root original graph journal or absence / 原图账本或不存在
     * @param journals original component journals / 原组件账本
     * @throws Exception if an original resource close cannot be confirmed / 原资源关闭无法确认时 */
    private static void closeOriginal(
        Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan> plans,
        DeploymentGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals)
        throws Exception {
        var closed = new IdentityHashMap<AutoCloseable, Boolean>();
        Exception failure = null;
        for (var plan : plans.values()) {
            for (AutoCloseable resource : List.<AutoCloseable>of(plan.request().archive(), plan.request().material())) {
                if (closed.put(resource, true) != null)
                    continue;
                try {
                    resource.close();
                } catch (Exception unconfirmed) {
                    if (failure == null)
                        failure = unconfirmed;
                    else
                        failure.addSuppressed(unconfirmed);
                }
            }
        }
        if (failure == null && root != null && root.facts().path("phase").asText().equals("SESSION_CLOSED")) {
            boolean all = true;
            for (var journal : journals.values())
                all &= journal.release();
            if (all)
                root.release();
        }
        if (failure != null)
            throw failure;
    }

    /** Hashes the complete reviewed graph with explicit lengths, independent of final release identities. / 使用显式长度哈希完整审阅图，独立于最终发布身份。
     * @param ordering deterministic graph / 确定性图
     * @param identities component plan digests / 组件计划摘要
     * @param healthComponent whole-health owner / 整图健康归属
     * @param health independent whole-health probe / 独立整图健康探测
     * @return bounded lowercase reviewed digest / 有界小写审阅摘要
     * @throws IOException if fixed binary encoding fails / 固定二进制编码失败时 */
    private static String graphRevision(MultiPartDeploymentPlan ordering, Map<String, String> identities,
                                        String healthComponent, DeploymentHealthCheck health) throws IOException {
        return DeploymentReviewedPlanDigest.graphFrom(
            new DeploymentReviewedPlanDigest.GraphValues(ordering.applicationId(), healthComponent, health,
                ordering.startOrder(), ordering.candidateNamespaces(), ordering.dependencies(), identities));
    }

    /** Copies the actual current child plans into complete canonical root framing. / 将实际当前子计划复制为完整规范根分界。
     * @param ordering fixed original graph / 固定原图
     * @param plans actual current source-bound plans / 实际当前来源绑定计划
     * @param owner original whole-health owner / 原整图健康归属
     * @param health original whole-health gate / 原整图健康门禁
     * @return complete original graph framing / 完整原图分界 */
    private static String graphDescription(MultiPartDeploymentPlan ordering,
                                           Map<String, gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan> plans,
                                           String owner, DeploymentHealthCheck health) {
        var identities = new LinkedHashMap<String, String>();
        plans.forEach((id, plan) -> identities.put(id, ReviewedPlanIdentity.from(plan.request())));
        try {
            return DeploymentAssistedBuildReviewCodec.encodeGraph(new DeploymentReviewedPlanDigest.GraphValues(
                ordering.applicationId(), owner, health, ordering.startOrder(), ordering.candidateNamespaces(),
                ordering.dependencies(), identities));
        } catch (SQLException invalid) {
            throw new IllegalArgumentException("Original graph framing is invalid", invalid);
        }
    }

    /** Reports a known pre-publication rejection. / 报告已知发布前拒绝。
     * @param event stable event / 稳定事件
     * @return known failed preparation / 已知失败准备 */
    private static OperationResult<DeploymentRefFlow.PreparedPublish> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(problem(ProblemCode.INVALID_INPUT, event)));
    }

    /** Detects the original task's cooperative cancellation. / 检测原任务协作取消。
     * @param task original task / 原任务
     * @return true when requested / 请求时为真 */
    private static boolean cancelled(TaskContext task) {
        return task.cancelToken().requested() || Thread.currentThread().isInterrupted();
    }

    /** Reports cancellation before any original remote effect. / 任一原远端副作用前报告取消。
     * @return cancelled result / 已取消结果 */
    private static OperationResult<DeploymentPublish> cancelled() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
            Optional.of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_GRAPH_CANCELLED")));
    }

    /** Retains one unconfirmed graph effect without replay. / 不重放地保留整图未确认副作用。
     * @param event stable event / 稳定事件
     * @return explicit unknown / 显式未知 */
    private static OperationResult<DeploymentPublish> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Creates bounded non-secret public metadata. / 创建有界无秘密公开元数据。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return bounded problem / 有界问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Whole reviewed graph requires original-task evidence", Map.of(),
            Optional.empty());
    }
}

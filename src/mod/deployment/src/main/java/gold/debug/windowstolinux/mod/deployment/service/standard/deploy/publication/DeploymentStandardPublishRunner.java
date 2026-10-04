package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.DeploymentStandardCandidateRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedPlanIdentity;

/** Builds and publishes one reviewed candidate inside the same original reference-recoverable task. / 在同一原可恢复引用任务内构建并发布一个已审阅候选。 */
public final class DeploymentStandardPublishRunner {
    /** Original Mod activation. / 原Mod启用。 */
    private final ModContext context;

    /** Already registered original publication recovery. / 已登记的原发布恢复器。 */
    private final DeploymentRefFlow references;

    /** Builds and publishes one original task without owning the caller's reviewed-source callback. / 构建并发布一个原任务，不接管调用方已审阅来源回调。
     * @param context original active Mod / 原活动Mod
     * @param references actual registered publication flow / 实际已登记发布流程 */
    public DeploymentStandardPublishRunner(ModContext context, DeploymentRefFlow references) {
        this.context = Objects.requireNonNull(context);
        this.references = Objects.requireNonNull(references);
    }

    /** Submits source preparation, durable reference intent and one remote publication under one task. / 在一个任务下提交来源准备、持久引用意图与一次远端发布。
     * @param unit exact managed ownership supplied from the reviewed target / 来自已审阅目标的精确受管归属
     * @param project exact saved project when the reviewed source was saved / 已审阅来源已保存时的精确项目
     * @param credentials exact opaque credential for each reviewed secret revision / 每个已审阅秘密修订的精确不透明凭据
     * @param prepareSource original-task callback retaining open source and archive until author cleanup / 原任务回调，保持来源和归档开放直至作者清理
     * @return original platform task / 原平台任务
     * @throws ApiException if task admission fails / 任务准入失败时 */
    public TaskHandle<Void> submit(DeploymentUnitIdentity unit, Optional<DeploymentProjectRecord> project,
                                   Map<DeploymentSecretRef, SecretId> credentials, TaskWork<ReviewedDeploymentPlan> prepareSource)
        throws ApiException {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(project);
        credentials = Map.copyOf(credentials);
        Objects.requireNonNull(prepareSource);
        Map<DeploymentSecretRef, SecretId> originals = credentials;
        return references.submitPrepared(unit.server(),
            task -> prepareWithinTask(task, unit, project, originals, prepareSource));
    }

    /** Binds the open source and actual saved credentials before the first publication reservation. / 在首次发布预留前绑定开放来源及实际已保存凭据。
     * @param task original task / 原任务
     * @param unit exact owned unit / 精确所属单元
     * @param project exact saved source / 精确已保存来源
     * @param credentials reviewed opaque credentials / 已审阅不透明凭据
     * @param prepareSource original source callback / 原来源回调
     * @return task-bound publication facts / 任务绑定发布事实
     * @throws Exception if original preparation fails / 原准备失败时 */
    public OperationResult<DeploymentRefFlow.PreparedPublish> prepareWithinTask(TaskContext task,
                                                                                DeploymentUnitIdentity unit, Optional<DeploymentProjectRecord> project,
                                                                                Map<DeploymentSecretRef, SecretId> credentials, TaskWork<ReviewedDeploymentPlan> prepareSource)
        throws Exception {
        return prepareWithinTask(task, unit, project, credentials, prepareSource, Optional.empty());
    }

    /** Adds only a task-bound typed build correction to the original one-shot publication. / 仅给原一次性发布增加任务绑定类型化构建纠正。
     * @param task authentic original task / 真实原任务
     * @param unit original owned unit / 原所属单元
     * @param project exact saved source / 精确已存来源
     * @param credentials exact saved revisions / 精确已存修订
     * @param prepareSource original source preparation / 原来源准备
     * @param correction optional bounded original build review / 可选有界原构建审阅
     * @return original one-shot publication / 原一次性发布
     * @throws Exception if original preparation is unavailable / 原准备不可用时 */
    public OperationResult<DeploymentRefFlow.PreparedPublish> prepareWithinTask(TaskContext task,
                                                                                DeploymentUnitIdentity unit, Optional<DeploymentProjectRecord> project,
                                                                                Map<DeploymentSecretRef, SecretId> credentials, TaskWork<ReviewedDeploymentPlan> prepareSource,
                                                                                Optional<DeploymentAssistedBuildCorrection> correction) throws Exception {
        Objects.requireNonNull(task);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(project);
        Objects.requireNonNull(prepareSource);
        var originals = Map.copyOf(credentials);
        var prepared = prepareSource.run(task);
        if (prepared.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(prepared.outcome(), Optional.empty(), prepared.problem());
        var plan = prepared.value().orElseThrow();
        boolean handedOff = false;
        try {
            var request = plan.request();
            if (!unit.server().equals(request.server()) || !unit.id().equals(request.facts().applicationId())
                || !originals.keySet().equals(new java.util.HashSet<>(request.secretReferences())))
                throw new IllegalArgumentException("Publish target or secrets differ from reviewed source");
            if (project.isPresent()
                && !project.orElseThrow().source().sha256().equals(request.material().snapshot().sha256()))
                throw new IllegalArgumentException("Saved project source differs from original frozen material");
            var repository = new DeploymentSecretStore(context.db());
            for (var entry : originals.entrySet()) {
                var actual = repository.findRevision(task, entry.getKey());
                if (actual.isPresent() && actual.orElseThrow().purpose().isEmpty())
                    return failed("DEPLOYMENT_SECRET_PURPOSE_UNAVAILABLE");
                if (actual.isEmpty() || !actual.orElseThrow().credentialId().equals(entry.getValue().id())
                    || !actual.orElseThrow().purpose().equals(Optional.of(entry.getValue().purpose())))
                    throw new IllegalArgumentException("Secret revision differs from its stored credential");
            }
            var intents = new ArrayList<DeploymentRefIntent>();
            intents.add(DeploymentRefKey.application(unit.server(), unit.id()));
            intents.add(DeploymentRefKey.unit(unit.server(), unit.id()));
            originals.forEach((revision, secret) -> intents
                .add(DeploymentRefKey.secretRevision(unit.server(), secret, revision)));
            var publicationPlan = new DeploymentPublishPlan(UUID.randomUUID(), project, unit.id(), unit.server(),
                DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, ReviewedPlanIdentity.from(request),
                List.copyOf(intents), Instant.now().truncatedTo(ChronoUnit.MILLIS));
            var cleanupFacts = new java.util.concurrent.atomic.AtomicReference<DeploymentPublishCleanupActionLog>();
            var ready = new DeploymentRefFlow.PreparedPublish(publicationPlan,
                original -> author(plan, unit, originals, original, cleanupFacts, correction), () -> {
                boolean closed = false;
                try {
                    request.archive().close();
                } finally {
                    request.material().close();
                    closed = true;
                }
                if (closed && cleanupFacts.get() != null)
                    cleanupFacts.get().release();
            });
            handedOff = true;
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(ready), Optional.empty());
        } catch (SQLException knownReadFailure) {
            return failed("DEPLOYMENT_SECRET_METADATA_UNAVAILABLE");
        } catch (IllegalArgumentException invalid) {
            return failed("DEPLOYMENT_PREPARATION_INVALID");
        } finally {
            if (!handedOff) {
                try {
                    plan.request().archive().close();
                } finally {
                    plan.request().material().close();
                }
            }
        }
    }

    /** Performs one original borrowed-session candidate and release attempt. / 执行一次原借用会话候选及发布尝试。
     * @param plan open original reviewed plan / 开放的原已审阅计划
     * @param unit original owned target / 原所属目标
     * @param credentials exact saved credential map / 精确已保存凭据映射
     * @param task original task / 原任务
     * @param cleanupFacts original task's durable remote cleanup / 原任务持久远端清理依据
     * @param correction optional bounded typed build correction / 可选有界类型化构建纠正
     * @return verified publication or retained unknown / 已核实发布或保留未知 */
    private OperationResult<DeploymentPublish> author(ReviewedDeploymentPlan plan, DeploymentUnitIdentity unit,
                                                      Map<DeploymentSecretRef, SecretId> credentials, TaskContext task,
                                                      java.util.concurrent.atomic.AtomicReference<DeploymentPublishCleanupActionLog> cleanupFacts,
                                                      Optional<DeploymentAssistedBuildCorrection> correction) {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_CANCELLED_BEFORE_REMOTE",
                    "Publish cancelled before remote work", Map.of(), Optional.empty())));
        final gold.debug.windowstolinux.appapi.linux.session.RemoteSession session;
        try {
            session = context.linux().connect(unit.server(), task);
        } catch (ApiException denied) {
            if (denied.problem().code() == ProblemCode.ACCESS_DENIED)
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(denied.problem()));
            return unknown("DEPLOYMENT_PUBLICATION_SESSION_UNCONFIRMED");
        } catch (Exception | LinkageError | AssertionError uncertain) {
            return unknown("DEPLOYMENT_PUBLICATION_SESSION_UNCONFIRMED");
        }
        OperationResult<DeploymentPublish> result;
        try {
            var request = plan.request();
            var journal = DeploymentPublishCleanupActionLog.begin(context, task, unit, request.archive().sha256(),
                ReviewedPlanIdentity.from(request), request.runtime());
            cleanupFacts.set(journal);
            if (correction.isEmpty()) {
                var build = new DeploymentStandardCandidateRunner().execute(plan, session, context.interaction(), task);
                result = build.outcome() == TaskResult.SUCCEEDED
                    ? new DeploymentStandardPublishTransaction(context).publish(plan, unit,
                    build.value().orElseThrow(), credentials, session, task, journal)
                    : unknown("DEPLOYMENT_CANDIDATE_NOT_CONFIRMED");
            } else return assisted(plan, unit, credentials, task, session, journal, correction.orElseThrow());
        } catch (Exception | LinkageError | AssertionError uncertain) {
            result = unknown("DEPLOYMENT_PUBLICATION_SESSION_UNCONFIRMED");
        }
        try {
            session.close();
            if (cleanupFacts.get() != null
                && (result.outcome() == TaskResult.SUCCEEDED || result.outcome() == TaskResult.FAILED))
                cleanupFacts.get().sessionClosed();
        } catch (Exception | LinkageError | AssertionError uncertainCleanup) {
            if (result.outcome() != TaskResult.SUCCEEDED && result.outcome() != TaskResult.FAILED)
                return unknown("DEPLOYMENT_SESSION_RELEASE_UNCONFIRMED");
        }
        return result;
    }

    /** Uses one original candidate for bounded build-only corrections and exact final publication. / 有界仅构建纠正及精确最终发布共用一个原候选。
     * @param plan original real plan / 原真实计划
     * @param unit original exact owner / 原精确归属
     * @param credentials original exact credentials / 原精确凭据
     * @param task original task / 原任务
     * @param session original session / 原会话
     * @param journal original physical occupancy / 原物理占用
     * @param correction actual typed reviewer / 实际类型化审阅器
     * @return verified publication or retained original failure / 已核发布或保留原失败
     * @throws Exception if review or original persistence is uncertain / 审阅或原持久确认不明时 */
    private OperationResult<DeploymentPublish> assisted(ReviewedDeploymentPlan plan, DeploymentUnitIdentity unit,
                                                        Map<DeploymentSecretRef, SecretId> credentials, TaskContext task, gold.debug.windowstolinux.appapi.linux.session.RemoteSession session,
                                                        DeploymentPublishCleanupActionLog journal, DeploymentAssistedBuildCorrection correction) throws Exception {
        var candidates = new DeploymentStandardCandidateRunner();
        var loop = new DeploymentAssistedPublishBuild(context, candidates);
        var attempts = new DeploymentAssistedPublishAttempts(context, loop, candidates);
        var journals = Map.of(unit.id(), journal);
        try (var owned = new DeploymentPublishAuthorSession(session, journals, null)) {
            var prepared = candidates.prepare(plan, owned.current(), context.interaction(), task, loop.fallbackAvailable());
            if (prepared.outcome() != TaskResult.SUCCEEDED) {
                if (prepared.outcome() == TaskResult.UNKNOWN || prepared.outcome() == TaskResult.CANCELLED)
                    return unknown("DEPLOYMENT_ASSISTED_PREPARE_UNCONFIRMED");
                if (!journal.verifiedBusiness(TaskResult.FAILED, prepared.problem()) || !journal.remoteClean() || !owned.retire())
                    return unknown("DEPLOYMENT_ASSISTED_PREPARE_RESULT_UNCONFIRMED");
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(), prepared.problem());
            }
            if (!loop.prepared(prepared.value().orElseThrow()))
                return unknown("DEPLOYMENT_ASSISTED_RETRY_BUDGET_UNCONFIRMED");
            var selected = prepared.value().orElseThrow();
            while (true) {
                var completed = loop.build(selected, correction, unit, journal, null, () -> "", owned.current(), task);
                if (completed.result().outcome() != TaskResult.SUCCEEDED)
                    return assistedBuildFailure(completed, unit, task, journal, owned);
                plan = completed.plan();
                var result = new DeploymentStandardPublishTransaction(context).publish(plan, unit,
                    completed.result().value().orElseThrow(), credentials, owned.current(), task, journal);
                if (result.outcome() == TaskResult.UNKNOWN || result.outcome() == TaskResult.CANCELLED) return result;
                boolean retired = owned.retire();
                if (result.outcome() != TaskResult.FAILED || !retired || result.problem().isEmpty()
                    || !unit.id().equals(result.problem().orElseThrow().context().get("publicationHealthFailure")))
                    return result;
                var next = attempts.advance(Map.of(unit.id(), plan), Map.of(unit.id(), unit), Map.of(unit.id(), correction),
                    unit.id(), "", null, journals, result.problem().orElseThrow(), task);
                if (next.outcome() != TaskResult.SUCCEEDED)
                    return new OperationResult<>(next.outcome(), Optional.empty(), next.problem());
                var continuation = next.value().orElseThrow();
                owned.next(continuation.session());
                plan = continuation.plans().get(unit.id());
                selected = continuation.prepared().get(unit.id());
            }
        }
    }

    /** Cleans only an actually known pre-publication build failure. / 仅清理实际已知发布前构建失败。
     * @param completed actual build result / 实际构建结果
     * @param unit exact original owner / 精确原归属
     * @param task authentic original task / 真实原任务
     * @param journal original cleanup occupancy / 原清理占用
     * @param owned actual physical session owner / 实际物理会话所有者
     * @return known cleaned failure or retained unknown / 已知已清理失败或保留未知
     * @throws Exception if original physical close is unconfirmed / 原物理关闭未确认时 */
    private OperationResult<DeploymentPublish> assistedBuildFailure(DeploymentAssistedPublishBuild.Completed completed,
                                                                    DeploymentUnitIdentity unit, TaskContext task, DeploymentPublishCleanupActionLog journal,
                                                                    DeploymentPublishAuthorSession owned) throws Exception {
        if (completed.result().outcome() != TaskResult.FAILED)
            return unknown("DEPLOYMENT_ASSISTED_CANDIDATE_UNCONFIRMED");
        var channel = new gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublishChannel(owned.current(), context.interaction(), unit.server(), task, journal.facts().path("planSha256").asText());
        if (!journal.verifiedBusiness(TaskResult.FAILED, completed.result().problem())
            || !DeploymentPublishCleanupAttempt.run(journal, "CANDIDATE_CLEANUP_MAY_HAVE_RUN", () -> channel.cleanupCandidate(unit, journal.facts().path("candidate").asText()))
            || !journal.mark("CANDIDATE_CLEANED") || !journal.remoteClean() || !owned.retire())
            return unknown("DEPLOYMENT_ASSISTED_CANDIDATE_CLEANUP_UNCONFIRMED");
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), completed.result().problem());
    }

    /** Reports a known pre-publication metadata rejection. / 报告已知发布前元数据拒绝。
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static OperationResult<DeploymentRefFlow.PreparedPublish> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Reviewed publication preparation failed",
                Map.of(), Optional.empty())));
    }

    /** Retains a remote candidate whose cleanup cannot yet be established. / 保留尚不能证明清理的远端候选。
     * @param event stable event / 稳定事件
     * @return explicit unknown / 显式未知 */
    private static OperationResult<DeploymentPublish> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original remote work requires reconciliation", Map.of(), Optional.empty())));
    }
}

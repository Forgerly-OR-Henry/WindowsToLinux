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
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.DeploymentStandardCandidateExecutor;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedPlanIdentity;

/** Builds and publishes one reviewed candidate inside the same original reference-recoverable task. / 在同一原可恢复引用任务内构建并发布一个已审阅候选。 */
public final class DeploymentStandardPublicationExecutor {
    /** Original Mod activation. / 原Mod启用。 */
    private final ModContext context;

    /** Already registered original publication recovery. / 已登记的原发布恢复器。 */
    private final DeploymentReferenceFlow references;

    /** Builds and publishes one original task without owning the caller's reviewed-source callback. / 构建并发布一个原任务，不接管调用方已审阅来源回调。
     * @param context original active Mod / 原活动Mod
     * @param references actual registered publication flow / 实际已登记发布流程 */
    public DeploymentStandardPublicationExecutor(ModContext context, DeploymentReferenceFlow references) {
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
            Map<DeploymentSecretReference, SecretRef> credentials, TaskWork<ReviewedDeploymentPlan> prepareSource)
            throws ApiException {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(project);
        credentials = Map.copyOf(credentials);
        Objects.requireNonNull(prepareSource);
        Map<DeploymentSecretReference, SecretRef> originals = credentials;
        return references.submitPrepared(unit.server(), task -> prepare(task, unit, project, originals, prepareSource));
    }

    /** Binds the open source and actual saved credentials before the first publication reservation. / 在首次发布预留前绑定开放来源及实际已保存凭据。
     * @param task original task / 原任务
     * @param unit exact owned unit / 精确所属单元
     * @param project exact saved source / 精确已保存来源
     * @param credentials reviewed opaque credentials / 已审阅不透明凭据
     * @param prepareSource original source callback / 原来源回调
     * @return task-bound publication facts / 任务绑定发布事实
     * @throws Exception if original preparation fails / 原准备失败时 */
    private OperationResult<DeploymentReferenceFlow.PreparedPublication> prepare(TaskContext task,
            DeploymentUnitIdentity unit, Optional<DeploymentProjectRecord> project,
            Map<DeploymentSecretReference, SecretRef> credentials, TaskWork<ReviewedDeploymentPlan> prepareSource)
            throws Exception {
        var prepared = prepareSource.run(task);
        if (prepared.outcome() != Outcome.SUCCEEDED)
            return new OperationResult<>(prepared.outcome(), Optional.empty(), prepared.problem());
        var plan = prepared.value().orElseThrow();
        boolean handedOff = false;
        try {
            var request = plan.request();
            if (!unit.server().equals(request.server()) || !unit.id().equals(request.facts().applicationId())
                    || !credentials.keySet().equals(new java.util.HashSet<>(request.secretReferences())))
                throw new IllegalArgumentException("Publication target or secrets differ from reviewed source");
            if (project.isPresent()
                    && !project.orElseThrow().source().sha256().equals(request.material().snapshot().sha256()))
                throw new IllegalArgumentException("Saved project source differs from original frozen material");
            var repository = new DeploymentSecretRepository(context.db());
            for (var entry : credentials.entrySet()) {
                var actual = repository.findRevision(task, entry.getKey());
                if (actual.isEmpty() || !actual.orElseThrow().credentialId().equals(entry.getValue().id()))
                    throw new IllegalArgumentException("Secret revision differs from its stored credential");
            }
            var intents = new ArrayList<DeploymentReferenceIntent>();
            intents.add(DeploymentReferenceKey.application(unit.server(), unit.id()));
            intents.add(DeploymentReferenceKey.unit(unit.server(), unit.id()));
            credentials.forEach((revision, secret) -> intents
                    .add(DeploymentReferenceKey.secretRevision(unit.server(), secret, revision)));
            var publicationPlan = new DeploymentPublicationPlan(UUID.randomUUID(), project, unit.id(), unit.server(),
                    DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, ReviewedPlanIdentity.from(request),
                    List.copyOf(intents), Instant.now().truncatedTo(ChronoUnit.MILLIS));
            var cleanupFacts = new java.util.concurrent.atomic.AtomicReference<DeploymentPublicationCleanupJournal>();
            var ready = new DeploymentReferenceFlow.PreparedPublication(publicationPlan,
                    original -> author(plan, unit, credentials, original, cleanupFacts), () -> {
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
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(ready), Optional.empty());
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
     * @return verified publication or retained unknown / 已核实发布或保留未知 */
    private OperationResult<DeploymentPublication> author(ReviewedDeploymentPlan plan, DeploymentUnitIdentity unit,
            Map<DeploymentSecretReference, SecretRef> credentials, TaskContext task,
            java.util.concurrent.atomic.AtomicReference<DeploymentPublicationCleanupJournal> cleanupFacts) {
        if (task.cancellation().requested() || Thread.currentThread().isInterrupted())
            return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_CANCELLED_BEFORE_REMOTE",
                            "Publication cancelled before remote work", Map.of(), Optional.empty())));
        final gold.debug.windowstolinux.appapi.linux.RemoteSession session;
        try {
            session = context.linux().connect(unit.server(), task);
        } catch (Exception | LinkageError | AssertionError uncertain) {
            return unknown("DEPLOYMENT_PUBLICATION_SESSION_UNCONFIRMED");
        }
        OperationResult<DeploymentPublication> result;
        try {
            var request = plan.request();
            var journal = DeploymentPublicationCleanupJournal.begin(context, task, unit, request.archive().sha256(),
                    ReviewedPlanIdentity.from(request), request.runtime());
            cleanupFacts.set(journal);
            var build = new DeploymentStandardCandidateExecutor().execute(plan, session, context.interaction(), task);
            result = build.outcome() == Outcome.SUCCEEDED
                    ? new DeploymentStandardPublicationTransaction(context).publish(plan, unit,
                            build.value().orElseThrow(), credentials, session, task, journal)
                    : unknown("DEPLOYMENT_CANDIDATE_NOT_CONFIRMED");
        } catch (Exception | LinkageError | AssertionError uncertain) {
            result = unknown("DEPLOYMENT_PUBLICATION_SESSION_UNCONFIRMED");
        }
        try {
            session.close();
            if (cleanupFacts.get() != null
                    && (result.outcome() == Outcome.SUCCEEDED || result.outcome() == Outcome.FAILED))
                cleanupFacts.get().sessionClosed();
        } catch (Exception | LinkageError | AssertionError uncertainCleanup) {
            if (result.outcome() != Outcome.SUCCEEDED && result.outcome() != Outcome.FAILED)
                return unknown("DEPLOYMENT_SESSION_RELEASE_UNCONFIRMED");
        }
        return result;
    }

    /** Reports a known pre-publication metadata rejection. / 报告已知发布前元数据拒绝。
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static OperationResult<DeploymentReferenceFlow.PreparedPublication> failed(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Reviewed publication preparation failed",
                        Map.of(), Optional.empty())));
    }

    /** Retains a remote candidate whose cleanup cannot yet be established. / 保留尚不能证明清理的远端候选。
     * @param event stable event / 稳定事件
     * @return explicit unknown / 显式未知 */
    private static OperationResult<DeploymentPublication> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                event, "Original remote work requires reconciliation", Map.of(), Optional.empty())));
    }
}

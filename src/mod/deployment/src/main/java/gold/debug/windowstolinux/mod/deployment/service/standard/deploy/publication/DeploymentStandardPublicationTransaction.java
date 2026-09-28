package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentUnitCommit;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublication;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.DeploymentVerifiedBuildResult;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.DeploymentReviewedReleaseIdentity;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedPlanIdentity;

/** Runs one known single-component release and only rolls back known failures with its original snapshot. / 执行一个已知单组件发布，且仅以原快照回滚已知失败。 */
public final class DeploymentStandardPublicationTransaction {
    /** Actual host capabilities, never owned by this transaction. / 真实宿主能力，本事务不拥有其寿命。 */
    private final ModContext context;

    /** Binds the author to the original Mod generation. / 将作者绑定至原Mod代次。
     * @param context original activation / 原启用 */
    public DeploymentStandardPublicationTransaction(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Executes immutable input staging, snapshot, publication, health and known-failure recovery once. / 一次执行不可变输入暂存、快照、发布、健康及已知失败恢复。
     * @param plan original open reviewed source and release plan / 原开放已审阅来源与发布计划
     * @param unit exact managed owner / 精确受管归属
     * @param build confirmed original candidate / 已确认原候选
     * @param credentials exact persisted revision-to-credential mapping / 精确持久修订到凭据映射
     * @param session borrowed original connection / 借用原连接
     * @param task original admitted task / 原准入任务
     * @param journal original durable remote cleanup evidence / 原持久远端清理依据
     * @return verified business publication or explicit unknown / 已核实业务发布或显式未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前准入拒绝时 */
    public OperationResult<DeploymentPublication> publish(ReviewedDeploymentPlan plan, DeploymentUnitIdentity unit,
            DeploymentVerifiedBuildResult build, Map<DeploymentSecretReference, SecretRef> credentials,
            RemoteSession session, TaskContext task, DeploymentPublicationCleanupJournal journal) throws ApiException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(build);
        Objects.requireNonNull(session);
        Objects.requireNonNull(task);
        Objects.requireNonNull(journal);
        var request = plan.request();
        if (!unit.server().equals(request.server()) || !unit.id().equals(request.facts().applicationId())
                || !build.archiveSha256().equals(request.archive().sha256())
                || !credentials.keySet().equals(new java.util.HashSet<>(request.secretReferences())))
            throw new IllegalArgumentException("Publication differs from the original reviewed candidate");
        var channel = new DeploymentPublicationChannel(session, context.interaction(), request.server(), task,
                ReviewedPlanIdentity.from(request));
        var systemd = DeploymentPublicationArguments.configuration(request.configuration(), "systemd");
        var container = DeploymentPublicationArguments.configuration(request.configuration(), "container");
        boolean staged = false;
        try {
            for (String format : List.of("systemd", "container")) {
                var stage = channel.stageConfiguration(unit, request.configuration().sha256(), format,
                        format.equals("systemd") ? systemd : container);
                if (stage.outcome() != Outcome.SUCCEEDED) {
                    if (!staged && stage.outcome() == Outcome.FAILED && stage.value().isEmpty())
                        return new OperationResult<>(Outcome.FAILED, Optional.empty(), stage.problem());
                    return uncertain(stage, "DEPLOYMENT_CONFIGURATION_STAGE_UNCONFIRMED");
                }
                staged = true;
            }
        } finally {
            java.util.Arrays.fill(systemd, (byte) 0);
            java.util.Arrays.fill(container, (byte) 0);
        }
        var digests = new HashMap<DeploymentSecretReference, String>();
        for (var revision : request.secretReferences().stream()
                .sorted(Comparator.comparing(DeploymentSecretReference::identifier)
                        .thenComparingLong(DeploymentSecretReference::revision))
                .toList()) {
            SecretRef ref = credentials.get(revision);
            var stage = context.secrets().use(ref, ref.purpose(), task,
                    plaintext -> channel.stageSecret(unit, revision.identifier(), revision.revision(), plaintext));
            if (stage.outcome() != Outcome.SUCCEEDED)
                return uncertain(stage, "DEPLOYMENT_SECRET_STAGE_UNCONFIRMED");
            digests.put(revision, stage.value().orElseThrow());
        }
        var saved = channel.snapshot(unit, request.runtime());
        if (saved.outcome() != Outcome.SUCCEEDED)
            return uncertain(saved, "DEPLOYMENT_SNAPSHOT_UNCONFIRMED");
        var snapshot = saved.value().orElseThrow();
        if (!journal.snapshot(snapshot))
            return unknown("DEPLOYMENT_SNAPSHOT_EVIDENCE_UNCONFIRMED");
        String releaseSha256 = DeploymentReviewedReleaseIdentity.bind(DeploymentReviewedReleaseIdentity.from(request),
                build.toolchains());
        if (cancelled(task))
            return unknown("DEPLOYMENT_SNAPSHOT_RETAINED_AFTER_CANCEL");
        if (!journal.publishing(releaseSha256))
            return unknown("DEPLOYMENT_PUBLICATION_EVIDENCE_UNCONFIRMED");
        var published = channel.publish(unit, request.facts(), request.runtime(), request.resources(),
                request.configuration().sha256(), digests, build.candidateId(), releaseSha256,
                build.toolchainBindingSha256(), !build.toolchains().selections().isEmpty());
        if (published.outcome() == Outcome.UNKNOWN || published.outcome() == Outcome.CANCELLED)
            return uncertain(published, "DEPLOYMENT_PUBLISH_UNCONFIRMED");
        if (published.outcome() == Outcome.FAILED)
            return rollback(channel, unit, build.candidateId(), releaseSha256, snapshot, request.runtime(), task,
                    journal, published);
        journal.mark("PUBLISHED_VERIFIED");
        if (cancelled(task))
            return unknown("DEPLOYMENT_PUBLISHED_AFTER_CANCEL");
        var health = channel.health(unit, request.runtime());
        if (health.outcome() == Outcome.UNKNOWN || health.outcome() == Outcome.CANCELLED)
            return uncertain(health, "DEPLOYMENT_HEALTH_UNCONFIRMED");
        if (health.outcome() == Outcome.FAILED)
            return rollback(channel, unit, build.candidateId(), releaseSha256, snapshot, request.runtime(), task,
                    journal, health);
        var observed = channel.observe(unit, request.runtime());
        if (observed.outcome() != Outcome.SUCCEEDED)
            return uncertain(observed, "DEPLOYMENT_RUNTIME_UNCONFIRMED");
        var state = observed.value().orElseThrow().state();
        var expected = request.runtime().workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND
                ? DeploymentRemoteRuntimeObservation.StateType.INSTALLED
                : DeploymentRemoteRuntimeObservation.StateType.RUNNING;
        if (state != expected)
            return rollback(channel, unit, build.candidateId(), releaseSha256, snapshot, request.runtime(), task,
                    journal, failed("DEPLOYMENT_RUNTIME_UNHEALTHY"));
        var runtime = new DeploymentUnitRuntimeConfiguration(request.runtime().healthCheck(), request.userAccessUrl(),
                request.runtime().identityPolicy(), request.runtime().workload());
        var component = new DeploymentApplicationGraph.Component(unit.id(), unit, runtime, List.of(), request.runtime(),
                request.resources().fileBindings().stream().map(DeploymentFileBinding::dataPath).toList(),
                request.resources());
        var graph = new DeploymentApplicationGraph(unit.id(), unit.id(), request.runtime().healthCheck(),
                List.of(component));
        var commit = new DeploymentUnitCommit(unit, runtime,
                new DeploymentCurrentRelease(unit.id(), releaseSha256, Instant.now()), request.configuration(),
                request.secretReferences());
        finishKnown(channel, unit, build.candidateId(), releaseSha256, true, journal);
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new DeploymentPublication(graph, List.of(commit))),
                Optional.empty());
    }

    /** Recovers only after an explicitly known publication or health failure. / 仅在明确已知发布或健康失败后恢复。
     * @param channel original remote boundary / 原远端边界
     * @param unit exact owned unit / 精确所属单元
     * @param candidateId original task-owned candidate / 原任务所属候选
     * @param releaseSha256 attempted release / 尝试的发布摘要
     * @param snapshot original rollback snapshot / 原回滚快照
     * @param runtime attempted runtime kind / 尝试的运行时类别
     * @param task original task / 原任务
     * @param journal original durable cleanup evidence / 原持久清理依据
     * @param failure known original failure / 已知原失败
     * @return known failure only after verified recovery / 仅核实恢复后返回已知失败
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前准入拒绝时 */
    private static OperationResult<DeploymentPublication> rollback(DeploymentPublicationChannel channel,
            DeploymentUnitIdentity unit, String candidateId, String releaseSha256,
            DeploymentRemoteReleaseSnapshot snapshot, DeploymentRuntimeDefinition runtime, TaskContext task,
            DeploymentPublicationCleanupJournal journal, OperationResult<?> failure) throws ApiException {
        if (cancelled(task))
            return unknown("DEPLOYMENT_ROLLBACK_BLOCKED_BY_CANCEL");
        if (!journal.mark("ROLLBACK_MAY_HAVE_RUN"))
            return unknown("DEPLOYMENT_ROLLBACK_EVIDENCE_UNCONFIRMED");
        var restored = channel.rollback(unit, releaseSha256, snapshot, runtime);
        if (restored.outcome() != Outcome.SUCCEEDED)
            return uncertain(restored, "DEPLOYMENT_ROLLBACK_UNCONFIRMED");
        if (snapshot.token().isPresent()) {
            var observed = channel.observeRestored(unit);
            if (observed.outcome() != Outcome.SUCCEEDED)
                return uncertain(observed, "DEPLOYMENT_RESTORED_STATE_UNCONFIRMED");
            var state = observed.value().orElseThrow().state();
            if (snapshot.previousRunning() != (state == DeploymentRemoteRuntimeObservation.StateType.RUNNING)
                    || state == DeploymentRemoteRuntimeObservation.StateType.ERROR)
                return unknown("DEPLOYMENT_RESTORED_STATE_CHANGED");
        }
        journal.mark("ROLLBACK_VERIFIED");
        finishKnown(channel, unit, candidateId, releaseSha256, false, journal);
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                failure.problem().or(() -> Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_KNOWN_FAILURE",
                        "Reviewed release failed", Map.of(), Optional.empty()))));
    }

    /** Completes only verified business outcomes; an unconfirmed cleanup remains occupied for original recovery. / 仅收尾已核实业务结果；未确认清理仍占用以待原任务恢复。
     * @param channel original fixed helper channel / 原固定helper通道
     * @param unit exact owner / 精确归属
     * @param candidateId original task-owned candidate / 原任务所属候选
     * @param releaseSha256 exact attempted release / 精确尝试的发布
     * @param successful whether the new release was verified / 新发布是否已核实
     * @param journal durable pending cleanup / 持久待清理依据 */
    private static void finishKnown(DeploymentPublicationChannel channel, DeploymentUnitIdentity unit,
            String candidateId, String releaseSha256, boolean successful, DeploymentPublicationCleanupJournal journal) {
        try {
            if (!journal.mark("MAINTENANCE_END_MAY_HAVE_RUN")
                    || channel.endMaintenance(unit, releaseSha256).outcome() != Outcome.SUCCEEDED
                    || !journal.mark("MAINTENANCE_ENDED"))
                return;
            if (successful) {
                if (!journal.mark("RETAIN_MAY_HAVE_RUN") || channel.retain(unit).outcome() != Outcome.SUCCEEDED)
                    return;
                if (!journal.mark("RETAIN_VERIFIED"))
                    return;
            }
            if (!journal.mark("CANDIDATE_CLEANUP_MAY_HAVE_RUN")
                    || channel.cleanupCandidate(unit, candidateId).outcome() != Outcome.SUCCEEDED)
                return;
            if (!journal.mark("CANDIDATE_CLEANED"))
                return;
            journal.remoteClean();
        } catch (ApiException unconfirmedCleanup) {
            // The exact pending step remains occupied in the original task checkpoint.
        }
    }

    /** Treats any incomplete post-effect evidence as unresolved rather than falsely released. / 将副作用后不完整证据视为未决而非误报已释放。
     * @param observed exact stage observation / 精确阶段观测
     * @param event fallback stable event / 后备稳定事件
     * @return retained uncertainty / 保留未知 */
    private static OperationResult<DeploymentPublication> uncertain(OperationResult<?> observed, String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                observed.problem().filter(problem -> problem.code() == ProblemCode.UNKNOWN)
                        .or(() -> Optional.of(new Problem(ProblemCode.UNKNOWN, event, "Remote release may have changed",
                                Map.of(), Optional.empty()))));
    }

    /** Reports a verified failed service after observation. / 观测后报告已核实的失败服务。
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static OperationResult<Void> failed(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE,
                event, "Reviewed release did not reach its required state", Map.of(), Optional.empty())));
    }

    /** Preserves a cancelled original task after a remote effect. / 远端副作用后保留已取消原任务。
     * @param event stable event / 稳定事件
     * @return unknown outcome / 未知结果 */
    private static OperationResult<DeploymentPublication> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                event, "Remote publication requires original-task reconciliation", Map.of(), Optional.empty())));
    }

    /** Checks original cooperative cancellation. / 检查原协作取消。
     * @param task original task / 原任务
     * @return whether cancellation is requested / 是否已请求取消 */
    private static boolean cancelled(TaskContext task) {
        return task.cancellation().requested() || Thread.currentThread().isInterrupted();
    }
}

package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublish;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.DeploymentVerifiedBuildResult;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.DeploymentReviewedReleaseIdentity;

/** Publishes one already-built reviewed graph as a single original transaction. / 将已构建的已审阅图作为单个原事务发布。 */
final class DeploymentMultiPartPublishTransaction {
    /** Original activation and database. / 原启用及数据库。 */
    private final ModContext context;

    /** Binds the actual original Mod activation. / 绑定实际原Mod启用。
     * @param context actual activation / 实际启用 */
    DeploymentMultiPartPublishTransaction(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Runs all stages before snapshots, then inverse stop, topological publish and whole-graph verification. / 先全部封存，再逆序快照停旧、拓扑发布和整图核验。
     * @param ordering admitted dependency graph / 已准入依赖图
     * @param plans exact original reviewed components / 精确原审阅组件
     * @param units exact owners / 精确归属
     * @param credentials original saved credentials / 原已存凭据
     * @param builds all confirmed candidates / 全部已确认候选
     * @param healthComponent original whole-health owner / 原整图健康归属
     * @param health original whole-health check / 原整图健康检查
     * @param session borrowed original SSH connection / 借用原SSH连接
     * @param task original task / 原任务
     * @param root original graph checkpoint / 原图检查点
     * @param journals original indexed component checkpoints / 原索引组件检查点
     * @return one verified business graph or retained uncertainty / 一个已核实业务图或保留未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    OperationResult<DeploymentPublish> publish(MultiPartDeploymentPlan ordering,
                                               Map<String, ReviewedDeploymentPlan> plans, Map<String, DeploymentUnitIdentity> units,
                                               Map<DeploymentSecretRef, SecretId> credentials, Map<String, DeploymentVerifiedBuildResult> builds,
                                               String healthComponent, DeploymentHealthCheck health, RemoteSession session, TaskContext task,
                                               DeploymentGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals)
        throws ApiException {
        if (!ordering.startOrder().equals(List.copyOf(builds.keySet()))
            && !builds.keySet().equals(new HashSet<>(ordering.startOrder())))
            throw new IllegalArgumentException("Complete graph builds are required");
        var channels = new HashMap<String, DeploymentPublishChannel>();
        for (var id : ordering.startOrder())
            if (!DeploymentPublishOperationBinding.confirmed(context, task, builds.get(id), journals.get(id)))
                return unknown("DEPLOYMENT_GRAPH_OPERATION_UNCONFIRMED");
        for (var id : ordering.startOrder())
            channels.put(id, new DeploymentPublishChannel(session, context.interaction(), units.get(id).server(),
                task, journals.get(id).facts().path("planSha256").asText()));
        var secrets = new HashMap<String, Map<DeploymentSecretRef, String>>();
        String stagingFailure = stageAll(ordering, plans, units, credentials, channels, task, root, secrets);
        if (stagingFailure != null)
            return unknown(stagingFailure);
        var snapshots = new HashMap<String, DeploymentRemoteReleaseSnapshot>();
        String snapshotFailure = snapshotAll(ordering, plans, units, builds, channels, task, root, journals, snapshots);
        if (snapshotFailure != null)
            return unknown(snapshotFailure);
        var publication = proposal(ordering, plans, units, builds, healthComponent, health);
        for (var id : ordering.startOrder())
            if (!journals.get(id).plannedRelease(release(builds.get(id), plans.get(id))))
                return unknown("DEPLOYMENT_GRAPH_RELEASE_IDENTITY_UNCONFIRMED");
        if (!persistProposal(task, publication))
            return unknown("DEPLOYMENT_GRAPH_TEMPLATE_UNCONFIRMED");
        var affected = new HashSet<String>();
        for (var id : ordering.stopOrder()) {
            var snapshot = snapshots.get(id);
            if (snapshot.token().isEmpty() || !snapshot.previousRunning())
                continue;
            if (cancelled(task) || !journals.get(id).mark("STOP_MAY_HAVE_RUN"))
                return unknown("DEPLOYMENT_GRAPH_STOP_BOUNDARY_UNCONFIRMED");
            affected.add(id);
            var stopped = channels.get(id).stopPrevious(units.get(id), snapshot);
            if (stopped.outcome() == TaskResult.UNKNOWN || stopped.outcome() == TaskResult.CANCELLED)
                return unknown("DEPLOYMENT_GRAPH_STOP_UNCONFIRMED");
            if (stopped.outcome() == TaskResult.FAILED)
                return rollback(ordering, units, plans, builds, snapshots, channels, journals, root, affected, stopped
                    .problem().orElseGet(() -> problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_GRAPH_STOP_FAILED")));
            if (!journals.get(id).mark("STOP_VERIFIED"))
                return unknown("DEPLOYMENT_GRAPH_STOP_EVIDENCE_UNCONFIRMED");
        }
        if (!root.mark("PUBLISHING"))
            return unknown("DEPLOYMENT_GRAPH_PUBLISH_BOUNDARY_UNCONFIRMED");
        for (var id : ordering.startOrder()) {
            var plan = plans.get(id);
            var request = plan.request();
            var build = builds.get(id);
            var release = release(build, plan);
            if (cancelled(task) || !journals.get(id).publishing(release))
                return unknown("DEPLOYMENT_GRAPH_PUBLISH_BOUNDARY_UNCONFIRMED");
            affected.add(id);
            gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.progress(context, task,
                "Publishing a verified original graph component / 发布已核实原图组件");
            var published = channels.get(id).publish(units.get(id), request.facts(), request.runtime(),
                request.resources(), request.configuration().sha256(), secrets.get(id), build.candidateId(),
                release, build.toolchainBindingSha256(), !build.toolchains().selections().isEmpty());
            if (published.outcome() == TaskResult.UNKNOWN || published.outcome() == TaskResult.CANCELLED)
                return unknown("DEPLOYMENT_GRAPH_PUBLISH_UNCONFIRMED");
            if (published.outcome() == TaskResult.FAILED)
                return rollback(ordering, units, plans, builds, snapshots, channels, journals, root, affected, published
                    .problem().orElseGet(() -> problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_GRAPH_PUBLISH_FAILED")));
            if (!journals.get(id).mark("PUBLISHED_VERIFIED"))
                return unknown("DEPLOYMENT_GRAPH_PUBLISH_EVIDENCE_UNCONFIRMED");
            var immediate = healthy(id, plans, units, channels);
            if (immediate.outcome() == TaskResult.UNKNOWN || immediate.outcome() == TaskResult.CANCELLED)
                return unknown("DEPLOYMENT_GRAPH_HEALTH_UNCONFIRMED");
            if (immediate.outcome() == TaskResult.FAILED)
                return rollback(ordering, units, plans, builds, snapshots, channels, journals, root, affected,
                    DeploymentStandardPublishTransaction.healthFailure(immediate, units.get(id).id()).problem().orElseGet(
                        () -> problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_GRAPH_COMPONENT_UNHEALTHY")));
        }
        for (var id : ordering.healthOrder()) {
            var repeated = healthy(id, plans, units, channels);
            if (repeated.outcome() == TaskResult.UNKNOWN || repeated.outcome() == TaskResult.CANCELLED)
                return unknown("DEPLOYMENT_GRAPH_RECHECK_UNCONFIRMED");
            if (repeated.outcome() == TaskResult.FAILED)
                return rollback(ordering, units, plans, builds, snapshots, channels, journals, root, affected,
                    DeploymentStandardPublishTransaction.healthFailure(repeated, units.get(id).id()).problem()
                        .orElseGet(() -> problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_GRAPH_RECHECK_FAILED")));
        }
        return completeWholeGraph(ordering, units, plans, builds, snapshots, channels, journals, root, affected,
            healthComponent, health, task, publication);
    }

    /** Completes the whole-application health gate before retaining success and cleaning original candidates. / 保留成功与清理原候选前完成整应用健康门槛。
     * @param ordering original dependency ordering / 原依赖顺序
     * @param units original exact owners / 原精确归属
     * @param plans original reviewed plans / 原已审阅计划
     * @param builds verified original candidates / 已核实原候选
     * @param snapshots original rollback snapshots / 原回滚快照
     * @param channels borrowed original channels / 借用原通道
     * @param journals indexed original checkpoints / 索引原检查点
     * @param root original whole-graph checkpoint / 原整图检查点
     * @param affected actually affected original components / 实际受影响原组件
     * @param healthComponent original health owner / 原健康归属
     * @param health reviewed whole-graph gate / 已审阅整图门槛
     * @param task original admitted task / 原已准入任务
     * @param publication original complete proposal / 原完整提案
     * @return verified publication or original rollback outcome / 已核实发布或原回滚结果
     * @throws ApiException if original admission is rejected / 原准入被拒绝时 */
    private OperationResult<DeploymentPublish> completeWholeGraph(MultiPartDeploymentPlan ordering,
                                                                  Map<String, DeploymentUnitIdentity> units,
                                                                  Map<String, ReviewedDeploymentPlan> plans,
                                                                  Map<String, DeploymentVerifiedBuildResult> builds,
                                                                  Map<String, DeploymentRemoteReleaseSnapshot> snapshots,
                                                                  Map<String, DeploymentPublishChannel> channels,
                                                                  Map<String, DeploymentPublishCleanupActionLog> journals,
                                                                  DeploymentGraphPublishActionLog root, java.util.Set<String> affected,
                                                                  String healthComponent, DeploymentHealthCheck health,
                                                                  TaskContext task, DeploymentPublish publication) throws ApiException {
        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.progress(context, task,
            "Verifying the original whole-application health gate / 核验原整应用健康门槛");
        var whole = channels.get(healthComponent).healthCheck(units.get(healthComponent), health);
        if (whole.outcome() == TaskResult.UNKNOWN || whole.outcome() == TaskResult.CANCELLED)
            return unknown("DEPLOYMENT_GRAPH_HEALTH_GATE_UNCONFIRMED");
        if (whole.outcome() == TaskResult.FAILED)
            return rollback(ordering, units, plans, builds, snapshots, channels, journals, root, affected,
                DeploymentStandardPublishTransaction.healthFailure(whole, units.get(healthComponent).id()).problem()
                    .orElseGet(() -> problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_GRAPH_HEALTH_GATE_FAILED")));
        if (!root.verified(TaskResult.SUCCEEDED, Optional.empty()))
            return unknown("DEPLOYMENT_GRAPH_VERIFIED_RESULT_UNCONFIRMED");
        finishKnown(ordering, units, builds, channels, journals, root, true, affected);
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(publication), Optional.empty());
    }

    /** Seals all component inputs before any old runtime is stopped. / 停止旧运行前封存全部组件输入。
     * @param ordering original graph / 原图
     * @param plans reviewed plans / 已审阅计划
     * @param units exact owners / 精确归属
     * @param credentials saved opaque credentials / 已存不透明凭据
     * @param channels original channels / 原通道
     * @param task original task / 原任务
     * @param root original graph journal / 原图账本
     * @param secrets confirmed staged secret references / 已确认封存的秘密引用
     * @return absent on success or bounded failure event / 成功为空，否则为有界失败事件
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private String stageAll(MultiPartDeploymentPlan ordering, Map<String, ReviewedDeploymentPlan> plans,
                            Map<String, DeploymentUnitIdentity> units, Map<DeploymentSecretRef, SecretId> credentials,
                            Map<String, DeploymentPublishChannel> channels, TaskContext task,
                            DeploymentGraphPublishActionLog root, Map<String, Map<DeploymentSecretRef, String>> secrets)
        throws ApiException {
        for (var id : ordering.startOrder()) {
            if (cancelled(task))
                return "DEPLOYMENT_GRAPH_STAGE_CANCELLED";
            var staged = DeploymentPublishStaging.stage(context, plans.get(id), units.get(id), credentials,
                channels.get(id), task);
            if (staged.outcome() != TaskResult.SUCCEEDED)
                return "DEPLOYMENT_GRAPH_INPUT_STAGE_UNCONFIRMED";
            secrets.put(id, staged.value().orElseThrow());
        }
        return root.mark("INPUTS_STAGED") ? null : "DEPLOYMENT_GRAPH_INPUT_BOUNDARY_UNCONFIRMED";
    }

    /** Saves all old snapshots in inverse order before one publication write. / 任一发布写入前逆序保存全部旧快照。
     * @param ordering original graph / 原图
     * @param plans reviewed plans / 已审阅计划
     * @param units exact owners / 精确归属
     * @param builds actual confirmed operation candidates / 实际已确认操作候选
     * @param channels original channels / 原通道
     * @param task original task / 原任务
     * @param root original graph journal / 原图账本
     * @param journals indexed original checkpoints / 索引原检查点
     * @param snapshots confirmed original snapshots / 已确认原快照
     * @return absent on success or bounded failure event / 成功为空，否则为有界失败事件
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private static String snapshotAll(MultiPartDeploymentPlan ordering, Map<String, ReviewedDeploymentPlan> plans,
                                      Map<String, DeploymentUnitIdentity> units, Map<String, DeploymentVerifiedBuildResult> builds,
                                      Map<String, DeploymentPublishChannel> channels,
                                      TaskContext task, DeploymentGraphPublishActionLog root,
                                      Map<String, DeploymentPublishCleanupActionLog> journals,
                                      Map<String, DeploymentRemoteReleaseSnapshot> snapshots) throws ApiException {
        for (var id : ordering.rollbackOrder()) {
            if (cancelled(task) || !journals.get(id).mark("SNAPSHOT_MAY_HAVE_RUN"))
                return "DEPLOYMENT_GRAPH_SNAPSHOT_BOUNDARY_UNCONFIRMED";
            var build = builds.get(id);
            var facts = journals.get(id).facts();
            var saved = build.operation().isEmpty() ? channels.get(id).snapshot(units.get(id), plans.get(id).request().runtime())
                : channels.get(id).snapshot(units.get(id), plans.get(id).request().runtime(), build.operation().orElseThrow(),
                facts.path("expectedDigest").asText().isEmpty() ? Optional.empty() : Optional.of(facts.path("expectedDigest").asText()),
                facts.path("expectedKind").asText(), facts.path("expectedRunning").booleanValue());
            if (saved.outcome() != TaskResult.SUCCEEDED || saved.value().isEmpty())
                return "DEPLOYMENT_GRAPH_SNAPSHOT_UNCONFIRMED";
            var snapshot = saved.value().orElseThrow();
            if (!journals.get(id).snapshot(snapshot))
                return "DEPLOYMENT_GRAPH_SNAPSHOT_EVIDENCE_UNCONFIRMED";
            snapshots.put(id, snapshot);
        }
        return root.mark("SNAPSHOTS_VERIFIED") ? null : "DEPLOYMENT_GRAPH_SNAPSHOTS_UNCONFIRMED";
    }

    /** Saves one complete non-secret graph before a publish or stop command. / 任一发布或停止命令前保存一份完整非秘密图。
     * @param task original task / 原任务
     * @param publication complete original business proposal / 完整原业务提案
     * @return whether the original request update was confirmed / 原请求更新是否确认 */
    private boolean persistProposal(TaskContext task, DeploymentPublish publication) {
        try {
            var executions = new DeploymentExecutionStore(context.db());
            var execution = executions.findForTask(task).orElseThrow();
            executions.recordPublicationTemplate(task, execution.identity().executionId(),
                new DeploymentPublishProposal(publication.graph(), publication.units()), Instant.now());
            return true;
        } catch (SQLException | ApiException | java.util.NoSuchElementException unconfirmed) {
            return false;
        }
    }

    /** Checks one reviewed component after publish and on whole-graph recheck. / 发布后及整图复查时检查一个已审阅组件。
     * @param id component ID / 组件ID
     * @param plans original plans / 原计划
     * @param units exact owners / 精确归属
     * @param channels original channels / 原通道
     * @return known healthy, known failed, or unknown / 已知健康、已知失败或未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private static OperationResult<Void> healthy(String id, Map<String, ReviewedDeploymentPlan> plans,
                                                 Map<String, DeploymentUnitIdentity> units, Map<String, DeploymentPublishChannel> channels)
        throws ApiException {
        var runtime = plans.get(id).request().runtime();
        var health = channels.get(id).healthCheck(units.get(id), runtime.healthCheck());
        if (health.outcome() != TaskResult.SUCCEEDED)
            return health;
        var observed = channels.get(id).observe(units.get(id), runtime);
        if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
            return unknownVoid("DEPLOYMENT_GRAPH_RUNTIME_UNCONFIRMED");
        var expected = runtime.workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND
            ? DeploymentRemoteRuntimeObservation.StateType.INSTALLED
            : DeploymentRemoteRuntimeObservation.StateType.RUNNING;
        return observed.value().orElseThrow().state() == expected
            ? new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty())
            : new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_GRAPH_RUNTIME_UNHEALTHY")));
    }

    /** Restores every possibly touched old runtime in inverse dependency order after only a known failure. / 仅在已知失败后按逆依赖顺序恢复每个可能受影响的旧运行时。
     * @param ordering original graph / 原图
     * @param units exact owners / 精确归属
     * @param plans reviewed runtimes / 已审阅运行定义
     * @param builds original candidates / 原候选
     * @param snapshots durable old snapshots / 持久旧快照
     * @param channels original commands / 原命令
     * @param journals component checkpoints / 组件检查点
     * @param root graph checkpoint / 图检查点
     * @param affected potentially touched components / 可能受影响组件
     * @param failure actual known failure / 实际已知失败
     * @return failed only after all old states are verified / 仅全部旧状态核实后返回失败
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private static OperationResult<DeploymentPublish> rollback(MultiPartDeploymentPlan ordering,
                                                               Map<String, DeploymentUnitIdentity> units, Map<String, ReviewedDeploymentPlan> plans,
                                                               Map<String, DeploymentVerifiedBuildResult> builds, Map<String, DeploymentRemoteReleaseSnapshot> snapshots,
                                                               Map<String, DeploymentPublishChannel> channels,
                                                               Map<String, DeploymentPublishCleanupActionLog> journals, DeploymentGraphPublishActionLog root,
                                                               java.util.Set<String> affected, Problem failure) throws ApiException {
        if (!root.mark("ROLLING_BACK"))
            return unknown("DEPLOYMENT_GRAPH_ROLLBACK_BOUNDARY_UNCONFIRMED");
        boolean manualRecovery = false;
        for (var id : ordering.rollbackOrder()) {
            if (!affected.contains(id))
                continue;
            var journal = journals.get(id);
            if (!journal.rollbackStarting(failure))
                return unknown("DEPLOYMENT_GRAPH_ROLLBACK_EVIDENCE_UNCONFIRMED");
            var restored = channels.get(id).rollback(units.get(id), release(builds.get(id), plans.get(id)),
                snapshots.get(id), plans.get(id).request().runtime());
            if (restored.outcome() == TaskResult.FAILED) {
                manualRecovery = true;
                continue;
            }
            if (restored.outcome() != TaskResult.SUCCEEDED)
                return unknown("DEPLOYMENT_GRAPH_ROLLBACK_UNCONFIRMED");
            var snapshot = snapshots.get(id);
            if (snapshot.token().isPresent()) {
                var observed = channels.get(id).observeRestored(units.get(id));
                if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
                    return unknown("DEPLOYMENT_GRAPH_RESTORED_STATE_UNCONFIRMED");
                if (observed.value().orElseThrow().state() == DeploymentRemoteRuntimeObservation.StateType.ERROR
                    || snapshot.previousRunning() != (observed.value().orElseThrow()
                    .state() == DeploymentRemoteRuntimeObservation.StateType.RUNNING))
                    return unknown("DEPLOYMENT_GRAPH_RESTORED_STATE_UNCONFIRMED");
            }
            if (!journal.mark("ROLLBACK_VERIFIED"))
                return unknown("DEPLOYMENT_GRAPH_ROLLBACK_RECORD_UNCONFIRMED");
        }
        if (manualRecovery)
            return unknown("DEPLOYMENT_GRAPH_MANUAL_ROLLBACK_REQUIRED");
        if (!root.verified(TaskResult.FAILED, Optional.of(failure)))
            return unknown("DEPLOYMENT_GRAPH_VERIFIED_ROLLBACK_UNCONFIRMED");
        finishKnown(ordering, units, builds, channels, journals, root, false, affected);
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(failure));
    }

    /** Closes only verified remote cleanup; uncertain steps keep original checkpoints occupied. / 仅关闭已核实远端收尾；未决步骤保持原检查点占用。
     * @param ordering original graph / 原图
     * @param units exact owners / 精确归属
     * @param builds original candidates / 原候选
     * @param channels original channels / 原通道
     * @param journals original components / 原组件
     * @param root graph checkpoint / 图检查点
     * @param successful whether new releases are retained / 是否留存新发布
     * @param affected components actually stopped or published / 实际停止或发布的组件 */
    private static void finishKnown(MultiPartDeploymentPlan ordering, Map<String, DeploymentUnitIdentity> units,
                                    Map<String, DeploymentVerifiedBuildResult> builds, Map<String, DeploymentPublishChannel> channels,
                                    Map<String, DeploymentPublishCleanupActionLog> journals, DeploymentGraphPublishActionLog root,
                                    boolean successful, java.util.Set<String> affected) {
        try {
            for (var id : ordering.startOrder()) {
                var journal = journals.get(id);
                if (!successful && !affected.contains(id)) {
                    if (!DeploymentPublishCleanupAttempt.run(journal, "SNAPSHOT_DISCARD_MAY_HAVE_RUN",
                        () -> builds.get(id).operation().isEmpty() ? channels.get(id).discardSnapshot(units.get(id))
                            : channels.get(id).discardSnapshot(units.get(id), builds.get(id).operation().orElseThrow()))
                        || !journal.mark("SNAPSHOT_DISCARDED"))
                        return;
                    continue;
                }
                if (!DeploymentPublishCleanupAttempt
                    .run(journal, "MAINTENANCE_END_MAY_HAVE_RUN",
                        () -> channels.get(id).endMaintenance(units.get(id),
                            journal.facts().path("releaseSha256").asText()))
                    || !journal.mark("MAINTENANCE_ENDED"))
                    return;
            }
            if (successful)
                for (var id : ordering.startOrder()) {
                    var journal = journals.get(id);
                    if (!DeploymentPublishCleanupAttempt.run(journal, "RETAIN_MAY_HAVE_RUN",
                        () -> channels.get(id).retain(units.get(id))) || !journal.mark("RETAIN_VERIFIED"))
                        return;
                }
            for (var id : ordering.startOrder()) {
                var journal = journals.get(id);
                if (!DeploymentPublishCleanupAttempt.run(journal, "CANDIDATE_CLEANUP_MAY_HAVE_RUN",
                    () -> channels.get(id).cleanupCandidate(units.get(id), builds.get(id).candidateId()))
                    || !journal.mark("CANDIDATE_CLEANED") || !journal.remoteClean())
                    return;
            }
            root.mark("REMOTE_CLEANUP_VERIFIED");
        } catch (ApiException unconfirmed) {
            // The original indexed checkpoint retains the exact pending step.
        }
    }

    /** Builds one complete immutable business proposal from exact verified candidates. / 从精确已核实候选构造一份完整不可变业务提案。
     * @param ordering reviewed graph / 已审阅图
     * @param plans original component plans / 原组件计划
     * @param units exact owners / 精确归属
     * @param builds all verified builds / 全部已核实构建
     * @param healthComponent whole-health owner / 整图健康归属
     * @param health independent whole-health check / 独立整图健康检查
     * @return complete publication / 完整发布 */
    private static DeploymentPublish proposal(MultiPartDeploymentPlan ordering,
                                              Map<String, ReviewedDeploymentPlan> plans, Map<String, DeploymentUnitIdentity> units,
                                              Map<String, DeploymentVerifiedBuildResult> builds, String healthComponent, DeploymentHealthCheck health) {
        var components = new ArrayList<DeploymentApplicationGraph.Part>();
        var commits = new ArrayList<DeploymentUnitCommit>();
        var time = Instant.now();
        for (var id : ordering.startOrder()) {
            var request = plans.get(id).request();
            var unit = units.get(id);
            var runtime = new DeploymentUnitRuntimeConfig(request.runtime().healthCheck(),
                request.userAccessUrl(), request.runtime().identityPolicy(), request.runtime().workload());
            components.add(new DeploymentApplicationGraph.Part(id, unit, runtime, ordering.dependencies().get(id),
                request.runtime(),
                request.resources().fileBindings().stream().map(DeploymentFileBinding::dataPath).toList(),
                request.resources()));
            commits.add(new DeploymentUnitCommit(unit, runtime,
                new DeploymentCurrentRelease(unit.id(), release(builds.get(id), plans.get(id)), time),
                request.configuration(), request.secretReferences()));
        }
        return new DeploymentPublish(
            new DeploymentApplicationGraph(ordering.applicationId(), healthComponent, health, components), commits);
    }

    /** Derives the old content-based release domain independently of plan/task identity. / 独立于计划及任务身份派生旧内容发布域。
     * @param build verified installed tools / 已核实安装工具
     * @param plan reviewed content / 已审阅内容
     * @return exact release digest / 精确发布摘要 */
    private static String release(DeploymentVerifiedBuildResult build, ReviewedDeploymentPlan plan) {
        return DeploymentReviewedReleaseIdentity.bind(DeploymentReviewedReleaseIdentity.from(plan.request()),
            build.toolchains());
    }

    /** Detects original cooperative cancellation without inventing another task. / 检测原协作取消且不伪造另一任务。
     * @param task original task / 原任务
     * @return whether cancelled / 是否取消 */
    private static boolean cancelled(TaskContext task) {
        return task.cancelToken().requested() || Thread.currentThread().isInterrupted();
    }

    /** Creates one safe bounded failure. / 创建安全有界失败。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return public failure / 公开失败 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Whole reviewed graph could not be confirmed", Map.of(), Optional.empty());
    }

    /** Retains a remote graph whose actual state is not fully known. / 保留实际状态尚不完整的远端图。
     * @param event stable event / 稳定事件
     * @return explicit unknown / 显式未知 */
    private static OperationResult<DeploymentPublish> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Returns uncertainty for a component health observation. / 返回组件健康观察的不确定性。
     * @param event stable event / 稳定事件
     * @return unknown component result / 未知组件结果 */
    private static OperationResult<Void> unknownVoid(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }
}

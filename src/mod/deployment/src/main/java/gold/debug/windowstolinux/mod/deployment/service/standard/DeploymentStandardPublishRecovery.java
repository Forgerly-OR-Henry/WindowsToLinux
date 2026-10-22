package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRemoteRecovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedReviewRecovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.DeploymentPublishRecoveryFacts;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.attempt.DeploymentPublishAttemptRecovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishRecovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyContext;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.single.DeploymentStandardSinglePublishRecovery;

/** Reconciles one original remote publication using retained facts and read-only helper observations. / 仅使用留存事实及只读helper观测核对一项原远端发布。 */
public final class DeploymentStandardPublishRecovery implements DeploymentRemoteRecovery {
    /** Original Mod capabilities; no path, source selection or replacement task is reconstructed. / 原Mod能力；不重建路径、来源选择或替代任务。 */
    private final ModContext context;

    /** Binds the same activation used for the reference recovery callback. / 绑定引用恢复回调所用的同一启用。
     * @param context actual activation / 实际启用 */
    public DeploymentStandardPublishRecovery(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Observes original owner, release, snapshot and cleanup without replaying a build or publish. / 不重放构建或发布，仅观察原归属、发布、快照及收尾。
     * @param task authentic original recovery task / 真实原恢复任务
     * @param record retained execution / 留存执行
     * @return absent for reference-only work, otherwise verified or uncertain remote result / 纯引用任务为空，否则返回已核实或未决远端结果 */
    @Override
    public Optional<OperationResult<DeploymentPublishProposal>> reconcile(TaskContext task,
                                                                          DeploymentExecutionRecord record) {
        if (context instanceof DeploymentPublishPolicyContext) return reconcileOriginal(task, record);
        try {
            var guarded = DeploymentPublishPolicyContext.recover(context, task, new DeploymentStandardAssembly());
            try {
                return new DeploymentStandardPublishRecovery(guarded).reconcileOriginal(task, record);
            } finally {
                guarded.finish(TaskResult.UNKNOWN);
            }
        } catch (ApiException | IllegalArgumentException unavailable) {
            return Optional.of(unknown("DEPLOYMENT_PUBLICATION_POLICY_UNAVAILABLE"));
        }
    }

    /** Checks original effect pointers before opening any helper recovery connection or fresh authority. / 打开任一 helper 恢复连接或新权限前核对原效果指针。
     * @param context actual original owner / 实际原所有者
     * @param task authentic original recovery / 真实原恢复
     * @return whether original receipts permit observation / 原回执是否允许观察 */
    public static boolean permitsOriginalRecovery(ModContext context, TaskContext task) {
        try {
            var original = new DeploymentExecutionStore(context.db()).findForTask(task);
            if (original.isEmpty()) return true;
            var record = original.orElseThrow();
            var graph = DeploymentStandardGraphPublishActionLog.reopen(context, task);
            if (graph.isPresent()) {
                var root = graph.orElseThrow();
                for (var index : root.indexes()) {
                    var child = DeploymentPublishCleanupActionLog.reopen(context, task, index.key());
                    if (child.isEmpty() || !confirmChildPointers(context, task, record, child.orElseThrow()))
                        return false;
                }
                if (!DeploymentPublishAttemptRecovery.root(context, task, record, root)
                    || !DeploymentAssistedReviewRecovery.root(context, task, record, root)) return false;
                for (var index : root.indexes()) {
                    var child = DeploymentPublishCleanupActionLog.reopen(context, task, index.key());
                    if (child.isEmpty()) return false;
                    DeploymentPublishRecoveryFacts.read(child.orElseThrow().facts(), record, index.unitId(), index.planSha256(),
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
                }
                var facts = root.facts();
                return record.identity().applicationId().equals(facts.path("application").asText())
                    && record.identity().server().id().equals(facts.path("server").asText())
                    && record.identity().reviewedRevision().equals(facts.path("planSha256").asText());
            }
            var child = DeploymentPublishCleanupActionLog.reopen(context, task);
            return child.isEmpty() || permitsChild(context, task, record, child.orElseThrow(), record.identity().applicationId(), record.identity().reviewedRevision());
        } catch (Exception | LinkageError | AssertionError unavailable) {
            return false;
        }
    }

    /** Validates one original candidate before any model or remote activity. / 任一模型或远端活动前校验一项原候选。
     * @param context original owner / 原所有者
     * @param task original task / 原任务
     * @param record formal original execution / 正式原执行
     * @param journal original child / 原子项
     * @param unit original unit / 原单元
     * @param plan original plan / 原计划
     * @return whether observation remains admissible / 观察是否仍可准入
     * @throws Exception if original durable evidence is unavailable / 原持久依据不可用时 */
    private static boolean permitsChild(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                                        DeploymentPublishCleanupActionLog journal, String unit, String plan) throws Exception {
        if (!confirmChildPointers(context, task, record, journal)) return false;
        DeploymentPublishRecoveryFacts.read(journal.facts(), record, unit, plan,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
        return true;
    }

    /** Confirms original pointers before reading their consumed identities. / 读取已消费身份前确认原指针。
     * @param context original owner / 原所有者
     * @param task original task / 原任务
     * @param record formal original execution / 正式原执行
     * @param journal original child / 原子项
     * @return whether the original pointer chain is confirmed / 原指针链是否已确认
     * @throws Exception if original durable evidence is unavailable / 原持久依据不可用时 */
    private static boolean confirmChildPointers(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                                                DeploymentPublishCleanupActionLog journal) throws Exception {
        return DeploymentPublishAttemptRecovery.child(context, task, record, journal)
            && DeploymentAssistedReviewRecovery.child(context, task, record, journal);
    }

    /** Requires every original remote cleanup to have a closed-session boundary before policy release. / 策略释放前要求每份原远端清理已有会话关闭边界。
     * @param context original owner / 原所有者
     * @param task original task / 原任务
     * @return whether original remote occupancy may now close / 原远端占用是否现可关闭
     * @throws ApiException if original cleanup checkpoints cannot be read / 原清理检查点无法读取时 */
    public static boolean originalCleanupReady(ModContext context, TaskContext task) throws ApiException {
        var graph = DeploymentStandardGraphPublishActionLog.reopen(context, task);
        if (graph.isPresent()) {
            var root = graph.orElseThrow();
            if (!root.released() && !java.util.Set.of("SESSION_CLOSED", "CLEANUP_VERIFIED").contains(root.facts().path("phase").asText()))
                return false;
            for (var index : root.indexes()) {
                var child = DeploymentPublishCleanupActionLog.reopen(context, task, index.key());
                if (child.isEmpty() || !cleanupReady(child.orElseThrow())) return false;
            }
            return true;
        }
        var child = DeploymentPublishCleanupActionLog.reopen(context, task);
        return child.isEmpty() || cleanupReady(child.orElseThrow());
    }

    /** Recognizes only original released or session-closed cleanup. / 仅识别原已释放或会话已关闭清理。
     * @param journal original child / 原子项
     * @return whether policy release is permitted / 是否允许策略释放 */
    private static boolean cleanupReady(DeploymentPublishCleanupActionLog journal) {
        return journal.released() || java.util.Set.of("SESSION_CLOSED", "CLEANUP_VERIFIED").contains(journal.facts().path("phase").asText());
    }

    /** Uses the same original guarded context for local evidence and every actual remote command. / 为本机依据及每条实际远端命令使用同份原受控上下文。
     * @param task authentic original recovery task / 真实原恢复任务
     * @param record retained execution / 留存执行
     * @return original observation or uncertainty / 原观察或未知 */
    private Optional<OperationResult<DeploymentPublishProposal>> reconcileOriginal(TaskContext task, DeploymentExecutionRecord record) {
        try {
            var reconciled = gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedEnvironmentRecovery
                .reconcile(context, task, record);
            if (reconciled.isEmpty()) return Optional.of(unknown("DEPLOYMENT_ASSISTED_ENVIRONMENT_RECOVERY_UNCONFIRMED"));
            record = reconciled.orElseThrow();
            var graph = DeploymentStandardGraphPublishActionLog.reopen(context, task);
            if (graph.isPresent()) return Optional.of(new DeploymentStandardGraphPublishRecovery(context,
                this::confirmBuildReviews).reconcile(task, record, graph.orElseThrow()));
        } catch (Exception | LinkageError | AssertionError unavailable) {
            return Optional.of(unknown("DEPLOYMENT_GRAPH_JOURNAL_UNAVAILABLE"));
        }
        return new DeploymentStandardSinglePublishRecovery(context, (original, execution, journal) ->
            permitsChild(context, original, execution, journal, execution.identity().applicationId(), execution.identity().reviewedRevision()))
            .reconcile(task, record);
    }

    /** Reconfirms the original build authority before recovery effects. / 在恢复效果发生前重新确认原构建授权。
     * @param task original admitted task / 原已准入任务
     * @param record original record bound to this operation / 当前操作绑定的原record值
     * @param root original whole-graph journal or source root / 原整图日志或来源根
     * @return original validated result or captured adapter / 原已校验结果或已捕获适配
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private boolean confirmBuildReviews(TaskContext task, DeploymentExecutionRecord record,
                                        DeploymentStandardGraphPublishActionLog root) throws Exception {
        for (var index : root.indexes()) {
            var child = DeploymentPublishCleanupActionLog.reopen(context, task, index.key());
            if (child.isEmpty()
                || !DeploymentPublishAttemptRecovery.child(context, task, record, child.orElseThrow())
                || !DeploymentAssistedReviewRecovery.child(context, task, record, child.orElseThrow()))
                return false;
        }
        return DeploymentPublishAttemptRecovery.root(context, task, record, root)
            && DeploymentAssistedReviewRecovery.root(context, task, record, root);
    }

    /** Preserves an unresolved original effect as UNKNOWN for reconciliation. / 将未解决原效果保留为 UNKNOWN 待核对。
     * @param event stable structured failure event / 稳定结构化失败事件
     * @return original UNKNOWN operation result / 原 UNKNOWN 操作结果 */
    private static OperationResult<DeploymentPublishProposal> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), newProblem(event));
    }

    /** Builds a structured publication failure without exposing private evidence. / 构造结构化发布失败，不暴露私有依据。
     * @param event stable structured failure event / 稳定结构化失败事件
     * @return structured bounded publication failure / 结构化有界发布失败 */
    private static Optional<Problem> newProblem(String event) {
        return Optional.of(new Problem(ProblemCode.UNKNOWN, event, "Original publication evidence remains unconfirmed",
            Map.of(), Optional.empty()));
    }
}

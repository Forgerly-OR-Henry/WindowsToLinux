package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentExecutionRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;

/** Routes only a documented prepublication no-effect task to local cleanup; all formal executions retain their original recovery. / 仅将有据的发布前无效果任务交本机清理；全部正式执行仍由原恢复处理。 */
public final class DeploymentStaticTaskRecovery implements TaskRecovery {
    /** Original bound Mod capabilities. / 原绑定 Mod 能力。 */
    private final ModContext context;

    /** Existing publication recovery. / 既有发布恢复。 */
    private final DeploymentReferenceFlow references;

    /** Authoritative execution facts. / 权威执行事实。 */
    private final DeploymentExecutionRepository executions;

    /** Binds the exact original publication recovery and owned task capabilities. / 绑定精确原发布恢复与所属任务能力。
     * @param context original Mod activation / 原 Mod 启用
     * @param references existing publication recovery / 既有发布恢复 */
    public DeploymentStaticTaskRecovery(ModContext context, DeploymentReferenceFlow references) {
        this.context = Objects.requireNonNull(context);
        this.references = Objects.requireNonNull(references);
        this.executions = new DeploymentExecutionRepository(context.db());
    }

    /** Reconciles only original local material when formal execution never began. / 仅在正式执行从未开始时核对原本机素材。
     * @param previous retained original task / 留存原任务
     * @param task authentic original recovery context / 真实原恢复上下文
     * @return original result or retained uncertainty / 原结果或保留未知
     * @throws Exception if original evidence cannot be read or released / 原依据无法读取或释放时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws Exception {
        if (!DeploymentReferenceFlow.TASK_TYPE.equals(previous.type()) || !previous.id().equals(task.id()))
            return unknown();
        if (executions.findForTask(task).isPresent())
            return references.reconcile(previous, task);
        Optional<DeploymentStaticPreparationJournal> marker;
        try {
            marker = DeploymentStaticPreparationJournal.find(context.tasks(), task);
        } catch (IllegalArgumentException damaged) {
            return unknown();
        }
        if (marker.isEmpty())
            return unknown();
        var gitOutcome = new DeploymentGitSourceFlow(context).reconcilePreparation(task);
        if (gitOutcome.filter(Outcome.UNKNOWN::equals).isPresent())
            return unknown();
        var original = marker.orElseThrow();
        boolean cancelled = original.wasCancelled() || gitOutcome.filter(Outcome.CANCELLED::equals).isPresent();
        context.source().closeTaskResources(task);
        original.close();
        if (!context.tasks().resourcesReleased(task))
            return unknown();
        var code = cancelled ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE;
        var outcome = cancelled ? Outcome.CANCELLED : Outcome.FAILED;
        return new OperationResult<>(outcome, Optional.empty(), Optional.of(new Problem(code,
                cancelled ? "DEPLOYMENT_STATIC_PREPARATION_CANCELLED" : "DEPLOYMENT_STATIC_PREPARATION_INTERRUPTED",
                "Static review ended before publication effects", Map.of(), Optional.empty())));
    }

    /** Keeps missing or conflicting evidence explicitly uncertain. / 缺失或冲突依据保持明确未知。
     * @return retained unknown / 保留未知 */
    private static OperationResult<Void> unknown() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_STATIC_PREPARATION_UNKNOWN",
                        "Original preparation needs review", Map.of(), Optional.empty())));
    }
}

package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentExecutionRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;

/** Reconciles original prepublication material and native DB receipts before local cleanup; formal executions retain their original recovery. / 本机清理前核对原发布前素材及原生数据库回执；正式执行仍由原恢复处理。 */
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

    /** Reconciles original DB effects and local material when formal execution never began. / 正式执行从未开始时核对原数据库效果和本机素材。
     * @param previous retained original task / 留存原任务
     * @param task authentic original recovery context / 真实原恢复上下文
     * @return original result or retained uncertainty / 原结果或保留未知
     * @throws Exception if original evidence cannot be read or released / 原依据无法读取或释放时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws Exception {
        if (!DeploymentReferenceFlow.TASK_TYPE.equals(previous.type()) || !previous.id().equals(task.id()))
            return unknown();
        Optional<DeploymentNativeDatabaseJournal> database;
        try {
            database = DeploymentNativeDatabaseJournal.find(context.tasks(), task);
        } catch (IllegalArgumentException damaged) {
            return unknown();
        }
        if (executions.findForTask(task).isPresent()) {
            if (database.isPresent() && !settleDatabase(task, database.orElseThrow()))
                return unknown();
            return references.reconcile(previous, task);
        }
        Optional<DeploymentStaticPreparationJournal> marker;
        try {
            marker = DeploymentStaticPreparationJournal.find(context.tasks(), task);
        } catch (IllegalArgumentException damaged) {
            return unknown();
        }
        if (marker.isEmpty())
            return unknown();
        if (database.isPresent() && !settleDatabase(task, database.orElseThrow()))
            return unknown();
        Optional<DeploymentNativeDatabaseJournal.Facts> databaseFacts;
        try {
            databaseFacts = database.isPresent() ? Optional.of(database.orElseThrow().facts()) : Optional.empty();
        } catch (ApiException | IllegalArgumentException unreadable) {
            return unknown();
        }
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
                databaseFacts.filter(DeploymentNativeDatabaseJournal.Facts::effectsObserved).isPresent()
                        ? "Native database effects may require manual review before publication"
                        : "Static review ended before publication effects",
                databaseFacts.isPresent()
                        ? Map.of("serverId", databaseFacts.orElseThrow().serverId(), "target",
                                databaseFacts.orElseThrow().target(), "effectTargets",
                                String.join(",", databaseFacts.orElseThrow().effectTargets()))
                        : Map.of(),
                Optional.empty())));
    }

    /** Settles only the original native operation after checking its exact helper receipt. / 仅核验精确 helper 回执后结清原生数据库原操作。
     * @param task original recovery task / 原恢复任务
     * @param journal original retained journal / 原保留日志
     * @return true only when the operation is known and released / 仅操作已知且释放时为真
     * @throws ApiException if original capabilities cannot be read / 原能力无法读取时 */
    private boolean settleDatabase(TaskContext task, DeploymentNativeDatabaseJournal journal) throws ApiException {
        var facts = journal.facts();
        if ("DISPATCHED".equals(facts.phase())) {
            try (RemoteSession session = context.linux().connect(new ServerRef(facts.serverId()), task)) {
                var result = new DeploymentNativeDatabaseChannel(session, context.interaction(),
                        new ServerRef(facts.serverId()), task).status(journal);
                if (result.outcome() == Outcome.UNKNOWN)
                    return false;
            } catch (ApiException unavailable) {
                return false;
            }
        }
        journal.close();
        return true;
    }

    /** Keeps missing or conflicting evidence explicitly uncertain. / 缺失或冲突依据保持明确未知。
     * @return retained unknown / 保留未知 */
    private static OperationResult<Void> unknown() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_STATIC_PREPARATION_UNKNOWN",
                        "Original preparation needs review", Map.of(), Optional.empty())));
    }
}

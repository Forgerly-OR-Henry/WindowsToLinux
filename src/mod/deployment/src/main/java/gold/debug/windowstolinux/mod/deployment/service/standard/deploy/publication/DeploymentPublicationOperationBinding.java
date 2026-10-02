package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.DeploymentVerifiedBuildResult;

/** Requires an actual later candidate to match the authentic formally consumed current operation. / 要求实际后续候选匹配真实正式已消费当前操作。 */
final class DeploymentPublicationOperationBinding {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentPublicationOperationBinding() {
    }

    /** Checks exact current operation before input staging or snapshot effects. / 输入暂存或快照副作用前检查精确当前操作。
     * @param context actual original Mod / 实际原Mod
     * @param task authentic unchanged task / 真实不变任务
     * @param build actual verified candidate / 实际已核候选
     * @param journal current child pointer / 当前子指针
     * @return whether formal operation and exact previous-state pointers agree / 正式操作及精确旧状态指针是否一致 */
    static boolean confirmed(ModContext context, TaskContext task, DeploymentVerifiedBuildResult build,
                             DeploymentPublicationCleanupJournal journal) {
        if (build.operation().isEmpty()) return !journal.facts().has("operation");
        try {
            var facts = journal.facts();
            DeploymentPublicationAttemptPointers.requireChild(facts);
            var record = new DeploymentExecutionRepository(context.db()).findForTask(task).orElseThrow();
            var repository = new DeploymentPublicationAttemptRepository(context.db());
            return repository.intent(record).isEmpty() && repository.operation(record).equals(build.operation().orElseThrow())
                && record.identity().reviewedRevision().equals(facts.path("attemptRootNext").asText())
                && facts.path("attemptState").asText().equals("CONFIRMED")
                && facts.path("operation").asText().equals(build.operation().orElseThrow().toString())
                && facts.path("candidate").asText().equals(build.candidateId())
                && facts.path("expectedRunning").isBoolean();
        } catch (Exception unavailable) {
            return false;
        }
    }
}

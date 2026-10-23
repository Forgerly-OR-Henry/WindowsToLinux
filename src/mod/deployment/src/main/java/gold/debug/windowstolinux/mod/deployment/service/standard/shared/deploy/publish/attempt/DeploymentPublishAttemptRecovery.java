package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.attempt;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReviewCodec;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptStore;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.attempt.pointer.DeploymentPublishAttemptPointers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishGraphJournal;

/** Confirms only formally consumed publication pointers, never resumes an interrupted author. / 仅确认正式已消费发布指针，不恢复中断作者。 */
public final class DeploymentPublishAttemptRecovery {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentPublishAttemptRecovery() {
    }

    /** Matches a retained child to its full consumed review before original observation or cleanup. / 原观察或收尾前匹配留存子节点与完整已消费审阅。
     * @param context original capabilities / 原能力
     * @param task authentic original recovery / 真实原恢复
     * @param record original formal execution / 原正式执行
     * @param journal original child pointer / 原子指针
     * @return whether the current operation is formally confirmed / 当前操作是否正式确认
     * @throws Exception if full original evidence is unavailable / 完整原依据不可用时 */
    public static boolean child(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                                DeploymentPublishCleanupActionLog journal) throws Exception {
        var facts = journal.facts();
        DeploymentPublishAttemptPointers.requireChild(facts);
        if (!facts.has("attemptState"))
            return new DeploymentPublishAttemptStore(context.db()).intent(record).isEmpty();
        var repository = new DeploymentPublishAttemptStore(context.db());
        var reviews = repository.reviews(record);
        if (repository.intent(record).isPresent() || reviews.isEmpty()) return false;
        var review = reviews.getLast();
        if (!record.identity().reviewedRevision().equals(review.nextRoot()) || !repository.operation(record).equals(review.nextOperation())
            || facts.path("attemptRetry").intValue() != review.retry()
            || !facts.path("attemptIntent").asText().equals(DeploymentPublishAttemptPointers.reviewBinding(review)))
            return false;
        if (facts.path("attemptState").asText().equals("CONFIRMED"))
            return facts.path("operation").asText().equals(review.nextOperation().toString())
                && facts.path("attemptReview").asText().equals(DeploymentPublishRestorationProof.digest(review.encode()));
        var after = DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan());
        if (facts.path("application").asText().equals(review.unitId()))
            return journal.attemptConfirmed(review, after.reviewedRevision(), after.runtime());
        var graph = DeploymentAssistedBuildReviewCodec.decodeGraph(review.originalGraph());
        var component = graph.namespaces().entrySet().stream().filter(entry -> entry.getValue().equals(facts.path("application").asText())).findFirst().orElseThrow();
        var proposal = repository.previousTemplate(record);
        var original = proposal.graph().components().stream().filter(value -> value.componentId().equals(component.getKey())).findFirst().orElseThrow();
        return journal.attemptConfirmed(review, graph.identities().get(component.getKey()), original.reviewedRuntime());
    }

    /** Confirms the root only after every authentic child pointer was checked. / 每个真实子指针检查后才确认根。
     * @param context original capabilities / 原能力
     * @param task authentic original recovery / 真实原恢复
     * @param record original formal execution / 原正式执行
     * @param root exact original graph / 精确原图
     * @return whether the current graph is confirmed / 当前图是否确认
     * @throws Exception if original formal evidence is unavailable / 原正式依据不可用时 */
    public static boolean root(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                               DeploymentPublishGraphJournal root) throws Exception {
        var facts = root.facts();
        DeploymentPublishAttemptPointers.requireRoot(facts);
        var repository = new DeploymentPublishAttemptStore(context.db());
        if (!facts.has("attemptState")) return repository.intent(record).isEmpty();
        var reviews = repository.reviews(record);
        if (repository.intent(record).isPresent() || reviews.isEmpty()) return false;
        var review = reviews.getLast();
        if (!record.identity().reviewedRevision().equals(review.nextRoot()) || !repository.operation(record).equals(review.nextOperation())
            || !facts.path("attemptIntent").asText().equals(DeploymentPublishAttemptPointers.reviewBinding(review)))
            return false;
        return facts.path("attemptState").asText().equals("CONFIRMED")
            ? facts.path("operation").asText().equals(review.nextOperation().toString())
            && facts.path("attemptReview").asText().equals(DeploymentPublishRestorationProof.digest(review.encode()))
            : root.attemptConfirmed(review);
    }
}

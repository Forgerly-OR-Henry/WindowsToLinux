package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReviewCodec;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;

/** Confirms only retained formal-to-checkpoint build review pointers after restart, never installs or builds. / 重启后仅确认留存正式账本到检查点的构建审阅指针，绝不安装或构建。 */
public final class DeploymentAssistedReviewRecovery {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentAssistedReviewRecovery() {
    }

    /** Reads the full original intent and review chain before accepting any child recovery identity. / 接受任一子恢复身份前读取完整原意图及审阅链。
     * @param context actual original Mod activation / 实际原Mod启用
     * @param task authentic original recovery task / 真实原恢复任务
     * @param record exact original formal record / 精确原正式记录
     * @param journal exact retained child / 精确留存子节点
     * @return whether observation and original cleanup remain admissible / 观察及原收尾是否仍可准入
     * @throws Exception if original access or framing is unavailable / 原访问或信封不可用时 */
    public static boolean child(ModContext context, TaskContext task, DeploymentExecutionRecord record, DeploymentPublishCleanupActionLog journal) throws Exception {
        var repository = new DeploymentExecutionStore(context.db());
        var pending = repository.buildIntent(record);
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        var facts = journal.facts();
        if (pending.isPresent() && pending.orElseThrow().unitId().equals(facts.path("application").asText())) {
            var intent = pending.orElseThrow();
            if (!DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan()).layout().equals(layout)
                || !DeploymentAssistedBuildReviewCodec.decodeDescription(intent.nextPlan()).layout().equals(layout))
                return false;
            if (!record.identity().reviewedRevision().equals(intent.previousRoot()) || !journal.actualBuild(intent)
                || !facts.path("planSha256").asText().equals(DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan()).reviewedRevision()))
                return false;
            return switch (facts.path("environmentRecovery").asText()) {
                case "MAY_HAVE_RUN" -> DeploymentAssistedEnvironmentRecovery.permits(context, task, record, journal);
                case "SETTLED" -> DeploymentAssistedEnvironmentRecovery.settled(context, task, record, journal);
                default -> true;
            };
        }
        if (!facts.has("buildReviewState")) return true;
        if (!facts.path("buildReviewAttempt").isInt() || !facts.path("buildReviewState").isTextual()) return false;
        var reviews = repository.buildReviews(record);
        var found = reviews.stream().filter(value -> value.unitId().equals(facts.path("application").asText()) && value.attempt() == facts.path("buildReviewAttempt").intValue()).findFirst();
        if (found.isEmpty()) return false;
        var review = found.orElseThrow();
        if (!DeploymentAssistedBuildReviewCodec.decodeDescription(review.previousPlan()).layout().equals(layout)
            || !DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan()).layout().equals(layout))
            return false;
        if (!facts.path("buildReviewPrevious").asText().equals(DeploymentAssistedBuildReviewCodec.decodeDescription(review.previousPlan()).reviewedRevision())
            || !facts.path("buildReviewNext").asText().equals(DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan()).reviewedRevision())
            || !facts.path("buildReviewRootPrevious").asText().equals(review.previousRoot()) || !facts.path("buildReviewRootNext").asText().equals(review.nextRoot())
            || !facts.path("buildReviewIntent").asText().equals(review.failureBinding())) return false;
        if (facts.path("buildReviewState").textValue().equals("CONFIRMED"))
            return facts.path("planSha256").asText().equals(facts.path("buildReviewNext").textValue());
        if (!facts.path("buildReviewState").textValue().equals("PENDING") || !record.identity().reviewedRevision().equals(review.nextRoot())
            || !facts.path("environmentRecovery").asText().equals("VERIFIED") || !facts.path("environmentTools").asText().equals(sha(review.nextTools()))
            || !facts.path("buildScript").asText().equals(sha(review.previousScript())) || !facts.path("buildTools").asText().equals(sha(review.previousTools()))
            || !facts.path("buildFailureEvidence").asText().equals(sha(review.failureEvidence()))) return false;
        return journal.reviewConfirmed(review);
    }

    /** Confirms a retained graph root only against its exact formally consumed child transition. / 仅按精确正式已消费子转换确认留存图根。
     * @param context current original activation / 当前原启用
     * @param task original recovery task / 原恢复任务
     * @param record original formal record / 原正式记录
     * @param root original retained root / 原留存根
     * @return whether the exact root is confirmed / 精确根是否确认
     * @throws Exception if original formal evidence is unavailable / 原正式依据不可用时 */
    public static boolean root(ModContext context, TaskContext task, DeploymentExecutionRecord record, DeploymentStandardGraphPublishActionLog root) throws Exception {
        var facts = root.facts();
        if (!facts.has("buildReviewState") || facts.path("buildReviewState").asText().equals("CONFIRMED"))
            return facts.path("planSha256").asText().equals(record.identity().reviewedRevision());
        if (!facts.path("buildReviewState").asText().equals("PENDING")) return false;
        var repository = new DeploymentExecutionStore(context.db());
        var pending = repository.buildIntent(record);
        if (pending.isPresent()) return facts.path("planSha256").asText().equals(record.identity().reviewedRevision())
            && pending.orElseThrow().previousRoot().equals(record.identity().reviewedRevision()) && pending.orElseThrow().nextRoot().equals(facts.path("buildReviewNext").asText());
        var chain = repository.buildReviews(record);
        return !chain.isEmpty() && chain.getLast().nextRoot().equals(record.identity().reviewedRevision()) && root.reviewConfirmed(chain.getLast());
    }

    /** Hashes exact persisted proof bytes without reconstructing any live source. / 哈希精确持久证明字节，不重建活动来源。
     * @param value original persisted bytes represented as text / 以文本表示的原持久字节
     * @return exact proof SHA / 精确证明SHA */
    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }
}

package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy;

/** Shared graph occupancy contract independent of graph execution. / 独立于图执行的共用整图占用合同。 */
public interface DeploymentPublishGraphJournal {
    /** Returns immutable evidence bound to the original executable or journal. / 返回绑定原可执行输入或日志的不可变依据。
     * @return immutable original executable or journal evidence / 不可变原可执行输入或日志依据 */
    com.fasterxml.jackson.databind.JsonNode facts();

    /** Persists the next original publication phase without a new ledger. / 持久化下一原发布阶段，不另建账本。
     * @param phase next persisted original operation phase / 下一持久化原操作阶段
     * @return whether the original phase was persisted / 原阶段是否已持久化 */
    boolean mark(String phase);

    /** Records the confirmed original graph publication attempt. / 记录已确认原整图发布尝试。
     * @param review original review bound to this operation / 当前操作绑定的原review值
     * @return whether the original attempt was persisted / 原尝试是否已持久化 */
    boolean attemptConfirmed(gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptReview review);
}

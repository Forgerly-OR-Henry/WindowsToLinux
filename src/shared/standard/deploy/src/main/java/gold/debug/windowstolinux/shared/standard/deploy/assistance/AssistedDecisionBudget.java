package gold.debug.windowstolinux.shared.standard.deploy.assistance;

/** Task-wide decision allowance shared by analysis and recovery. / 分析及恢复共用的任务决策额度。 */
public final class AssistedDecisionBudget {
    /** Remaining model decisions; provider handoff never replenishes it. / 剩余模型决策数，提供者接替不补充额度。 */
    private int remaining = 30;

    /** Consumes one decision before making a model request. / 模型请求前消耗一次决策。
     * @return remaining decisions / 剩余决策数
     * @throws IllegalStateException when the task allowance is exhausted / 任务额度耗尽时
     */
    public int consume() {
        if (remaining == 0)
            throw new IllegalStateException("assisted decision budget exhausted");
        return --remaining;
    }

    /** Reports the unspent allowance. / 返回尚未使用的额度。
     * @return remaining decisions / 剩余决策数
     */
    public int remaining() {
        return remaining;
    }
}

package gold.debug.windowstolinux.shared.model.agent;
/**
 * One deployment decision referring to an exact offered action. / 引用精确候选动作的一次部署决策。
 * @param decision bounded decision / 受限决策
 * @param actionId chosen action or empty when paused / 所选动作，暂停时为空
 * @param binding offered action digest / 候选动作摘要
 * @param reason bounded explanation / 有界解释
 */
public record AgentDecision(AgentDecisionType decision,String actionId,String binding,String reason) {
    /** Rejects absent or unbounded model fields. / 拒绝缺失或无界模型字段。
     * @param decision bounded decision / 受限决策
     * @param actionId chosen action or empty when paused / 所选动作，暂停时为空
     * @param binding offered action digest / 候选动作摘要
     * @param reason bounded explanation / 有界解释
     */
    public AgentDecision {java.util.Objects.requireNonNull(decision);if(actionId==null||binding==null||reason==null||actionId.length()>128||binding.length()>64||reason.length()>2048)throw new IllegalArgumentException("invalid decision");}
}

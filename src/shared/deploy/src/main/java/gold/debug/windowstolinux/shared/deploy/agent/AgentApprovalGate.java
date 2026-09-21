package gold.debug.windowstolinux.shared.deploy.agent;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;

/** Fail-closed policy evaluated again immediately before execution. / 执行前再次求值的默认拒绝策略。 */
public final class AgentApprovalGate {
    /** Prevents construction. / 禁止实例化。 */
    private AgentApprovalGate(){}
    /** Requires an exact positive independent review. / 要求精确绑定的独立肯定审批。
     * @param action trusted action / 可信动作
     * @param local deterministic risk / 确定性风险
     * @param review reviewer decision / 审批决定
     * @return stricter combined risk / 更严格的合并风险
     */
    public static AgentRiskLevel admit(AgentAction action,AgentRiskLevel local,AgentReview review){
        if(local==AgentRiskLevel.FORBIDDEN||action.risk()==AgentRiskLevel.FORBIDDEN||review==null
                ||review.decision()!=AgentReviewDecision.ALLOW||review.risk()==AgentRiskLevel.FORBIDDEN
                ||!action.binding().equals(review.binding())||review.evidence().isEmpty()
                ||!action.evidence().keySet().containsAll(review.evidence()))throw new SecurityException("action not approved");
        return AgentRiskLevel.values()[Math.max(action.risk().ordinal(),Math.max(local.ordinal(),review.risk().ordinal()))];
    }
    /** Computes human confirmation without expanding authorization. / 计算人工确认需求，不扩大授权。
     * @param mode selected policy / 所选策略
     * @param risk admitted risk / 已准入风险
     * @return whether each action needs confirmation / 是否需要逐动作确认
     */
    public static boolean needsHuman(AgentApprovalMode mode,AgentRiskLevel risk){
        if(risk==AgentRiskLevel.FORBIDDEN)throw new SecurityException("forbidden action");
        return mode==AgentApprovalMode.MANUAL_REVIEW||mode==AgentApprovalMode.AUTOMATIC&&risk==AgentRiskLevel.HIGH;
    }
}

package gold.debug.windowstolinux.shared.model.agent;
import java.util.List;
/**
 * Independent review bound to one exact action. / 绑定一个精确动作的独立审批。
 * @param decision approval disposition / 审批处置
 * @param risk reviewer risk; cannot reduce local risk / 审批风险，不能降低本地风险
 * @param binding approved action digest / 被审批动作摘要
 * @param reason bounded review explanation / 有界审批解释
 * @param evidence referenced observed facts / 引用的观察事实
 */
public record AgentReview(AgentReviewDecision decision,AgentRiskLevel risk,String binding,String reason,List<String> evidence){
    /** Requires bounded review fields without assuming approval. / 要求有界审批字段，不预设通过。
     * @param decision approval disposition / 审批处置
     * @param risk reviewer risk; cannot reduce local risk / 审批风险，不能降低本地风险
     * @param binding approved action digest / 被审批动作摘要
     * @param reason bounded review explanation / 有界审批解释
     * @param evidence referenced observed facts / 引用的观察事实
     */
    public AgentReview {java.util.Objects.requireNonNull(decision);java.util.Objects.requireNonNull(risk);
        if(binding==null||!binding.matches("[0-9a-f]{64}")||reason==null||reason.isBlank()||reason.length()>2048)throw new IllegalArgumentException("invalid review");
        evidence=List.copyOf(evidence);if(evidence.size()>128||evidence.stream().anyMatch(v->v.length()>160))throw new IllegalArgumentException("invalid evidence");
    }
}

package gold.debug.windowstolinux.shared.model.agent;
import java.util.Map;
/**
 * Actual executor result; unknown outcomes must never be replayed. / 实际执行器结果，未知结果绝不重放。
 * @param known whether the outcome is confirmed / 结果是否已确认
 * @param succeeded actual operation success / 操作是否实际成功
 * @param facts bounded sanitized observed facts / 有界脱敏观察事实
 */
public record AgentObservation(boolean known,boolean succeeded,Map<String,String> facts){
    /** Freezes result evidence and rejects contradictory success. / 冻结结果证据并拒绝矛盾的成功状态。
     * @param known whether the outcome is confirmed / 结果是否已确认
     * @param succeeded actual operation success / 操作是否实际成功
     * @param facts bounded sanitized observed facts / 有界脱敏观察事实
     */
    public AgentObservation{facts=Map.copyOf(facts);if(!known&&succeeded)throw new IllegalArgumentException("unknown success");
        if(facts.size()>128||facts.entrySet().stream().anyMatch(e->e.getKey().length()>160||e.getValue().length()>8192))throw new IllegalArgumentException("result too large");}
}

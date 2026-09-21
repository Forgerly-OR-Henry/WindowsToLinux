package gold.debug.windowstolinux.shared.deploy.agent;
import gold.debug.windowstolinux.shared.model.agent.*;
/** Narrow executor for an already registered action. / 已登记动作的窄执行接口。 */
public interface AgentExecutionPort {
    /** Refreshes pending evidence after a checked diagnostic, invalidating old bindings. / 在受检诊断后刷新待执行证据，使旧绑定失效。
     * @param action pending action / 待执行动作
     * @return current trusted action / 当前可信动作
     */
    default AgentAction refresh(AgentAction action){return action;}

    /** Rechecks ownership, frozen revisions and current preconditions. / 重新检查归属、冻结修订及当前前置条件。
     * @param action exact proposed action / 精确提议动作
     * @return deterministic risk, FORBIDDEN when rejected / 确定性风险，拒绝时为 FORBIDDEN
     * @throws Exception when validation cannot establish safety / 无法确认安全时
     */
    AgentRiskLevel validate(AgentAction action)throws Exception;
    /** Executes one admitted typed action. / 执行一个已准入类型化动作。
     * @param action approved action / 已审批动作
     * @return actual outcome / 实际结果
     * @throws Exception when the outcome cannot be established / 无法确认结果时
     */
    AgentObservation execute(AgentAction action)throws Exception;
}

package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import java.util.List;

import gold.debug.windowstolinux.shared.model.agent.*;

/** Independent decision and review contexts supplied by purpose-based adapters. / 用途适配器提供的独立决策及审批上下文。 */
public interface AssistedActionModelPort extends gold.debug.windowstolinux.shared.deploy.approval.DeploymentReviewPort {
    /** Chooses a next action using actual history. / 根据实际历史选择下一动作。
     * @param goal fixed user goal / 固定用户目标
     * @param actions exact available actions / 精确可用动作
     * @param history bounded execution and rejection history / 有界执行及拒绝历史
     * @param remaining remaining shared decision budget / 剩余共享决策预算
     * @return structured decision / 结构化决策
     * @throws Exception when all deployment models are unavailable / 部署模型全部不可用时
     */
    AgentDecision decide(String goal, List<AgentAction> actions, List<String> history, int remaining) throws Exception;

    /** Reviews without access to execution tools or recovery-model reasoning. / 审批时不访问执行工具或恢复模型推理。
     * @param goal fixed authorization / 固定授权
     * @param action exact pending action / 精确待执行动作
     * @param localRisk local validation result / 本地验证结果
     * @return independently bound review / 独立绑定审批
     * @throws Exception when approval service is unavailable / 审批服务不可用时
     */
    AgentReview review(String goal, AgentAction action, AgentRiskLevel localRisk) throws Exception;

    /** Advances deployment models for inability or no progress, never reviewer shopping. / 因无法处理或无进展接替部署模型，绝不更换审批以寻求放行。
     * @return whether a later deployment model remains / 是否存在后续部署模型
     */
    boolean advanceDeployment();
}

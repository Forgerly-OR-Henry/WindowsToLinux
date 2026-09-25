package gold.debug.windowstolinux.shared.deploy.approval;

import gold.debug.windowstolinux.shared.model.agent.*;

/** Independent purpose-isolated approval model boundary. / 独立且用途隔离的审批模型边界。 */
@FunctionalInterface
public interface DeploymentReviewPort {
    /** Reviews one bound operation without access to deployment reasoning. / 审批一个绑定操作，不访问部署推理。
     * @param goal user authorization / 用户授权
     * @param action pending operation / 待执行操作
     * @param localRisk deterministic risk / 确定性风险
     * @return independently bound review / 独立绑定审批
     * @throws Exception on model or protocol failure / 模型或协议失败时
     */
    AgentReview review(String goal, AgentAction action, AgentRiskLevel localRisk) throws Exception;
}

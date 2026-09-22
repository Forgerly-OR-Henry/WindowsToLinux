package gold.debug.windowstolinux.app.service.ai;

import java.util.*;

import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;

/** App credential adapter; deployment and approval never share conversation state. / App 凭据适配器，部署与审批不共享会话状态。 */
public final class DeploymentAgentModelAdapter
        implements
            gold.debug.windowstolinux.shared.deploy.approval.DeploymentReviewPort,
            gold.debug.windowstolinux.shared.agent.execution.protocol.AutonomousModelPort,
            AutoCloseable {
    /** Selects an autonomous tool using only actual task evidence. / 仅根据实际任务证据选择自主工具。
     * @param context current revisions and plan / 当前修订及方案
     * @param history bounded observations / 有界观察
     * @param remaining remaining decision budget / 剩余决策预算
     * @return validated tool proposal / 已校验工具提议
     * @throws Exception when deployment models are unavailable / 部署模型不可用时
     */
    @Override
    public gold.debug.windowstolinux.shared.agent.execution.protocol.AgentProposal decide(Map<String, Object> context,
            List<Map<String, String>> history, int remaining) throws Exception {
        var result = chain.invoke(AiPurposeType.DEPLOYMENT, master.clone(), (profile, key) -> {
            var reply = new gold.debug.windowstolinux.shared.agent.execution.protocol.AutonomousAiProtocolClient(client)
                    .decide(profile.chatCompletionsEndpoint(), profile.model(), key, context, history, remaining);
            scope.usage(reply.tokens());
            return new AiProviderChain.Attempt<>(reply.value(), AiInvocationStatus.VALIDATED,
                    "autonomous-proposal-validated");
        });
        if (!result.valid())
            throw new IllegalStateException("deployment-models-unavailable");
        return result.value().orElseThrow();
    }

    /** Advances without resetting budgets or mixing approval purpose. / 接替时不重置预算或混用审批用途。
     * @return whether another deployment model is available / 是否存在下一个部署模型
     */
    @Override
    public boolean advance() {
        return advanceDeployment();
    }
    /** Purpose routing and secret resolution. / 用途路由及秘密解析。 */
    private final AiProviderChain chain;

    /** Strict dedicated Agent protocol. / 严格专用 Agent 协议。 */
    private final StructuredAiClient client;

    /** Task-owned unlock buffer, wiped on close. / 任务所属解锁缓冲区，关闭时清零。 */
    private final char[] master;

    /** Frozen worker scope. / 冻结工作线程作用域。 */
    private final DeploymentAiScope scope;
    /** Binds task-scoped credentials without opening a connection. / 绑定任务凭据，不打开连接。
     * @param chain purpose router / 用途路由器
     * @param client isolated protocol / 独立协议
     * @param master unlock buffer / 解锁缓冲区
     */
    DeploymentAgentModelAdapter(AiProviderChain chain, StructuredAiClient client, char[] master) {
        this.chain = chain;
        this.client = client;
        this.master = master.clone();
        this.scope = DeploymentAiScope.current()
                .orElseThrow(() -> new IllegalStateException("Agent requires deployment scope"));
    }

    /** Independently reviews with approval-role models only; a valid denial ends routing. / 仅使用审批用途独立审批，有效拒绝终止路由。
     * @param goal fixed goal / 固定目标
     * @param action exact action / 精确动作
     * @param localRisk deterministic risk / 确定性风险
     * @return strict independent review / 严格独立审批
     * @throws Exception when no reviewer returns a valid response / 没有审批者返回有效响应时
     */
    @Override
    public AgentReview review(String goal, AgentAction action, AgentRiskLevel localRisk) throws Exception {
        return new DeploymentReviewModelAdapter(chain, client, master, scope).review(goal, action, localRisk);
    }

    /** Advances only the deployment route. / 仅推进部署路由。
     * @return whether a model remains / 是否还有模型
     */
    public boolean advanceDeployment() {
        return scope.advance(AiPurposeType.DEPLOYMENT);
    }

    /** Wipes the task unlock buffer. / 清零任务解锁缓冲区。 */
    @Override
    public void close() {
        Arrays.fill(master, '\0');
    }
}

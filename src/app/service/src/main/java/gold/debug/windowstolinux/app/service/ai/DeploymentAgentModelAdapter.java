package gold.debug.windowstolinux.app.service.ai;
import gold.debug.windowstolinux.shared.ai.client.AgentProtocolClient;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.deploy.agent.AgentModelPort;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import gold.debug.windowstolinux.shared.model.agent.*;
import java.util.*;

/** App credential adapter; deployment and approval never share conversation state. / App 凭据适配器，部署与审批不共享会话状态。 */
public final class DeploymentAgentModelAdapter implements AgentModelPort,AutoCloseable {
    /** Purpose routing and secret resolution. / 用途路由及秘密解析。 */
    private final AiProviderChain chain;
    /** Strict dedicated Agent protocol. / 严格专用 Agent 协议。 */
    private final AgentProtocolClient client;
    /** Task-owned unlock buffer, wiped on close. / 任务所属解锁缓冲区，关闭时清零。 */
    private final char[] master;
    /** Frozen worker scope. / 冻结工作线程作用域。 */
    private final DeploymentAiScope scope;
    /** Binds task-scoped credentials without opening a connection. / 绑定任务凭据，不打开连接。
     * @param chain purpose router / 用途路由器
     * @param client isolated protocol / 独立协议
     * @param master unlock buffer / 解锁缓冲区
     */
    DeploymentAgentModelAdapter(AiProviderChain chain,AgentProtocolClient client,char[] master){this.chain=chain;this.client=client;this.master=master.clone();
        this.scope=DeploymentAiScope.current().orElseThrow(()->new IllegalStateException("Agent requires deployment scope"));}
    /** Chooses the next action with only deployment-role models. / 仅使用部署用途模型选择下一动作。
     * @param goal fixed goal / 固定目标
     * @param actions trusted action menu / 可信动作菜单
     * @param history bounded handoff evidence / 有界交接证据
     * @param remaining shared remaining budget / 共享剩余预算
     * @return strict decision / 严格决策
     * @throws Exception when the role list is exhausted / 用途列表耗尽时
     */
    @Override public AgentDecision decide(String goal,List<AgentAction> actions,List<String> history,int remaining)throws Exception{
        var result=chain.invoke(AiPurposeType.DEPLOYMENT,master.clone(),(profile,key)->{
            var reply=client.decide(profile.chatCompletionsEndpoint(),profile.model(),key,goal,actions,history,remaining);scope.usage(reply.tokens());
            return new AiProviderChain.Attempt<>(reply.value(),AiInvocationStatus.VALIDATED,"agent-decision-validated");
        });
        if(!result.valid())throw new IllegalStateException("deployment-models-unavailable");return result.value().orElseThrow();
    }
    /** Independently reviews with approval-role models only; a valid denial ends routing. / 仅使用审批用途独立审批，有效拒绝终止路由。
     * @param goal fixed goal / 固定目标
     * @param action exact action / 精确动作
     * @param localRisk deterministic risk / 确定性风险
     * @return strict independent review / 严格独立审批
     * @throws Exception when no reviewer returns a valid response / 没有审批者返回有效响应时
     */
    @Override public AgentReview review(String goal,AgentAction action,AgentRiskLevel localRisk)throws Exception{
        var result=chain.invoke(AiPurposeType.APPROVAL,master.clone(),(profile,key)->{
            var reply=client.review(profile.chatCompletionsEndpoint(),profile.model(),key,goal,action,localRisk);scope.usage(reply.tokens());
            return new AiProviderChain.Attempt<>(reply.value(),AiInvocationStatus.VALIDATED,"independent-review-validated");
        });
        if(!result.valid())throw new IllegalStateException("approval-service-unavailable");return result.value().orElseThrow();
    }
    /** Advances only the deployment route. / 仅推进部署路由。
     * @return whether a model remains / 是否还有模型
     */
    @Override public boolean advanceDeployment(){return scope.advance(AiPurposeType.DEPLOYMENT);}
    /** Wipes the task unlock buffer. / 清零任务解锁缓冲区。 */
    @Override public void close(){Arrays.fill(master,'\0');}
}

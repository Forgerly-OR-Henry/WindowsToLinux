package gold.debug.windowstolinux.app.service.ai;

import java.util.*;

import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.*;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.execution.protocol.AssistedAiProtocolClient;

/** Independent assisted analysis and registered recovery model adapter. / 独立的辅助分析及已登记恢复模型适配器。 */
public final class AssistedDeploymentModelAdapter
        implements
            AssistedAnalysisModelPort,
            AssistedActionModelPort,
            AutoCloseable {
    /** Frozen purpose routing. / 冻结用途路由。 */
    private final AiProviderChain chain;

    /** Assistance-specific protocol. / 辅助专用协议。 */
    private final AssistedAiProtocolClient client;

    /** Task-owned credential buffer. / 任务所属凭据缓冲区。 */
    private final char[] master;

    /** Model scope and metrics. / 模型作用域及度量。 */
    private final DeploymentAiScope scope;

    /** Isolated approval transport. / 隔离审批传输。 */
    private final DeploymentReviewModelAdapter reviewer;

    /** Creates assistance-only model capabilities. / 创建仅用于辅助的模型能力。
     * @param chain purpose router / 用途路由
     * @param transport generic model transport / 通用模型传输
     * @param master task unlock buffer / 任务解锁缓冲区
     */
    AssistedDeploymentModelAdapter(AiProviderChain chain, StructuredAiClient transport, char[] master) {
        this.chain = chain;
        this.client = new AssistedAiProtocolClient(transport);
        this.master = master.clone();
        scope = DeploymentAiScope.current().orElseThrow();
        reviewer = new DeploymentReviewModelAdapter(chain, transport, this.master, scope);
    }

    /** Requests a source-only analysis step. / 请求纯源码分析步骤。
     * @param context source and fields / 源码及字段
     * @param observations actual source evidence / 实际源码证据
     * @param remaining task allowance / 任务额度
     * @return verified analysis decision / 已验证分析决定
     * @throws Exception when all providers fail / 全部提供者失败时
     */
    @Override
    public AssistedAnalysisDecision analyze(Map<String, Object> context, List<Map<String, String>> observations,
            int remaining) throws Exception {
        var result = chain.invoke(AiPurposeType.DEPLOYMENT, master.clone(), (profile, key) -> {
            var reply = client.analyze(profile.chatCompletionsEndpoint(), profile.model(), key, context, observations,
                    remaining);
            scope.usage(reply.tokens());
            return new AiProviderChain.Attempt<>(reply.value(), AiInvocationStatus.VALIDATED,
                    "assisted-analysis-validated");
        });
        if (!result.valid())
            throw new IllegalStateException("deployment-models-unavailable");
        return result.value().orElseThrow();
    }

    /** Selects only a supplied recovery operation. / 仅选择已提供的恢复操作。
     * @param goal recovery goal / 恢复目标
     * @param actions closed registered menu / 封闭登记菜单
     * @param history actual results / 实际结果
     * @param remaining task allowance / 任务额度
     * @return selected recovery operation / 所选恢复操作
     * @throws Exception when all providers fail / 全部提供者失败时
     */
    @Override
    public AgentDecision decide(String goal, List<AgentAction> actions, List<String> history, int remaining)
            throws Exception {
        var result = chain.invoke(AiPurposeType.DEPLOYMENT, master.clone(), (profile, key) -> {
            var reply = client.decide(profile.chatCompletionsEndpoint(), profile.model(), key, goal, actions, history,
                    remaining);
            scope.usage(reply.tokens());
            return new AiProviderChain.Attempt<>(reply.value(), AiInvocationStatus.VALIDATED,
                    "assisted-recovery-validated");
        });
        if (!result.valid())
            throw new IllegalStateException("deployment-models-unavailable");
        return result.value().orElseThrow();
    }

    /** Uses the common independent reviewer. / 使用公共独立审批者。
     * @param goal authorized goal / 已授权目标
     * @param action exact action / 精确动作
     * @param localRisk local risk / 本地风险
     * @return independent decision / 独立决定
     * @throws Exception when approval fails / 审批失败时
     */
    @Override
    public AgentReview review(String goal, AgentAction action, AgentRiskLevel localRisk) throws Exception {
        return reviewer.review(goal, action, localRisk);
    }

    /** Advances only deployment-purpose providers. / 仅推进部署用途提供者。
     * @return whether a provider remains / 是否仍有提供者
     */
    @Override
    public boolean advanceDeployment() {
        return scope.advance(AiPurposeType.DEPLOYMENT);
    }

    /** Wipes the task-owned unlock buffer. / 清零任务所属解锁缓冲区。 */
    @Override
    public void close() {
        Arrays.fill(master, '\0');
    }
}

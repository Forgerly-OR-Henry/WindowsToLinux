package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.deploy.approval.DeploymentReviewPort;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;

/** Common purpose-isolated approval transport without execution tools. / 不含执行工具的公共用途隔离审批传输。 */
final class DeploymentReviewModelAdapter implements DeploymentReviewPort {
    /** Credential route owned by the caller. / 调用方持有的凭据路由。 */
    private final AiProviderChain chain;

    /** Structured transport. / 结构化传输。 */
    private final StructuredAiClient client;

    /** Caller-owned buffer, wiped by the owning adapter. / 由所属适配器清零的调用方缓冲区。 */
    private final char[] master;

    /** Current immutable model configuration. / 当前不可变模型配置。 */
    private final DeploymentAiScope scope;

    /** Binds the shared approval transport. / 绑定共享审批传输。
     * @param chain credential route / 凭据路由
     * @param client transport / 传输
     * @param master caller-owned secret buffer / 调用方秘密缓冲区
     * @param scope task scope / 任务作用域
     */
    DeploymentReviewModelAdapter(AiProviderChain chain, StructuredAiClient client, char[] master,
            DeploymentAiScope scope) {
        this.chain = chain;
        this.client = client;
        this.master = master;
        this.scope = scope;
    }

    /** Reviews using only the approval-purpose route. / 仅使用审批用途路由进行审核。
     * @param goal authorized scope / 已授权范围
     * @param action exact pending operation / 精确待执行操作
     * @param localRisk local risk floor / 本地风险下限
     * @return independent bound review / 独立绑定审批
     * @throws Exception when approval is unavailable / 审批不可用时
     */
    @Override
    public AgentReview review(String goal, AgentAction action, AgentRiskLevel localRisk) throws Exception {
        var result = chain.invoke(AiPurposeType.APPROVAL, master.clone(), (profile, key) -> {
            var reply = new gold.debug.windowstolinux.shared.deploy.approval.DeploymentApprovalClient(client)
                    .review(profile.chatCompletionsEndpoint(), profile.model(), key, goal, action, localRisk);
            scope.usage(reply.tokens());
            return new AiProviderChain.Attempt<>(reply.value(), AiInvocationStatus.VALIDATED,
                    "independent-review-validated");
        });
        if (!result.valid())
            throw new IllegalStateException("approval-service-unavailable");
        return result.value().orElseThrow();
    }
}

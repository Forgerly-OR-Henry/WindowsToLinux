package gold.debug.windowstolinux.shared.deploy.approval;

import static gold.debug.windowstolinux.shared.ai.execution.protocol.StrictJson.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient.Reply;
import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionResponseParser;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.ai.transport.*;
import gold.debug.windowstolinux.shared.model.agent.*;

/** Independent command and operation review protocol. / 独立命令及操作审批协议。 */
public final class DeploymentApprovalClient {
    /** Versioned review prompt. / 版本化审批提示。 */
    public static final String APPROVAL_SKILL = "approval-v1";

    /** Shared transport. / 共享传输。 */
    private final StructuredAiClient client;
    /** Binds transport. / 绑定传输。
     * @param client structured transport / 结构化传输
     */
    public DeploymentApprovalClient(StructuredAiClient client) {
        this.client = Objects.requireNonNull(client);
    }

    /** Executes one reviewer request with no decision-agent transcript or tools. / 执行一次不携带部署 Agent 会话或工具的审批请求。
     * @param endpoint verified endpoint / 已验证端点
     * @param model frozen reviewer model / 冻结审批模型
     * @param key temporary secret buffer / 临时秘密缓冲区
     * @param goal authorized goal / 已授权目标
     * @param action precise pending action / 精确待执行动作
     * @param localRisk deterministic risk / 确定性风险
     * @return strict independent review and usage / 严格独立审批及用量
     * @throws Exception on invalid or unavailable responses / 响应无效或不可用时
     */
    public Reply<AgentReview> review(URI endpoint, String model, char[] key, String goal, AgentAction action,
            AgentRiskLevel localRisk) throws Exception {
        var response = client.request(endpoint, model, key, DeploymentApprovalClient.class,
                "/gold/debug/windowstolinux/shared/deploy/approval/approval.md",
                Map.of("goal", goal, "action", action(action), "localRisk", localRisk.name(), "localValidation",
                        "passed; rechecked before execution"));
        JsonNode node = response.value();
        exact(node, Set.of("decision", "risk", "binding", "reason", "evidence"));
        if (!node.get("evidence").isArray() || node.get("evidence").size() > 128)
            throw new IllegalArgumentException("invalid evidence references");
        var references = new ArrayList<String>();
        for (var item : node.get("evidence")) {
            if (!item.isTextual())
                throw new IllegalArgumentException("invalid evidence reference");
            references.add(item.textValue());
        }
        var review = new AgentReview(AgentReviewDecision.valueOf(text(node, "decision")),
                AgentRiskLevel.valueOf(text(node, "risk")), text(node, "binding"),
                gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText.redact(text(node, "reason")),
                references);
        if (!review.binding().equals(action.binding()) || !action.evidence().keySet().containsAll(review.evidence())
                || review.decision() == AgentReviewDecision.ALLOW && review.evidence().isEmpty())
            throw new IllegalArgumentException("unbound review");
        return new Reply<>(review, response.tokens());
    }

    /** Creates nonsecret canonical action input. / 创建非秘密规范动作输入。
     * @param action trusted action / 可信动作
     * @return serialized context / 可序列化上下文
     */
    public static Map<String, Object> action(AgentAction action) {
        return Map.of("id", action.id(), "binding", action.binding(), "task", action.taskId(), "target",
                action.target(), "sourceRevision", action.sourceRevision(), "planRevision", action.planRevision(),
                "tool", action.tool().name(), "parameters", action.parameters(), "evidence", action.evidence(), "risk",
                action.risk().name());
    }
}

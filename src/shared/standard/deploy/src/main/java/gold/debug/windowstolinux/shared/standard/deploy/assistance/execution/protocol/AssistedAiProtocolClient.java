package gold.debug.windowstolinux.shared.standard.deploy.assistance.execution.protocol;

import static gold.debug.windowstolinux.shared.ai.execution.protocol.StrictJson.*;
import static gold.debug.windowstolinux.shared.deploy.approval.DeploymentApprovalClient.action;

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

/** Independent source analysis, checkpoint advice and registered recovery protocols. / 独立源码分析、节点建议及登记恢复协议。 */
public final class AssistedAiProtocolClient {
    /** Versioned assisted recovery policy. / 版本化辅助恢复策略。 */
    public static final String DEPLOYMENT_SKILL = "assisted-v2";

    /** Structured provider transport. / 结构化提供者传输。 */
    private final StructuredAiClient client;

    /** JSON serialization. / JSON 序列化。 */
    private final ObjectMapper json = new ObjectMapper();
    /** Binds the shared transport. / 绑定共享传输。
     * @param client transport / 传输
     */
    public AssistedAiProtocolClient(StructuredAiClient client) {
        this.client = Objects.requireNonNull(client);
    }

    /** Requests an independent read-only analysis step. / 请求独立只读分析步骤。
     * @param endpoint verified endpoint / 已验证端点
     * @param model selected model / 所选模型
     * @param key temporary credential / 临时凭据
     * @param context frozen analysis inputs / 冻结分析输入
     * @param observations actual source observations / 实际源码观察
     * @param remaining shared decision budget / 共享决策预算
     * @return strictly parsed source-only decision / 严格解析的纯源码决定
     * @throws Exception when transport or validation fails / 传输或校验失败时
     */
    public Reply<gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision> analyze(
            URI endpoint, String model, char[] key, Map<String, Object> context, List<Map<String, String>> observations,
            int remaining) throws Exception {
        var response = client.request(endpoint, model, key, AssistedAiProtocolClient.class,
                "/gold/debug/windowstolinux/shared/standard/deploy/assistance/analysis.md",
                Map.of("context", context, "observations", observations, "remaining", remaining));
        var node = response.value();
        exact(node, Set.of("action", "sourceRevision", "argument", "offset", "limit", "advice"));
        if (!node.get("offset").isIntegralNumber() || !node.get("offset").canConvertToInt()
                || !node.get("limit").isIntegralNumber() || !node.get("limit").canConvertToInt())
            throw new IllegalArgumentException("invalid analysis pagination");
        return new Reply<>(
                new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision(
                        text(node, "action"), text(node, "sourceRevision"), text(node, "argument"),
                        node.get("offset").intValue(), node.get("limit").intValue(), advice(node.get("advice"))),
                response.tokens());
    }

    /** Parses evidence-linked advice without granting execution authority. / 解析关联证据的建议，不授予执行权限。
     * @param node strict advice object / 严格建议对象
     * @return bounded advice / 有界建议
     */
    private static gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice advice(JsonNode node) {
        exact(node, Set.of("summary", "suggestions", "unresolved"));
        if (!node.get("suggestions").isArray() || node.get("suggestions").size() > 64)
            throw new IllegalArgumentException("invalid advice suggestions");
        var values = new ArrayList<gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate>();
        for (var item : node.get("suggestions")) {
            exact(item, Set.of("field", "candidate", "evidence"));
            values.add(new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate(
                    text(item, "field"), text(item, "candidate"), stringList(item.get("evidence"))));
        }
        return new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice(text(node, "summary"),
                values, stringList(node.get("unresolved")));
    }

    /** Executes one isolated decision request. / 执行一次独立决策请求。
     * @param endpoint verified endpoint / 已验证端点
     * @param model frozen model name / 冻结模型名称
     * @param key temporary secret buffer / 临时秘密缓冲区
     * @param goal authorized goal / 已授权目标
     * @param actions precise offered actions / 精确候选动作
     * @param history actual execution and rejection history / 实际执行及拒绝历史
     * @param remaining remaining task budget / 任务剩余预算
     * @return strict decision plus reported usage / 严格决策及返回用量
     * @throws Exception on invalid or unavailable responses / 响应无效或不可用时
     */
    public Reply<AgentDecision> decide(URI endpoint, String model, char[] key, String goal, List<AgentAction> actions,
            List<String> history, int remaining) throws Exception {
        var evidenceSets = new LinkedHashMap<String, Map<String, String>>();
        var offered = new ArrayList<Map<String, Object>>();
        for (var candidate : actions) {
            String reference = AgentAction.digest(json.writeValueAsString(new TreeMap<>(candidate.evidence())));
            evidenceSets.putIfAbsent(reference, candidate.evidence());
            var description = new LinkedHashMap<>(action(candidate));
            description.remove("evidence");
            description.put("evidenceReference", reference);
            offered.add(Map.copyOf(description));
        }
        var response = client.request(endpoint, model, key, AssistedAiProtocolClient.class,
                "/gold/debug/windowstolinux/shared/standard/deploy/assistance/deployment.md", Map.of("goal", goal,
                        "actions", offered, "evidenceSets", evidenceSets, "history", history, "remaining", remaining));
        JsonNode node = response.value();
        exact(node, Set.of("decision", "actionId", "binding", "reason"));
        return new Reply<>(
                new AgentDecision(AgentDecisionType.valueOf(text(node, "decision")), text(node, "actionId"),
                        text(node, "binding"),
                        gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText.redact(text(node, "reason"))),
                response.tokens());
    }

    /** Produces evidence-linked advice at a fixed system checkpoint. / 在固定系统节点生成关联证据的建议。
     * @param endpoint verified endpoint / 已验证端点
     * @param model frozen model identity / 冻结模型身份
     * @param key temporary secret / 临时秘密
     * @param phase fixed workflow checkpoint / 固定流程节点
     * @param candidates nonsecret candidates only / 仅非秘密候选
     * @param evidence bounded observed facts / 有界观察事实
     * @return validated suggestions and usage / 已校验建议及用量
     * @throws Exception on invalid output or transport failure / 输出无效或传输失败时
     */
    public Reply<gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice> assist(URI endpoint,
            String model, char[] key, String phase, Map<String, List<String>> candidates, Map<String, String> evidence)
            throws Exception {
        if (!Set.of("PREFLIGHT", "FAILURE").contains(phase))
            throw new IllegalArgumentException("unsupported assisted checkpoint");
        var response = client.request(endpoint, model, key, AssistedAiProtocolClient.class,
                "/gold/debug/windowstolinux/shared/standard/deploy/assistance/assisted.md",
                Map.of("phase", phase, "candidates", candidates, "evidence", evidence));
        var node = response.value();
        exact(node, Set.of("summary", "suggestions", "unresolved"));
        if (!node.get("suggestions").isArray() || node.get("suggestions").size() > 64
                || !node.get("unresolved").isArray())
            throw new IllegalArgumentException("invalid assisted list");
        var suggestions = new ArrayList<gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate>();
        for (var suggestion : node.get("suggestions")) {
            exact(suggestion, Set.of("field", "candidate", "evidence"));
            String field = text(suggestion, "field"), candidate = text(suggestion, "candidate");
            var refs = stringList(suggestion.get("evidence"));
            if (!candidates.getOrDefault(field, List.of()).contains(candidate) || !evidence.keySet().containsAll(refs))
                throw new IllegalArgumentException("unsupported advice");
            suggestions.add(new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate(
                    field, candidate, refs));
        }
        return new Reply<>(new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice(
                text(node, "summary"), suggestions, stringList(node.get("unresolved"))), response.tokens());
    }

    /** Reads bounded string arrays without coercion. / 读取有界字符串数组，不进行强制转换。
     * @param node parsed array / 已解析数组
     * @return copied strings / 复制的字符串
     */
    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 64)
            throw new IllegalArgumentException("string array required");
        var values = new ArrayList<String>();
        for (var value : node) {
            if (!value.isTextual() || value.textValue().length() > 1024)
                throw new IllegalArgumentException("invalid string array");
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }
}

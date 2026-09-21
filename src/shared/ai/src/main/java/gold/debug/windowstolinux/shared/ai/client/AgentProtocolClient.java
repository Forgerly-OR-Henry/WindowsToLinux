package gold.debug.windowstolinux.shared.ai.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionResponseParser;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.ai.transport.*;
import gold.debug.windowstolinux.shared.model.agent.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate strict protocol for deployment decisions and independent reviews. / 部署决策与独立审批的专用严格协议。 */
public final class AgentProtocolClient {
    /** Versioned deployment skill identity. / 版本化部署 Skill 标识。 */
    public static final String DEPLOYMENT_SKILL="deployment-v1";
    /** Versioned independent review skill identity. / 版本化独立审批 Skill 标识。 */
    public static final String APPROVAL_SKILL="approval-v1";
    /** Existing bounded thirty-second transport. / 既有有界三十秒传输。 */
    private final RoleChatTransport transport;
    /** Rejects duplicate properties and trailing JSON values. / 拒绝重复属性及尾随 JSON 值。 */
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** Creates a production protocol client. / 创建生产协议客户端。 */
    public AgentProtocolClient(){this(new HttpRoleChatTransport());}
    /** Injects a bounded transport for integration tests. / 为集成测试注入有界传输。
     * @param transport bounded HTTP transport / 有界 HTTP 传输
     */
    public AgentProtocolClient(RoleChatTransport transport){this.transport=Objects.requireNonNull(transport);}
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
    public Reply<AgentDecision> decide(URI endpoint,String model,char[] key,String goal,List<AgentAction> actions,List<String> history,int remaining)throws Exception{
        var response=request(endpoint,model,key,DEPLOYMENT_SKILL,Map.of("goal",goal,"actions",actions.stream().map(this::action).toList(),"history",history,"remaining",remaining));
        JsonNode node=response.value();exact(node,Set.of("decision","actionId","binding","reason"));
        return new Reply<>(new AgentDecision(AgentDecisionType.valueOf(text(node,"decision")),text(node,"actionId"),text(node,"binding"),text(node,"reason")),response.tokens());
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
    public Reply<AgentReview> review(URI endpoint,String model,char[] key,String goal,AgentAction action,AgentRiskLevel localRisk)throws Exception{
        var response=request(endpoint,model,key,APPROVAL_SKILL,Map.of("goal",goal,"action",action(action),"localRisk",localRisk.name(),"localValidation","passed; rechecked before execution"));
        JsonNode node=response.value();exact(node,Set.of("decision","risk","binding","reason","evidence"));
        if(!node.get("evidence").isArray()||node.get("evidence").size()>128)throw new IllegalArgumentException("invalid evidence references");
        var references=new ArrayList<String>();for(var item:node.get("evidence")){if(!item.isTextual())throw new IllegalArgumentException("invalid evidence reference");references.add(item.textValue());}
        var review=new AgentReview(AgentReviewDecision.valueOf(text(node,"decision")),AgentRiskLevel.valueOf(text(node,"risk")),
                text(node,"binding"),text(node,"reason"),references);
        if(!review.binding().equals(action.binding())||!action.evidence().keySet().containsAll(review.evidence())
                ||review.decision()==AgentReviewDecision.ALLOW&&review.evidence().isEmpty())throw new IllegalArgumentException("unbound review");
        return new Reply<>(review,response.tokens());
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
    public Reply<gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice> assist(URI endpoint,String model,char[] key,String phase,
            Map<String,List<String>> candidates,Map<String,String> evidence)throws Exception{
        if(!Set.of("ANALYSIS","PREFLIGHT","FAILURE").contains(phase))throw new IllegalArgumentException("unsupported assisted checkpoint");
        var response=request(endpoint,model,key,"assisted-v1",Map.of("phase",phase,"candidates",candidates,"evidence",evidence));
        var node=response.value();exact(node,Set.of("summary","suggestions","unresolved"));
        if(!node.get("suggestions").isArray()||node.get("suggestions").size()>64||!node.get("unresolved").isArray())throw new IllegalArgumentException("invalid assisted list");
        var suggestions=new ArrayList<gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate>();
        for(var suggestion:node.get("suggestions")){
            exact(suggestion,Set.of("field","candidate","evidence"));
            String field=text(suggestion,"field"),candidate=text(suggestion,"candidate");var refs=stringList(suggestion.get("evidence"));
            if(!candidates.getOrDefault(field,List.of()).contains(candidate)||!evidence.keySet().containsAll(refs))throw new IllegalArgumentException("unsupported advice");
            suggestions.add(new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate(field,candidate,refs));
        }
        return new Reply<>(new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice(text(node,"summary"),suggestions,stringList(node.get("unresolved"))),response.tokens());
    }
    /** Reads bounded string arrays without coercion. / 读取有界字符串数组，不进行强制转换。
     * @param node parsed array / 已解析数组
     * @return copied strings / 复制的字符串
     */
    private static List<String> stringList(JsonNode node){if(node==null||!node.isArray()||node.size()>64)throw new IllegalArgumentException("string array required");
        var values=new ArrayList<String>();for(var value:node){if(!value.isTextual()||value.textValue().length()>1024)throw new IllegalArgumentException("invalid string array");values.add(value.textValue());}return List.copyOf(values);}
    /** Creates nonsecret canonical action input. / 创建非秘密规范动作输入。
     * @param action trusted action / 可信动作
     * @return serialized context / 可序列化上下文
     */
    private Map<String,Object> action(AgentAction action){return Map.of("id",action.id(),"binding",action.binding(),"task",action.taskId(),"target",action.target(),
        "sourceRevision",action.sourceRevision(),"planRevision",action.planRevision(),"tool",action.tool().name(),"parameters",action.parameters(),"evidence",action.evidence(),"risk",action.risk().name());}
    /** Sends the exact skill and context through the existing transport. / 通过既有传输发送精确 Skill 及上下文。
     * @param endpoint provider endpoint / 提供者端点
     * @param model model identity / 模型身份
     * @param key temporary credential / 临时凭据
     * @param skill versioned skill / 版本化 Skill
     * @param context nonsecret bounded context / 非秘密有界上下文
     * @return strict output and optional token count / 严格输出及可选 Token 数
     * @throws Exception on transport or schema failure / 传输或模式失败时
     */
    private Reply<JsonNode> request(URI endpoint,String model,char[] key,String skill,Map<String,Object> context)throws Exception{
        var policy=new ProviderEndpointPolicy();policy.validateEndpoint(endpoint);policy.requireModel(model);
        if(key==null||key.length==0)throw new IllegalArgumentException("missing credential");
        String input=json.writeValueAsString(context);if(input.length()>65536)throw new IllegalArgumentException("agent context too large");
        String prompt;
        try(var stream=AgentProtocolClient.class.getResourceAsStream("/gold/debug/windowstolinux/shared/ai/skills/"+skill.replace("-v1","")+".md")){
            if(stream==null)throw new IllegalStateException("missing built-in skill");prompt=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        String body=json.writeValueAsString(Map.of("model",model,"temperature",0,"messages",List.of(
                Map.of("role","system","content",prompt),Map.of("role","user","content",input))));
        char[] copy=key.clone();
        try{
            RoleChatResult response=transport.send(endpoint,copy,body);
            if(response.statusCode()<200||response.statusCode()>=300)throw new java.io.IOException("agent provider unavailable");
            String output=new ChatCompletionResponseParser().parse(response.body()).explanation();
            if(output.length()>8192)throw new IllegalArgumentException("agent output too large");
            JsonNode parsed=json.readTree(output),envelope=json.readTree(response.body());
            var usage=envelope.path("usage").path("total_tokens");
            OptionalLong tokens=usage.isIntegralNumber()&&usage.canConvertToLong()&&usage.longValue()>=0?OptionalLong.of(usage.longValue()):OptionalLong.empty();
            return new Reply<>(parsed,tokens);
        }finally{Arrays.fill(copy,'\0');}
    }
    /** Requires exactly the documented fields. / 要求字段与文档完全一致。
     * @param node parsed object / 已解析对象
     * @param fields exact field names / 精确字段名
     */
    private static void exact(JsonNode node,Set<String> fields){var found=new HashSet<String>();if(node==null||!node.isObject())throw new IllegalArgumentException("agent object required");
        node.fieldNames().forEachRemaining(found::add);if(!found.equals(fields))throw new IllegalArgumentException("agent fields do not match schema");}
    /** Reads a required bounded string. / 读取必需有界字符串。
     * @param node containing object / 包含对象
     * @param field field name / 字段名
     * @return exact string / 精确字符串
     */
    private static String text(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isTextual()||value.textValue().length()>2048)throw new IllegalArgumentException("invalid agent string");return value.textValue();}
    /** Model result with usage only when actually reported. / 模型结果，用量仅在实际返回时记录。
     * @param value parsed result / 已解析结果
     * @param tokens provider-reported total tokens / 提供者返回的 Token 总数
     * @param <T> strict output type / 严格输出类型
     */
    public record Reply<T>(T value,OptionalLong tokens){}
}

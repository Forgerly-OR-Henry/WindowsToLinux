package gold.debug.windowstolinux.appapi.ai.model;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** * Bounded model invocation with a deadline and optional requested output budget. / 具有期限及可选申请输出额度的模型调用。
 * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
 * @param messages copied model conversation in request order / 按请求顺序复制的模型对话
 * @param maxOutputTokens optional positive requested budget; empty uses the host recommendation / 可选正数申请额度，空值使用宿主推荐值
 * @param timeout strictly positive operation deadline / 严格为正的操作期限
 * @param jsonObjectResponse whether the response must contain a JSON object / 是否要求响应包含 JSON 对象
 * @param tools copied native function definitions / 复制的原生函数定义
 * @param maxRequestBytes exact encoded request ceiling enforced before network send / 网络发送前执行的精确编码请求字节上限
 * @param requiredToolName optional registered native tool to require; empty retains automatic selection / 可选必须调用的已注册原生工具，空值保留自动选择
 * @param generationRecoveryAttempts extra pure-generation attempts after a connection timeout or incomplete HTTP body, 0..16; no tool or business operation replay / HTTP 连接超时或正文不完整后的额外纯生成次数，0..16，不重放工具或业务操作
 * @param modelId optional or exact platform model configuration identity / 可选或精确的平台模型配置标识
 * @param reasoningOverride empty inherits purpose preferences; DEFAULT explicitly omits controls / 空值继承用途偏好，DEFAULT 明确省略控制参数
 */
public record AiRequest(AiPurpose purpose, List<AiMessage> messages, Optional<Integer> maxOutputTokens,
                        Duration timeout,
                        boolean jsonObjectResponse, List<AiToolDefinition> tools, int maxRequestBytes,
                        Optional<String> requiredToolName, int generationRecoveryAttempts,
                        Optional<String> modelId, Optional<AiReasoningMode> reasoningOverride) {
    /** Captures inputs at the AiRequest ownership boundary. / 在 AiRequest 所有权边界捕获输入。
     * @param purpose declared invocation purpose / 声明的调用用途
     * @param messages complete immutable conversation / 完整不可变对话
     * @param maxOutputTokens captured maxOutputTokens at this validated model boundary / 此已校验模型边界捕获的 maxOutputTokens
     * @param timeout captured timeout at this validated model boundary / 此已校验模型边界捕获的 timeout
     * @param jsonObjectResponse captured jsonObjectResponse at this validated model boundary / 此已校验模型边界捕获的 jsonObjectResponse
     * @param tools captured tools at this validated model boundary / 此已校验模型边界捕获的 tools
     * @param maxRequestBytes captured maxRequestBytes at this validated model boundary / 此已校验模型边界捕获的 maxRequestBytes
     * @param requiredToolName captured requiredToolName at this validated model boundary / 此已校验模型边界捕获的 requiredToolName
     * @param generationRecoveryAttempts captured generationRecoveryAttempts at this validated model boundary / 此已校验模型边界捕获的 generationRecoveryAttempts
     */
    public AiRequest(AiPurpose purpose, List<AiMessage> messages, Optional<Integer> maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse, List<AiToolDefinition> tools, int maxRequestBytes,
                     Optional<String> requiredToolName, int generationRecoveryAttempts) {
        this(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools, maxRequestBytes,
            requiredToolName, generationRecoveryAttempts, Optional.empty(), Optional.empty());
    }

    /** Selects one host configuration without fallback. / 选择一个宿主配置，不切换后备模型。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public AiRequest withModel(String id) {
        return new AiRequest(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools, maxRequestBytes,
            requiredToolName, generationRecoveryAttempts, Optional.of(id), reasoningOverride);
    }

    /** Overrides this request only; DEFAULT explicitly omits provider controls. / 仅覆写本次请求，DEFAULT 明确省略控制参数。
     * @param setting requested reasoning control / 请求的推理控制
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public AiRequest withReasoning(AiReasoningMode setting) {
        return new AiRequest(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools, maxRequestBytes,
            requiredToolName, generationRecoveryAttempts, modelId, Optional.of(setting));
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, Optional<Integer> maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse, List<AiToolDefinition> tools, int maxRequestBytes,
                     Optional<String> requiredToolName) {
        this(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools, maxRequestBytes, requiredToolName, 0);
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, Optional<Integer> maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse, List<AiToolDefinition> tools, int maxRequestBytes) {
        this(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools, maxRequestBytes, Optional.empty());
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, int maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse, List<AiToolDefinition> tools, int maxRequestBytes) {
        this(purpose, messages, Optional.of(maxOutputTokens), timeout, jsonObjectResponse, tools, maxRequestBytes);
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, int maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse, List<AiToolDefinition> tools) {
        this(purpose, messages, Optional.of(maxOutputTokens), timeout, jsonObjectResponse, tools);
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, Optional<Integer> maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse, List<AiToolDefinition> tools) {
        this(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools,
            purpose == AiPurpose.VISION ? 64 * 1024 * 1024 : 1024 * 1024);
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, int maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse) {
        this(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, List.of());
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, Optional<Integer> maxOutputTokens, Duration timeout,
                     boolean jsonObjectResponse) {
        this(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, List.of());
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, Duration timeout,
                     boolean jsonObjectResponse, List<AiToolDefinition> tools) {
        this(purpose, messages, Optional.empty(), timeout, jsonObjectResponse, tools);
    }

    public AiRequest(AiPurpose purpose, List<AiMessage> messages, Duration timeout, boolean jsonObjectResponse) {
        this(purpose, messages, timeout, jsonObjectResponse, List.of());
    }

    /** Copies this request while requiring one registered native tool. The selected provider, model and reasoning settings must support named tool choice; no reasoning change or automatic fallback is applied. / 复制请求并要求一个已注册原生工具；所选提供方、模型及推理设置须支持指定工具，不改变推理设置或自动回退。
     * @param name exact registered native tool name / 精确已注册原生工具名
     * @return immutable request with all original messages and budgets / 保留原消息与预算的不可变请求
     * @throws IllegalArgumentException if the name is not a registered tool / 名称不是已注册工具时 */
    public AiRequest withRequiredTool(String name) {
        return new AiRequest(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools, maxRequestBytes,
            Optional.of(java.util.Objects.requireNonNull(name, "name")), generationRecoveryAttempts, modelId, reasoningOverride);
    }

    /** Selects bounded new generation attempts within this request's original deadline. Each old attempt remains UNKNOWN; a new complete response alone is accepted. / 在原请求期限内选择有限的新生成；旧尝试保持 UNKNOWN，仅接纳新完整响应。
     * @param extraAttempts extra attempts, 0 disables recovery and 16 is the public ceiling / 额外次数，0 关闭，公开上限为 16
     * @return immutable request retaining messages, tools, history and budgets / 保留消息、工具、历史和预算的不可变请求 */
    public AiRequest withGenerationRecovery(int extraAttempts) {
        return new AiRequest(purpose, messages, maxOutputTokens, timeout, jsonObjectResponse, tools, maxRequestBytes,
            requiredToolName, extraAttempts, modelId, reasoningOverride);
    }

    /** Validates and freezes the invocation, selector and optional override. / 校验并冻结调用、选择器及可选覆写。
     * @param purpose declared invocation purpose / 声明的调用用途
     * @param messages complete immutable conversation / 完整不可变对话
     * @param maxOutputTokens optional requested positive output ceiling / 可选正数申请输出上限
     * @param timeout positive operation deadline / 正的操作期限
     * @param jsonObjectResponse whether JSON object output is required / 是否要求 JSON 对象输出
     * @param tools registered native function definitions / 已注册原生函数定义
     * @param maxRequestBytes exact serialized request byte ceiling / 精确序列化请求字节上限
     * @param requiredToolName optional forced registered tool / 可选强制已注册工具
     * @param generationRecoveryAttempts bounded incomplete-body generation recovery attempts / 有界不完整正文生成恢复次数
     * @param modelId exact model configuration or empty for recommendations / 精确模型配置，空值使用推荐
     * @param reasoningOverride empty inherits preferences; DEFAULT explicitly omits controls / 空值继承偏好，DEFAULT 明确省略控制
     */
    public AiRequest {
        java.util.Objects.requireNonNull(purpose, "purpose");
        java.util.Objects.requireNonNull(modelId, "modelId");
        java.util.Objects.requireNonNull(reasoningOverride, "reasoningOverride");
        if (modelId.isPresent() && !modelId.orElseThrow().matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid model configuration identity");
        if (generationRecoveryAttempts < 0 || generationRecoveryAttempts > 16)
            throw new IllegalArgumentException("Generation recovery attempts must be in 0..16");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (tools.size() > 128 || tools.stream().map(AiToolDefinition::name).distinct().count() != tools.size())
            throw new IllegalArgumentException("Duplicate or excessive native tools");
        java.util.Objects.requireNonNull(requiredToolName, "requiredToolName");
        if (requiredToolName.isPresent() && tools.stream().noneMatch(tool -> tool.name().equals(requiredToolName.orElseThrow())))
            throw new IllegalArgumentException("Required native tool is not registered");
        java.util.Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        if (maxOutputTokens.isPresent() && maxOutputTokens.orElseThrow() < 1)
            throw new IllegalArgumentException("maxOutputTokens is below its minimum");
        if (maxRequestBytes < 1 || maxRequestBytes > (purpose == AiPurpose.VISION ? 64 * 1024 * 1024 : 1024 * 1024))
            throw new IllegalArgumentException("maxRequestBytes is outside its bound");
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");
    }
}

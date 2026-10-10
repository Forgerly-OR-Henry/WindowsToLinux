package gold.debug.windowstolinux.appapi.ai.model;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Bounded model invocation with a deadline and optional requested output budget. / 具有期限及可选申请输出额度的模型调用。
 * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
 * @param messages copied model conversation in request order / 按请求顺序复制的模型对话
 * @param maxOutputTokens optional positive requested budget; empty uses the host recommendation / 可选正数申请额度，空值使用宿主推荐值
 * @param timeout strictly positive operation deadline / 严格为正的操作期限
 * @param jsonObjectResponse whether the response must contain a JSON object / 是否要求响应包含 JSON 对象
 * @param tools copied native function definitions / 复制的原生函数定义
 * @param maxRequestBytes exact encoded request ceiling enforced before network send / 网络发送前执行的精确编码请求字节上限
 */
public record AiRequest(AiPurpose purpose, List<AiMessage> messages, Optional<Integer> maxOutputTokens,
                        Duration timeout,
                        boolean jsonObjectResponse, List<AiToolDefinition> tools, int maxRequestBytes) {
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

    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
     * @param messages copied model conversation in request order / 按请求顺序复制的模型对话
     * @param maxOutputTokens optional positive requested output budget / 可选正数申请输出额度
     * @param timeout strictly positive operation deadline / 严格为正的操作期限
     * @param jsonObjectResponse whether the response must contain a JSON object / 是否要求响应包含 JSON 对象
     */
    public AiRequest {
        java.util.Objects.requireNonNull(purpose, "purpose");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (tools.size() > 128 || tools.stream().map(AiToolDefinition::name).distinct().count() != tools.size())
            throw new IllegalArgumentException("Duplicate or excessive native tools");
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

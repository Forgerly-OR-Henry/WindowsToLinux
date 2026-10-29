package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.util.Map;

import gold.debug.windowstolinux.appapi.ai.model.AiRequest;

/** One explicit protocol implementation. */
public interface ModelProtocolCodec {
    byte[] encode(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort,
                  Map<java.util.UUID, String> continuations);

    /** Keeps the original model capability ownership for encode. / 为 encode 保持原模型能力归属。
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param request captured request at this validated model boundary / 此已校验模型边界捕获的 request
     * @param effectiveMaxOutputTokens actual host-resolved output ceiling / 宿主实际解析的输出上限
     * @param effort explicit legacy protocol effort / 显式旧协议强度
     * @param continuations task-owned native continuation payloads / 任务所属原生续接载荷
     * @param adaptation provider customization applied before exact size validation / 精确大小校验前应用的厂商定制
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    byte[] encode(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort,
                  Map<java.util.UUID, String> continuations,
                  java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> adaptation);

    ModelDecodedResponse decode(byte[] bytes, String modelId, boolean jsonObject) throws IOException;

    static ModelProtocolCodec forProtocol(ModelProtocol protocol) {
        return switch (protocol) {
            case OPENAI_CHAT_COMPLETIONS -> new ChatCompletionsCodec();
            case OPENAI_RESPONSES -> new ResponsesCodec();
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesCodec();
        };
    }
}

package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.util.Map;

import gold.debug.windowstolinux.appapi.ai.model.AiRequest;

/** One explicit protocol implementation. */
public interface ModelProtocolCodec {
    byte[] encode(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort,
                  Map<java.util.UUID, String> continuations);

    ModelDecodedResponse decode(byte[] bytes, String modelId, boolean jsonObject) throws IOException;

    static ModelProtocolCodec forProtocol(ModelProtocol protocol) {
        return switch (protocol) {
            case OPENAI_CHAT_COMPLETIONS -> new ChatCompletionsCodec();
            case OPENAI_RESPONSES -> new ResponsesCodec();
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesCodec();
        };
    }
}

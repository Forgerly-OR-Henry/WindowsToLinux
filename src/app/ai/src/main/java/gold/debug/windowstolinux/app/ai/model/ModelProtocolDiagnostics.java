package gold.debug.windowstolinux.app.ai.model;

import java.util.*;

import com.fasterxml.jackson.databind.*;

/** Converts only the selected provider's envelope to the existing numeric, body-free diagnostics. */
final class ModelProtocolDiagnostics {
    private ModelProtocolDiagnostics() {
    }

    static Map<String, String> shape(ModelProtocol protocol, byte[] bytes) {
        if (protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS) return ModelMessageCodec.responseShape(bytes);
        try {
            var root = NativeModelCodec.root(bytes);
            var normalized = NativeModelCodec.JSON.createObjectNode();
            var choice = normalized.putArray("choices").addObject();
            var message = choice.putObject("message");
            var text = new StringBuilder();
            String finish;
            var usage = normalized.putObject("usage");
            var wire = root.path("usage");
            if (protocol == ModelProtocol.OPENAI_RESPONSES) {
                for (var output : root.path("output"))
                    if (output.path("type").asText().equals("message"))
                        for (var content : output.path("content"))
                            if (content.path("type").asText().equals("output_text") && content.path("text").isTextual())
                                text.append(content.path("text").asText());
                finish = root.path("status").asText().equals("completed") ? "stop" : root.at("/incomplete_details/reason").asText().equals("max_output_tokens") ? "length" : "OTHER";
                usage.set("prompt_tokens", wire.path("input_tokens"));
                usage.set("completion_tokens", wire.path("output_tokens"));
                usage.set("prompt_tokens_details", wire.path("input_tokens_details"));
                usage.set("completion_tokens_details", wire.path("output_tokens_details"));
            } else {
                for (var content : root.path("content"))
                    if (content.path("type").asText().equals("text") && content.path("text").isTextual())
                        text.append(content.path("text").asText());
                finish = switch (root.path("stop_reason").asText()) {
                    case "end_turn", "stop_sequence" -> "stop";
                    case "max_tokens" -> "length";
                    case "tool_use" -> "tool_calls";
                    default -> "OTHER";
                };
                var input = ModelMessageCodec.tokens(wire.path("input_tokens"));
                var cached = ModelMessageCodec.tokens(wire.path("cache_read_input_tokens"));
                var created = ModelMessageCodec.tokens(wire.path("cache_creation_input_tokens"));
                if (input.isPresent())
                    usage.put("prompt_tokens", Math.addExact(input.orElseThrow(), Math.addExact(cached.orElse(0L), created.orElse(0L))));
                cached.ifPresent(value -> usage.putObject("prompt_tokens_details").put("cached_tokens", value));
                usage.set("completion_tokens", wire.path("output_tokens"));
            }
            choice.put("finish_reason", finish);
            message.put("content", text.toString());
            return ModelMessageCodec.responseShape(NativeModelCodec.JSON.writeValueAsBytes(normalized));
        } catch (java.io.IOException | RuntimeException invalid) {
            return Map.of("responseEnvelope", "INVALID");
        }
    }
}

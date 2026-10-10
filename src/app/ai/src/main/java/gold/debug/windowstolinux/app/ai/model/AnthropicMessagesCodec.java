package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

final class AnthropicMessagesCodec extends NativeModelCodec {
    @Override
    ObjectNode body(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort, Map<UUID, String> states) {
        if (request.jsonObjectResponse())
            throw new IllegalArgumentException("Anthropic Messages requires an explicit schema or native submission tool for structured output");
        var body = JSON.createObjectNode().put("model", model).put("max_tokens", effectiveMaxOutputTokens).put("stream", false);
        var system = body.putArray("system");
        var messages = body.putArray("messages");
        var images = new int[]{0};
        boolean conversationStarted = false;
        for (var message : request.messages()) {
            if (message.role() == AiRole.SYSTEM) {
                if (conversationStarted || !message.images().isEmpty())
                    throw new IllegalArgumentException("Anthropic system instructions must precede conversation messages");
                system.addObject().put("type", "text").put("text", message.text());
                continue;
            }
            conversationStarted = true;
            String role = message.role() == AiRole.TOOL ? "user" : message.role().name().toLowerCase(Locale.ROOT);
            ArrayNode content;
            if (!messages.isEmpty() && messages.get(messages.size() - 1).path("role").asText().equals(role))
                content = (ArrayNode) messages.get(messages.size() - 1).get("content");
            else content = messages.addObject().put("role", role).putArray("content");
            if (message.continuation().isPresent()) {
                content.addAll((ArrayNode) previous(message, states));
                continue;
            }
            if (message.toolResult().isPresent()) {
                var result = message.toolResult().orElseThrow();
                content.addObject().put("type", "tool_result").put("tool_use_id", result.callId())
                    .put("content", result.text()).put("is_error", result.error());
                continue;
            }
            if (!message.text().isEmpty()) content.addObject().put("type", "text").put("text", message.text());
            if (!message.images().isEmpty()) for (var part : chatContent(message, request.purpose(), images)) {
                if (!part.path("type").asText().equals("image_url")) continue;
                String data = part.path("image_url").path("url").asText();
                int comma = data.indexOf(',');
                content.addObject().put("type", "image").putObject("source").put("type", "base64")
                    .put("media_type", data.substring(5, data.indexOf(';'))).put("data", data.substring(comma + 1));
            }
            for (var call : message.toolCalls())
                content.addObject().put("type", "tool_use").put("id", call.id())
                    .put("name", call.name()).set("input", node(call.arguments()));
        }
        if (system.isEmpty()) body.remove("system");
        if (effort != ModelReasoningLevel.DEFAULT)
            body.putObject("output_config").put("effort", effort.protocolValue());
        if (!request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var tool : request.tools())
                tools.addObject().put("name", tool.name()).put("description", tool.description())
                    .set("input_schema", node(tool.inputSchema()));
            body.putObject("tool_choice").put("type", "auto");
        }
        return body;
    }

    @Override
    public ModelDecodedResponse decode(byte[] bytes, String modelId, boolean object) throws IOException {
        var root = root(bytes);
        var blocks = root.path("content");
        if (!blocks.isArray()) throw new IOException("Anthropic content is incomplete");
        var calls = new ArrayList<AiToolCall>();
        var content = new StringBuilder();
        for (var block : blocks)
            switch (block.path("type").asText()) {
                case "text" -> content.append(content(block, "text"));
                case "tool_use" -> {
                    if (!block.path("input").isObject()) throw new IOException("Anthropic tool input is not an object");
                    calls.add(call(text(block, "id"), text(block, "name"), JSON.writeValueAsString(block.path("input"))));
                }
                case "thinking" -> {
                    text(block, "signature");
                }
                case "redacted_thinking" -> {
                    text(block, "data");
                }
                default -> throw new IOException("Unsupported Anthropic content block");
            }
        String finish = switch (text(root, "stop_reason")) {
            case "end_turn", "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            case "refusal" -> "refusal";
            default -> "incomplete";
        };
        var wire = root.path("usage");
        var input = ModelMessageCodec.tokens(wire.path("input_tokens"));
        var read = ModelMessageCodec.tokens(wire.path("cache_read_input_tokens"));
        var created = ModelMessageCodec.tokens(wire.path("cache_creation_input_tokens"));
        if (input.isPresent()) {
            try {
                input = Optional.of(Math.addExact(input.orElseThrow(), Math.addExact(read.orElse(0L), created.orElse(0L))));
            } catch (ArithmeticException invalid) {
                throw new IOException("Anthropic input usage overflows", invalid);
            }
        }
        AiUsage usage;
        try {
            usage = new AiUsage(input, ModelMessageCodec.tokens(wire.path("output_tokens")), read, Optional.empty(), Optional.empty());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Anthropic usage is inconsistent", invalid);
        }
        return result(content.toString(), modelId, finish, usage, calls, blocks, object);
    }
}

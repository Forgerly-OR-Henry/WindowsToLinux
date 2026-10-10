package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import com.fasterxml.jackson.databind.node.*;

final class ResponsesCodec extends NativeModelCodec {
    @Override
    ObjectNode body(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort, Map<UUID, String> states) {
        var body = JSON.createObjectNode().put("model", model).put("max_output_tokens", effectiveMaxOutputTokens)
            .put("stream", false).put("store", false);
        body.putArray("include").add("reasoning.encrypted_content");
        var input = body.putArray("input");
        var images = new int[]{0};
        for (var message : request.messages()) {
            if (message.continuation().isPresent()) {
                input.addAll((ArrayNode) previous(message, states));
                continue;
            }
            if (message.toolResult().isPresent()) {
                var result = message.toolResult().orElseThrow();
                input.addObject().put("type", "function_call_output").put("call_id", result.callId()).put("output", result.text());
                continue;
            }
            var content = input.addObject().put("type", "message")
                .put("role", message.role().name().toLowerCase(Locale.ROOT)).putArray("content");
            content.addObject().put("type", message.role() == AiRole.ASSISTANT ? "output_text" : "input_text").put("text", message.text());
            if (!message.images().isEmpty()) for (var part : chatContent(message, request.purpose(), images)) {
                if (part.path("type").asText().equals("image_url")) content.addObject().put("type", "input_image")
                    .put("image_url", part.path("image_url").path("url").asText());
            }
            for (var call : message.toolCalls())
                input.addObject().put("type", "function_call")
                    .put("call_id", call.id()).put("name", call.name()).put("arguments", call.arguments());
        }
        if (effort != ModelReasoningLevel.DEFAULT) body.putObject("reasoning").put("effort", effort.protocolValue());
        if (request.jsonObjectResponse()) body.putObject("text").putObject("format").put("type", "json_object");
        if (!request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var tool : request.tools())
                tools.addObject().put("type", "function").put("name", tool.name())
                    .put("description", tool.description()).put("strict", false).set("parameters", node(tool.inputSchema()));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    @Override
    public ModelDecodedResponse decode(byte[] bytes, String modelId, boolean object) throws IOException {
        var root = root(bytes);
        var output = root.path("output");
        if (!output.isArray()) throw new IOException("Responses output is incomplete");
        String status = text(root, "status");
        if (!Set.of("completed", "incomplete").contains(status))
            throw new IOException("Responses response did not complete");
        var calls = new ArrayList<AiToolCall>();
        var content = new StringBuilder();
        boolean refusal = false;
        for (var item : output)
            switch (item.path("type").asText()) {
                case "function_call" ->
                    calls.add(call(text(item, "call_id"), text(item, "name"), text(item, "arguments")));
                case "message" -> {
                    for (var part : item.path("content")) {
                        if (part.path("type").asText().equals("output_text")) content.append(content(part, "text"));
                        else if (part.path("type").asText().equals("refusal")) refusal = true;
                    }
                }
                case "reasoning" -> {
                }
                default -> throw new IOException("Unsupported Responses output item");
            }
        String finish = status.equals("incomplete") ? switch (root.path("incomplete_details").path("reason").asText()) {
            case "max_output_tokens" -> "length";
            case "content_filter" -> "content_filter";
            default -> "incomplete";
        } : refusal ? "refusal" : calls.isEmpty() ? "stop" : "tool_calls";
        var wireUsage = root.path("usage");
        var adapted = JSON.createObjectNode();
        var usage = adapted.putObject("usage");
        usage.set("prompt_tokens", wireUsage.path("input_tokens"));
        usage.set("completion_tokens", wireUsage.path("output_tokens"));
        usage.set("prompt_tokens_details", wireUsage.path("input_tokens_details"));
        usage.set("completion_tokens_details", wireUsage.path("output_tokens_details"));
        return result(content.toString(), modelId, finish, ModelMessageCodec.usage(adapted), calls, output, object);
    }
}

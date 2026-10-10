package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.ai.model.*;

final class ChatCompletionsCodec extends NativeModelCodec {
    @Override
    ObjectNode body(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort, Map<UUID, String> states) {
        var body = JSON.createObjectNode().put("model", model).put("max_tokens", effectiveMaxOutputTokens).put("stream", false);
        var messages = body.putArray("messages");
        var images = new int[]{0};
        for (var message : request.messages()) {
            if (message.continuation().isPresent()) {
                messages.add(previous(message, states));
                continue;
            }
            var item = messages.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT));
            if (message.toolResult().isPresent()) {
                var output = message.toolResult().orElseThrow();
                item.put("tool_call_id", output.callId()).put("content", output.text());
            } else {
                if (message.images().isEmpty()) item.put("content", message.text());
                else item.set("content", chatContent(message, request.purpose(), images));
                chatCalls(item, message);
            }
        }
        if (effort != ModelReasoningLevel.DEFAULT) body.put("reasoning_effort", effort.protocolValue());
        if (request.jsonObjectResponse()) body.putObject("response_format").put("type", "json_object");
        if (!request.tools().isEmpty()) {
            var tools = body.putArray("tools");
            for (var tool : request.tools()) {
                var definition = tools.addObject().put("type", "function").putObject("function")
                    .put("name", tool.name()).put("description", tool.description()).put("strict", false);
                definition.set("parameters", node(tool.inputSchema()));
            }
            if (request.requiredToolName().isPresent())
                body.putObject("tool_choice").put("type", "function").putObject("function")
                    .put("name", request.requiredToolName().orElseThrow());
            else body.put("tool_choice", "auto");
        }
        return body;
    }

    @Override
    public ModelDecodedResponse decode(byte[] bytes, String modelId, boolean object) throws IOException {
        var root = root(bytes);
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) throw new IOException("Model response has no complete choice");
        var choice = choices.get(0);
        var message = choice.path("message");
        if (!message.isObject()) throw new IOException("Model response choice is incomplete");
        var calls = new ArrayList<AiToolCall>();
        var wireCalls = message.path("tool_calls");
        if (!wireCalls.isMissingNode() && !wireCalls.isNull() && !wireCalls.isArray())
            throw new IOException("Invalid tool call array");
        for (var item : wireCalls) {
            if (!item.path("type").asText().equals("function")) throw new IOException("Unsupported tool call type");
            var function = item.path("function");
            calls.add(call(text(item, "id"), text(function, "name"), text(function, "arguments")));
        }
        var content = message.path("content");
        if (!content.isTextual() && !(content.isNull() && !calls.isEmpty()))
            throw new IOException("Model response choice is incomplete");
        String finish = text(choice, "finish_reason");
        if (message.path("refusal").isTextual() && !message.path("refusal").asText().isBlank()) finish = "refusal";
        return result(content.isTextual() ? content.asText() : "", modelId, finish, ModelMessageCodec.usage(root), calls, message, object);
    }
}

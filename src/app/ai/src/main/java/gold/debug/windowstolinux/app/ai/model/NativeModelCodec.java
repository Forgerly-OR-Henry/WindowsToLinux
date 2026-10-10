package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.app.ai.vision.VisionMessageCodec;

/** Shared bounded JSON and native-call validation, without provider detection. */
abstract class NativeModelCodec implements ModelProtocolCodec {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
            .maxStringLength(ModelMessageCodec.MAX_RESPONSE_BYTES).maxNumberLength(32).build()).build())
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    abstract ObjectNode body(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort, Map<UUID, String> states);

    @Override
    public final byte[] encode(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort,
                               Map<UUID, String> states) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(effort);
        Objects.requireNonNull(states);
        if (model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}") || effectiveMaxOutputTokens < 1)
            throw new IllegalArgumentException("Invalid model or output token budget");
        try {
            request.timeout().toNanos();
        } catch (ArithmeticException bad) {
            throw new IllegalArgumentException("Model timeout is too large");
        }
        if (request.messages().isEmpty() || request.messages().size() > 1024)
            throw new IllegalArgumentException("A request requires one to 1024 messages");
        long size = 0;
        var pending = new LinkedHashSet<String>();
        var seen = new HashSet<String>();
        for (var message : request.messages()) {
            size += bytes(message.text());
            if (!message.toolCalls().isEmpty()) {
                if (!pending.isEmpty())
                    throw new IllegalArgumentException("Previous tool calls have no complete results");
                for (var call : message.toolCalls()) {
                    if (!seen.add(call.id())) throw new IllegalArgumentException("Duplicate native call ID in history");
                    pending.add(call.id());
                    size += bytes(call.arguments());
                }
            }
            if (message.toolResult().isPresent()) {
                var result = message.toolResult().orElseThrow();
                if (!pending.remove(result.callId())) throw new IllegalArgumentException("Orphan native tool result");
                size += bytes(result.text());
            } else if (message.role() != AiRole.ASSISTANT && !pending.isEmpty()) {
                throw new IllegalArgumentException("Tool results must follow their assistant calls");
            }
            if (message.continuation().isPresent()) {
                var handle = message.continuation().orElseThrow();
                var state = states.get(handle.id());
                if (state == null) throw new IllegalArgumentException("Native continuation is unavailable");
                if (handle.payloadBytes() != bytes(state))
                    throw new IllegalArgumentException("Native continuation size changed");
                size += bytes(state);
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Native history has unfinished tool calls");
        for (var tool : request.tools()) {
            size += bytes(tool.description()) + bytes(tool.inputSchema());
            var schema = node(tool.inputSchema());
            if (!schema.isObject() || !schema.path("type").asText().equals("object"))
                throw new IllegalArgumentException("Native tool schema must describe an object");
        }
        if (size > 1024 * 1024)
            throw new IllegalArgumentException("Complete model text, schemas and continuation exceed one MiB");
        try {
            byte[] encoded = JSON.writeValueAsBytes(body(model, request, effectiveMaxOutputTokens, effort, states));
            if (encoded.length > request.maxRequestBytes()) {
                Arrays.fill(encoded, (byte) 0);
                throw new ModelRequestBudgetException(encoded.length);
            }
            return encoded;
        } catch (IOException bad) {
            throw new IllegalArgumentException("Model request cannot be encoded");
        }
    }

    private static int bytes(String value) {
        if (value.length() > 1024 * 1024) throw new IllegalArgumentException("Model text is too large");
        try {
            return StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException bad) {
            throw new IllegalArgumentException("Model text is not valid Unicode");
        }
    }

    static JsonNode node(String value) {
        try {
            var result = JSON.readTree(value);
            if (result != null) return result;
        } catch (IOException ignored) {
        }
        throw new IllegalArgumentException("Invalid native JSON document");
    }

    static JsonNode root(byte[] bytes) throws IOException {
        if (bytes.length > ModelMessageCodec.MAX_RESPONSE_BYTES)
            throw new IOException("Model response exceeds its byte limit");
        var value = JSON.readTree(bytes);
        if (value == null || !value.isObject()) throw new IOException("Invalid provider response envelope");
        return value;
    }

    static String text(JsonNode node, String field) throws IOException {
        var value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank())
            throw new IOException("Provider field is incomplete: " + field);
        return value.textValue();
    }

    static String content(JsonNode node, String field) throws IOException {
        var value = node.path(field);
        if (!value.isTextual()) throw new IOException("Provider text field is incomplete");
        return value.textValue();
    }

    static AiToolCall call(String id, String name, String arguments) throws IOException {
        try {
            return new AiToolCall(id, name, arguments);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid provider native call identity", invalid);
        }
    }

    static ModelDecodedResponse result(String content, String modelId, String finish, AiUsage usage,
                                       List<AiToolCall> calls, JsonNode state, boolean object) throws IOException {
        if (finish.isBlank() || finish.length() > 128) throw new IOException("Invalid provider finish reason");
        if (object) {
            var value = JSON.readTree(content);
            if (value == null || !value.isObject()) throw new IOException("Model response is not a JSON object");
        }
        try {
            return new ModelDecodedResponse(new AiResponse(content, modelId, finish, usage, calls, Optional.empty()),
                JSON.writeValueAsString(state));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid provider native call batch", invalid);
        }
    }

    static ArrayNode chatContent(AiMessage message, AiPurpose purpose, int[] imageCount) {
        var holder = JSON.createObjectNode();
        imageCount[0] = VisionMessageCodec.encode(holder, message, purpose, imageCount[0]);
        return (ArrayNode) holder.get("content");
    }

    static JsonNode previous(AiMessage message, Map<UUID, String> states) {
        return node(states.get(message.continuation().orElseThrow().id()));
    }

    static void chatCalls(ObjectNode item, AiMessage message) {
        if (message.toolCalls().isEmpty()) return;
        var calls = item.putArray("tool_calls");
        for (var call : message.toolCalls())
            calls.addObject().put("id", call.id()).put("type", "function")
                .putObject("function").put("name", call.name()).put("arguments", call.arguments());
    }
}

package gold.debug.windowstolinux.app.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.ai.vision.AiImage;

class ModelProtocolCodecTest {
    @Test
    void chatNativeValidationCodesIdentifyOnlyOriginalFailingGuards() throws Exception {
        record Rejected(String name, String code, byte[] body, boolean object) {
        }
        var cases = new ArrayList<Rejected>();
        cases.add(new Rejected("response limit", "1", new byte[ModelMessageCodec.MAX_RESPONSE_BYTES + 1], false));
        cases.add(new Rejected("malformed envelope", "2", bytes("{"), false));
        cases.add(new Rejected("duplicate envelope keys", "2", bytes("{\"choices\":[],\"choices\":[]}"), false));
        cases.add(new Rejected("trailing envelope token", "2", bytes("{} {}"), false));
        cases.add(new Rejected("non-object envelope", "3", bytes("[]"), false));
        cases.add(new Rejected("missing choices", "4", bytes("{}"), false));
        cases.add(new Rejected("empty choices", "4", bytes("{\"choices\":[]}"), false));
        cases.add(new Rejected("message type", "5", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0")).putArray("message")), false));
        cases.add(new Rejected("tool array type", "6", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message")).putObject("tool_calls")), false));
        cases.add(new Rejected("tool type", "7", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message/tool_calls/0")).put("type", "secretmarker")), false));
        cases.add(new Rejected("id field", "8", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message/tool_calls/0")).put("id", 123)), false));
        cases.add(new Rejected("name field", "9", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message/tool_calls/0/function")).put("name", " ")), false));
        cases.add(new Rejected("arguments type", "10", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message/tool_calls/0/function")).putArray("arguments")), false));
        cases.add(new Rejected("arguments empty", "10", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message/tool_calls/0/function")).put("arguments", "")), false));
        cases.add(new Rejected("call identity", "11", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message/tool_calls/0")).put("id", "secretmarker/path")), false));
        cases.add(new Rejected("duplicate call ids", "12", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.at("/choices/0/message/tool_calls"))
            .add(root.at("/choices/0/message/tool_calls/0").deepCopy())), false));
        cases.add(new Rejected("content type", "13", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message")).putObject("content")), false));
        cases.add(new Rejected("null content without calls", "13", chatResponse(root -> {
            var message = (com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message");
            message.putNull("content");
            message.remove("tool_calls");
        }), false));
        cases.add(new Rejected("finish field", "14", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0")).remove("finish_reason")), false));
        cases.add(new Rejected("finish length", "14", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0")).put("finish_reason", "x".repeat(129))), false));
        cases.add(new Rejected("object content shape", "15", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message")).put("content", "[]")), true));
        cases.add(new Rejected("object content parse", "15", chatResponse(root -> ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message")).put("content", "{")), true));
        cases.add(new Rejected("unclassified usage", "0", chatResponse(root -> {
            var usage = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("usage");
            usage.putObject("prompt_tokens_details").put("cached_tokens", 1);
            usage.put("prompt_cache_hit_tokens", 2);
        }), false));
        assertEquals(23, cases.size(), "These are table scenarios inside one JUnit case");
        var codec = ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_CHAT_COMPLETIONS);
        for (var example : cases) {
            var invalid = assertThrows(java.io.IOException.class, () -> codec.decode(example.body(), "fixture", example.object()), example.name());
            assertEquals(example.code(), NativeModelCodec.validationCode(invalid), example.name());
            assertFalse(invalid.getMessage().contains("secretmarker"));
            assertEquals("0", NativeModelCodec.validationCode(new java.io.IOException("secretmarker", invalid)), "No message or cause-chain guessing");
        }
        assertEquals("0", NativeModelCodec.validationCode(new java.io.IOException("secretmarker")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void chatNativeCallsAcceptNullOrEmptyContentWithoutJsonObjectCoercion(boolean nullContent) throws Exception {
        byte[] body = chatResponse(root -> {
            var message = (com.fasterxml.jackson.databind.node.ObjectNode) root.at("/choices/0/message");
            if (nullContent) message.putNull("content");
            else message.put("content", "");
        });
        var codec = ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_CHAT_COMPLETIONS);
        var decoded = codec.decode(body, "fixture", false);
        assertEquals("tool_calls", decoded.response().finishReason());
        assertEquals("", decoded.response().text());
        assertEquals(List.of(new AiToolCall("call_1", "echo", "{}")), decoded.response().toolCalls());
        assertEquals(Optional.of(7L), decoded.response().usage().inputTokens());
        assertEquals(Optional.of(3L), decoded.response().usage().outputTokens());
        var original = json.readTree(body).at("/choices/0/message");
        assertEquals(original, json.readTree(decoded.continuation()));
        var handle = new AiContinuation(UUID.randomUUID(), bytes(decoded.continuation()).length);
        var response = decoded.response();
        var retained = new AiResponse(response.text(), response.modelId(), response.finishReason(), response.usage(), response.toolCalls(), Optional.of(handle));
        var messages = List.of(new AiMessage(AiRole.USER, "public request", List.of()), AiMessage.assistant(retained),
            AiMessage.result(new AiToolResult("call_1", "public result", false)));
        var wire = json.readTree(codec.encode("fixture", request(messages), 128, ModelReasoningLevel.DEFAULT, Map.of(handle.id(), decoded.continuation())));
        assertEquals(original, wire.at("/messages/1"));
        if (!nullContent)
            assertEquals("NON_OBJECT", ModelProtocolDiagnostics.shape(ModelProtocol.OPENAI_CHAT_COMPLETIONS, body).get("jsonFailureKind"),
                "A content-shape projection is not a native decoder rejection");
    }

    private byte[] chatResponse(java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> change) throws Exception {
        var root = json.createObjectNode();
        var message = root.putArray("choices").addObject().put("finish_reason", "tool_calls").putObject("message")
            .put("role", "assistant").put("content", "");
        message.putArray("tool_calls").addObject().put("type", "function").put("id", "call_1")
            .putObject("function").put("name", "echo").put("arguments", "{}");
        root.putObject("usage").put("prompt_tokens", 7).put("completion_tokens", 3);
        change.accept(root);
        return json.writeValueAsBytes(root);
    }

    @Test
    void explicitNoneUsesEachProtocolsDisabledReasoningFieldWithoutChangingDefault() throws Exception {
        var request = new AiRequest(AiPurpose.APPROVAL,
            List.of(new AiMessage(AiRole.USER, "Review the current command", List.of())),
            8192, Duration.ofSeconds(30), false);
        for (var protocol : ModelProtocol.values()) {
            var codec = ModelProtocolCodec.forProtocol(protocol);
            var baseline = json.readTree(codec.encode("fixture", request, 8192, ModelReasoningLevel.DEFAULT, Map.of()));
            var disabled = json.readTree(codec.encode("fixture", request, 8192, ModelReasoningLevel.NONE, Map.of()));
            String field;
            switch (protocol) {
                case OPENAI_CHAT_COMPLETIONS -> {
                    field = "reasoning_effort";
                    assertEquals("none", disabled.path(field).textValue());
                }
                case OPENAI_RESPONSES -> {
                    field = "reasoning";
                    assertEquals("none", disabled.path(field).path("effort").textValue());
                }
                case ANTHROPIC_MESSAGES -> {
                    field = "thinking";
                    assertEquals("disabled", disabled.path(field).path("type").textValue());
                    assertFalse(disabled.has("output_config"));
                }
                default -> throw new AssertionError(protocol);
            }
            assertFalse(baseline.has(field));
            ((com.fasterxml.jackson.databind.node.ObjectNode) disabled).remove(field);
            assertEquals(baseline, disabled);
        }
    }

    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    private final AiToolDefinition tool = new AiToolDefinition("echo", "echo challenge",
        "{\"type\":\"object\",\"properties\":{\"challenge\":{\"type\":\"string\"}},\"required\":[\"challenge\"],\"additionalProperties\":false}");

    private AiRequest request(List<AiMessage> messages) {
        return new AiRequest(AiPurpose.LANGUAGE, messages, 128, Duration.ofSeconds(10), false, List.of(tool));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void requiredNativeToolChangesOnlyTheChoiceAndPreservesAutomaticDefaults() throws Exception {
        var messages = List.of(new AiMessage(AiRole.SYSTEM, "Review the current evidence", List.of()),
            new AiMessage(AiRole.USER, "完整证据", List.of()));
        var submit = new AiToolDefinition("submit_result", "Submit the complete result", "{\"type\":\"object\"}");
        var original = new AiRequest(AiPurpose.APPROVAL, messages, 128, Duration.ofSeconds(17), false, List.of(tool, submit));
        var required = original.withRequiredTool("submit_result");
        var noTools = new AiRequest(AiPurpose.APPROVAL, messages, 128, Duration.ofSeconds(17), false);
        for (var protocol : ModelProtocol.values()) {
            var codec = ModelProtocolCodec.forProtocol(protocol);
            assertFalse(json.readTree(codec.encode("model", noTools, 64, ModelReasoningLevel.DEFAULT, Map.of())).has("tool_choice"));
            for (var effort : List.of(ModelReasoningLevel.DEFAULT, ModelReasoningLevel.HIGH)) {
                var baseline = json.readTree(codec.encode("model", original, 64, effort, Map.of()));
                var named = json.readTree(codec.encode("model", required, 64, effort, Map.of()));
                String expected = switch (protocol) {
                    case OPENAI_CHAT_COMPLETIONS -> "{\"type\":\"function\",\"function\":{\"name\":\"submit_result\"}}";
                    case OPENAI_RESPONSES -> "{\"type\":\"function\",\"name\":\"submit_result\"}";
                    case ANTHROPIC_MESSAGES -> "{\"type\":\"tool\",\"name\":\"submit_result\"}";
                };
                assertEquals(json.readTree(expected), named.path("tool_choice"));
                assertEquals(protocol == ModelProtocol.ANTHROPIC_MESSAGES ? json.readTree("{\"type\":\"auto\"}")
                    : json.getNodeFactory().textNode("auto"), baseline.path("tool_choice"));
                assertEquals(64, named.path(protocol == ModelProtocol.OPENAI_RESPONSES ? "max_output_tokens" : "max_tokens").intValue());
                assertEquals(2, named.path("tools").size());
                ((com.fasterxml.jackson.databind.node.ObjectNode) named).remove("tool_choice");
                ((com.fasterxml.jackson.databind.node.ObjectNode) baseline).remove("tool_choice");
                assertEquals(baseline, named, "Named tool selection must preserve complete messages, reasoning settings and host budgets");
            }
        }
        assertArrayEquals(ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            .encode("model", required, 64, ModelReasoningLevel.DEFAULT, Map.of()), ModelMessageCodec.encode("model", required, 64));
    }

    @Test
    void requiredToolChoiceRemainsInsideEachProtocolsExactEncodedBodyLimit() throws Exception {
        var original = request(List.of(new AiMessage(AiRole.USER, "完整证据", List.of()))).withRequiredTool("echo");
        for (var protocol : ModelProtocol.values()) {
            var codec = ModelProtocolCodec.forProtocol(protocol);
            byte[] encoded = codec.encode("model", original, 64, ModelReasoningLevel.DEFAULT, Map.of());
            var exact = new AiRequest(original.purpose(), original.messages(), original.maxOutputTokens(), original.timeout(),
                original.jsonObjectResponse(), original.tools(), encoded.length, original.requiredToolName());
            assertArrayEquals(encoded, codec.encode("model", exact, 64, ModelReasoningLevel.DEFAULT, Map.of()));
            var tooSmall = new AiRequest(original.purpose(), original.messages(), original.maxOutputTokens(), original.timeout(),
                original.jsonObjectResponse(), original.tools(), encoded.length - 1, original.requiredToolName());
            var rejected = assertThrows(ModelRequestBudgetException.class,
                () -> codec.encode("model", tooSmall, 64, ModelReasoningLevel.DEFAULT, Map.of()));
            assertEquals(encoded.length, rejected.encodedBytes());
        }
    }

    @Test
    void allProtocolsEncodeTheHostBudgetRatherThanTheRequestedBudget() throws Exception {
        var messages = List.of(new AiMessage(AiRole.USER, "hello", List.of()));
        for (var protocol : ModelProtocol.values()) {
            var codec = ModelProtocolCodec.forProtocol(protocol);
            for (Optional<Integer> requested : List.of(Optional.<Integer>empty(), Optional.of(1), Optional.of(65536))) {
                var request = new AiRequest(AiPurpose.LANGUAGE, messages, requested, Duration.ofSeconds(10), false);
                for (int effective : new int[]{1, 32768, 65536}) {
                    var wire = json.readTree(codec.encode("model", request, effective, ModelReasoningLevel.DEFAULT, Map.of()));
                    String key = protocol == ModelProtocol.OPENAI_RESPONSES ? "max_output_tokens" : "max_tokens";
                    assertTrue(wire.path(key).isInt());
                    assertEquals(effective, wire.path(key).intValue());
                }
                assertThrows(IllegalArgumentException.class, () -> codec.encode("model", request, 0, ModelReasoningLevel.DEFAULT, Map.of()));
            }
        }
    }

    @Test
    void nativeRoundTripPreservesProviderStateAndUsageForAllExplicitProtocols() throws Exception {
        var envelopes = Map.of(
            ModelProtocol.OPENAI_CHAT_COMPLETIONS, """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"opaque reasoning","tool_calls":[{"id":"call_1","type":"function","function":{"name":"echo","arguments":"{\"challenge\":\"abc\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":3}}
                """.replace("{\"challenge\":\"abc\"}", "{\\\"challenge\\\":\\\"abc\\\"}"),
            ModelProtocol.OPENAI_RESPONSES, """
                {"status":"completed","output":[{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque reasoning"},{"type":"function_call","id":"fc_1","call_id":"call_1","name":"echo","arguments":"{\"challenge\":\"abc\"}"}],"usage":{"input_tokens":10,"output_tokens":3}}
                """.replace("{\"challenge\":\"abc\"}", "{\\\"challenge\\\":\\\"abc\\\"}"),
            ModelProtocol.ANTHROPIC_MESSAGES, """
                {"stop_reason":"tool_use","content":[{"type":"thinking","thinking":"opaque reasoning","signature":"signed"},{"type":"tool_use","id":"call_1","name":"echo","input":{"challenge":"abc"}}],"usage":{"input_tokens":6,"cache_read_input_tokens":4,"output_tokens":3}}
                """);
        for (var protocol : ModelProtocol.values()) {
            var codec = ModelProtocolCodec.forProtocol(protocol);
            var decoded = codec.decode(bytes(envelopes.get(protocol)), "model-id", false);
            assertEquals("tool_calls", decoded.response().finishReason());
            assertEquals(1, decoded.response().toolCalls().size());
            assertEquals(Optional.of(10L), decoded.response().usage().inputTokens());
            assertEquals(Optional.of(3L), decoded.response().usage().outputTokens());
            assertFalse(decoded.toString().contains("opaque reasoning"));
            var handle = new AiContinuation(UUID.randomUUID(), bytes(decoded.continuation()).length);
            var response = decoded.response();
            var assistant = new AiMessage(AiRole.ASSISTANT, response.text(), List.of(), response.toolCalls(), Optional.empty(), Optional.of(handle));
            var messages = List.of(new AiMessage(AiRole.USER, "echo", List.of()), assistant,
                AiMessage.result(new AiToolResult("call_1", "returned challenge", false)));
            var wire = json.readTree(codec.encode("model", request(messages), 128, ModelReasoningLevel.DEFAULT, Map.of(handle.id(), decoded.continuation())));
            assertTrue(wire.toString().contains("opaque reasoning"));
            assertTrue(wire.toString().contains("returned challenge"));
            var bounded = new AiRequest(AiPurpose.LANGUAGE, messages, 128, Duration.ofSeconds(10), false, List.of(tool), 16);
            var rejected = assertThrows(ModelRequestBudgetException.class,
                () -> codec.encode("model", bounded, 128, ModelReasoningLevel.DEFAULT, Map.of(handle.id(), decoded.continuation())));
            assertTrue(rejected.encodedBytes() > 16);
            var forged = new AiMessage(AiRole.ASSISTANT, response.text(), List.of(), response.toolCalls(), Optional.empty(),
                Optional.of(new AiContinuation(handle.id(), 1)));
            assertThrows(IllegalArgumentException.class, () -> codec.encode("model",
                request(List.of(messages.getFirst(), forged, messages.getLast())), 128, ModelReasoningLevel.DEFAULT, Map.of(handle.id(), decoded.continuation())));
            switch (protocol) {
                case OPENAI_CHAT_COMPLETIONS -> assertEquals("call_1", wire.at("/messages/2/tool_call_id").asText());
                case OPENAI_RESPONSES -> assertEquals("function_call_output", wire.at("/input/3/type").asText());
                case ANTHROPIC_MESSAGES -> {
                    assertEquals("signed", wire.at("/messages/1/content/0/signature").asText());
                    assertEquals("call_1", wire.at("/messages/2/content/0/tool_use_id").asText());
                }
            }
        }
    }

    @Test
    void rejectsOrphansDuplicateIdsMissingStateAndWrongEnvelopeInsteadOfSwitchingProtocol() {
        var user = new AiMessage(AiRole.USER, "hello", List.of());
        var orphan = request(List.of(user, AiMessage.result(new AiToolResult("missing", "value", false))));
        for (var protocol : ModelProtocol.values()) {
            var codec = ModelProtocolCodec.forProtocol(protocol);
            assertThrows(IllegalArgumentException.class, () -> codec.encode("model", orphan, 128, ModelReasoningLevel.DEFAULT, Map.of()));
            assertThrows(java.io.IOException.class, () -> codec.decode(bytes("{}"), "id", false));
        }
        assertThrows(java.io.IOException.class, () -> ModelProtocolCodec.forProtocol(ModelProtocol.ANTHROPIC_MESSAGES)
            .decode(bytes("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"), "id", false));
        assertThrows(IllegalArgumentException.class, () -> new AiResponse("", "id", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall("same", "echo", "{}"), new AiToolCall("same", "echo", "{}")), Optional.empty()));
    }

    @Test
    void textAndImageEncodeBySelectedProtocolAndAnthropicDoesNotPretendJsonMode() throws Exception {
        var image = new AiImage("image/png", Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jTXYAAAAASUVORK5CYII="));
        var request = new AiRequest(AiPurpose.VISION, List.of(new AiMessage(AiRole.USER, "describe", List.of(image))),
            128, Duration.ofSeconds(10), false);
        for (var protocol : ModelProtocol.values()) {
            var wire = json.readTree(ModelProtocolCodec.forProtocol(protocol).encode("model", request, 128, ModelReasoningLevel.DEFAULT, Map.of()));
            assertTrue(wire.toString().contains(protocol == ModelProtocol.ANTHROPIC_MESSAGES ? "base64" : "data:image/png;base64"));
        }
        var object = new AiRequest(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "{}", List.of())), 128, Duration.ofSeconds(10), true);
        assertThrows(IllegalArgumentException.class, () -> ModelProtocolCodec.forProtocol(ModelProtocol.ANTHROPIC_MESSAGES)
            .encode("model", object, 128, ModelReasoningLevel.DEFAULT, Map.of()));
    }
}

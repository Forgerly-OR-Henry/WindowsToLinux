package gold.debug.windowstolinux.app.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.ai.vision.AiImage;

class ModelProtocolCodecTest {
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

package gold.debug.windowstolinux.app.ai.provider.adapter;

import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelProviderRegistryTest {
    @Test
    void exactModelsHaveDistinctControlsAndUnknownAliasesAreNotGuessed() {
        var registry = ModelProviderCatalog.create();
        var protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS;
        assertEquals(AiReasoningMode.MEDIUM, registry.initial(ModelProviderType.OPENAI, "gpt-6.1-sol", protocol, "MEDIUM", "STANDARD"));
        assertFalse(registry.capabilities(ModelProviderType.OPENAI, "gpt-6.1-sol", protocol).options().contains(AiReasoningMode.STANDARD));
        assertEquals(AiReasoningMode.HIGH, registry.initial(ModelProviderType.DEEPSEEK, "deepseek-flash", protocol, "MEDIUM", "STANDARD"));
        assertEquals(AiReasoningMode.STANDARD, registry.initial(ModelProviderType.QWEN, "qwen3.8-max", protocol, "MEDIUM", "STANDARD"));
        assertEquals(AiReasoningMode.DEFAULT, registry.initial(ModelProviderType.GEMINI, "gemini-3-pro-preview", protocol, "MEDIUM", "STANDARD"));
        assertFalse(registry.capabilities(ModelProviderType.OPENAI, "gpt-6.1-sol-unreviewed", protocol).known());
        assertFalse(registry.capabilities(ModelProviderType.THIRD_PARTY, "gpt-6.1-sol", protocol).known());
        assertFalse(registry.capabilities(ModelProviderType.QWEN, "qwen3.8-max", ModelProtocol.ANTHROPIC_MESSAGES).known());
        assertFalse(registry.capabilities(ModelProviderType.OPENAI, "gpt-5.1", protocol).options().contains(AiReasoningMode.XHIGH));
        for (var type : List.of(ModelProviderType.DEEPSEEK, ModelProviderType.QWEN, ModelProviderType.THIRD_PARTY)) {
            var generic = new ObjectMapper().createObjectNode();
            registry.adapter(type).apply(generic, "unreviewed", protocol, AiReasoningMode.HIGH);
            assertEquals("high", generic.path("reasoning_effort").asText());
            assertFalse(generic.has("enable_thinking"));
            assertFalse(generic.has("thinking"));
        }
    }

    @Test
    void sixProviderControlsAreEncodedBeforeTheFinalBudgetCheck() throws Exception {
        var registry = ModelProviderCatalog.create();
        var request = new AiRequest(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "test", List.of())), 1024, Duration.ofSeconds(5), false);
        var json = new ObjectMapper();
        record Sample(ModelProviderType type, String model, ModelProtocol protocol, AiReasoningMode setting,
                      String pointer, String value) {
        }
        for (var sample : List.of(
            new Sample(ModelProviderType.OPENAI, "gpt-6.1-sol", ModelProtocol.OPENAI_CHAT_COMPLETIONS, AiReasoningMode.MEDIUM, "/reasoning_effort", "medium"),
            new Sample(ModelProviderType.ANTHROPIC, "claude-opus-4-6", ModelProtocol.ANTHROPIC_MESSAGES, AiReasoningMode.HIGH, "/output_config/effort", "high"),
            new Sample(ModelProviderType.GEMINI, "gemini-3.8-flash", ModelProtocol.OPENAI_CHAT_COMPLETIONS, AiReasoningMode.LOW, "/reasoning_effort", "low"),
            new Sample(ModelProviderType.GROK, "grok-4.7", ModelProtocol.OPENAI_RESPONSES, AiReasoningMode.XHIGH, "/reasoning/effort", "xhigh"),
            new Sample(ModelProviderType.DEEPSEEK, "deepseek-flash", ModelProtocol.OPENAI_CHAT_COMPLETIONS, AiReasoningMode.STANDARD, "/thinking/type", "disabled"),
            new Sample(ModelProviderType.QWEN, "qwen3.8-max", ModelProtocol.OPENAI_CHAT_COMPLETIONS, AiReasoningMode.STANDARD, "/enable_thinking", "false"))) {
            var encoded = ModelProtocolCodec.forProtocol(sample.protocol()).encode(sample.model(), request, 1024,
                ModelReasoningLevel.DEFAULT, Map.of(), body -> registry.adapter(sample.type()).apply(body, sample.model(), sample.protocol(), sample.setting()));
            var body = json.readTree(encoded);
            assertEquals(sample.value(), body.at(sample.pointer()).asText(), sample.type().name());
            if (sample.type() == ModelProviderType.OPENAI) {
                assertEquals(1024, body.path("max_completion_tokens").intValue());
                assertFalse(body.has("max_tokens"));
            }
            if (sample.type() == ModelProviderType.ANTHROPIC)
                assertEquals("adaptive", body.at("/thinking/type").asText());
            assertThrows(ModelRequestBudgetException.class, () -> ModelProtocolCodec.forProtocol(sample.protocol()).encode(sample.model(),
                new AiRequest(request.purpose(), request.messages(), 1024, request.timeout(), false, List.of(), encoded.length - 1),
                1024, ModelReasoningLevel.DEFAULT, Map.of(), payload -> registry.adapter(sample.type()).apply(payload, sample.model(), sample.protocol(), sample.setting())));
        }
    }

    @Test
    void anthropicDefaultAndStandardKeepDistinctThinkingBehavior() {
        var adapter = new AnthropicModelAdapter();
        var body = new ObjectMapper().createObjectNode();
        adapter.apply(body, "claude-opus-4-6", ModelProtocol.ANTHROPIC_MESSAGES, AiReasoningMode.DEFAULT);
        assertTrue(body.isEmpty());
        adapter.apply(body, "claude-opus-4-6", ModelProtocol.ANTHROPIC_MESSAGES, AiReasoningMode.STANDARD);
        assertEquals("disabled", body.at("/thinking/type").asText());
        assertFalse(adapter.capabilities("claude-opus-5-5", ModelProtocol.ANTHROPIC_MESSAGES).options().contains(AiReasoningMode.STANDARD));
        var effortOnly = new ObjectMapper().createObjectNode();
        adapter.apply(effortOnly, "claude-opus-4-5", ModelProtocol.ANTHROPIC_MESSAGES, AiReasoningMode.MEDIUM);
        assertEquals("medium", effortOnly.at("/output_config/effort").asText());
        assertFalse(effortOnly.has("thinking"), "The legacy budget-only model does not receive an invented thinking token budget");
    }

    @Test
    void unsupportedToolAndReasoningCombinationsAreRejectedWithoutDowngrading() {
        var request = new AiRequest(AiPurpose.APPROVAL, List.of(new AiMessage(AiRole.USER, "test", List.of())), 1024,
            Duration.ofSeconds(5), false, List.of(new AiToolDefinition("approve", "Approval", "{\"type\":\"object\"}"))).withRequiredTool("approve");
        for (var model : List.of("gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna")) {
            assertThrows(ModelProviderRequestException.class, () -> ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
                .encode(model, request, 1024, ModelReasoningLevel.DEFAULT, Map.of(),
                    body -> new OpenAiModelAdapter().apply(body, model, ModelProtocol.OPENAI_CHAT_COMPLETIONS, AiReasoningMode.HIGH)));
        }
        assertThrows(ModelProviderRequestException.class, () -> ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            .encode("deepseek-flash", request, 1024, ModelReasoningLevel.DEFAULT, Map.of(),
                body -> new DeepSeekModelAdapter().apply(body, "deepseek-flash", ModelProtocol.OPENAI_CHAT_COMPLETIONS, AiReasoningMode.DEFAULT)));
        assertDoesNotThrow(() -> ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            .encode("deepseek-flash", request, 1024, ModelReasoningLevel.DEFAULT, Map.of(),
                body -> new DeepSeekModelAdapter().apply(body, "deepseek-flash", ModelProtocol.OPENAI_CHAT_COMPLETIONS, AiReasoningMode.STANDARD)));
    }
}

package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.service.ai.AiModelProfile;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformNativeModelTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"OPENAI_CHAT_COMPLETIONS,true", "OPENAI_CHAT_COMPLETIONS,false", "OPENAI_RESPONSES,true", "OPENAI_RESPONSES,false", "ANTHROPIC_MESSAGES,true", "ANTHROPIC_MESSAGES,false"})
    void nativeRoundTripUsesSelectedProtocolEvenWhenUrlLooksLikeChat(ModelProtocol protocol, boolean allow) throws Exception {
        var json = PlatformAiServiceTest.JSON;
        var turns = new AtomicInteger();
        try (var fixture = new PlatformAiServiceTest.Fixture(); var runtime = PlatformOutputTokenPolicyTest.open(root, allow)) {
            var service = PlatformModelServiceTest.setup(runtime);
            fixture.handler.set(exchange -> {
                try {
                    var wire = fixture.requests.remove().body();
                    var response = json.createObjectNode();
                    assertEquals("/v1/chat/completions", exchange.getRequestURI().getPath());
                    assertEquals(8192, wire.path(protocol == ModelProtocol.OPENAI_RESPONSES ? "max_output_tokens" : "max_tokens").intValue());
                    if (protocol == ModelProtocol.ANTHROPIC_MESSAGES) {
                        assertEquals("fixture-key", exchange.getRequestHeaders().getFirst("x-api-key"));
                        assertEquals("2023-06-01", exchange.getRequestHeaders().getFirst("anthropic-version"));
                        assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    } else assertEquals("Bearer fixture-key", exchange.getRequestHeaders().getFirst("Authorization"));
                    boolean first = turns.getAndIncrement() == 0;
                    String value;
                    if (first) {
                        var matcher = java.util.regex.Pattern.compile("[0-9a-f-]{36}").matcher(wire.toString());
                        assertTrue(matcher.find());
                        value = matcher.group();
                    } else value = switch (protocol) {
                        case OPENAI_CHAT_COMPLETIONS -> wire.at("/messages/2/content").asText();
                        case OPENAI_RESPONSES -> wire.at("/input/2/output").asText();
                        case ANTHROPIC_MESSAGES -> wire.at("/messages/2/content/0/content").asText();
                    };
                    var args = json.createObjectNode().put("challenge", value);
                    switch (protocol) {
                        case OPENAI_CHAT_COMPLETIONS -> {
                            assertTrue(wire.has("messages"));
                            assertFalse(wire.has("input"));
                            var choice = response.putArray("choices").addObject().put("finish_reason", first ? "tool_calls" : "stop");
                            var message = choice.putObject("message").put("role", "assistant");
                            if (first) {
                                message.putNull("content").put("reasoning_content", "private");
                                message.putArray("tool_calls").addObject().put("id", "probe_1").put("type", "function").putObject("function").put("name", "roundtrip_probe").put("arguments", args.toString());
                            } else {
                                assertEquals("private", wire.at("/messages/1/reasoning_content").asText());
                                message.put("content", value);
                            }
                        }
                        case OPENAI_RESPONSES -> {
                            assertTrue(wire.has("input"));
                            assertFalse(wire.has("messages"));
                            response.put("status", "completed");
                            var output = response.putArray("output");
                            if (first)
                                output.addObject().put("type", "function_call").put("id", "fc_1").put("call_id", "probe_1").put("name", "roundtrip_probe").put("arguments", args.toString());
                            else
                                output.addObject().put("type", "message").put("role", "assistant").putArray("content").addObject().put("type", "output_text").put("text", value);
                        }
                        case ANTHROPIC_MESSAGES -> {
                            response.put("stop_reason", first ? "tool_use" : "end_turn");
                            var content = response.putArray("content");
                            if (first) {
                                content.addObject().put("type", "thinking").put("thinking", "private").put("signature", "signature");
                                content.addObject().put("type", "tool_use").put("id", "probe_1").put("name", "roundtrip_probe").set("input", args);
                            } else {
                                assertEquals("signature", wire.at("/messages/1/content/0/signature").asText());
                                content.addObject().put("type", "text").put("text", value);
                            }
                        }
                    }
                    PlatformAiServiceTest.Fixture.reply(exchange, 200, response.toString());
                } catch (AssertionError error) {
                    PlatformAiServiceTest.Fixture.reply(exchange, 500, "{}");
                    throw error;
                }
            });
            var profile = new AiModelProfile("native", fixture.uri("/v1/chat/completions"), "model", "ai/native", null, null, ModelReasoningLevel.DEFAULT, protocol);
            service.save(profile, "Native", "fixture-key".toCharArray(), 256);
            var result = service.verify("native", AiCapabilityType.TOOL_CALLING, null, "").completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.problem().toString());
            var saved = service.listAiConfigurations().getFirst();
            assertTrue(saved.verified(AiCapabilityType.TOOL_CALLING));
            assertFalse(saved.verified(AiCapabilityType.TEXT));
            assertEquals(protocol, saved.profile().protocol());
            assertEquals(2, turns.get());
        }
    }
}

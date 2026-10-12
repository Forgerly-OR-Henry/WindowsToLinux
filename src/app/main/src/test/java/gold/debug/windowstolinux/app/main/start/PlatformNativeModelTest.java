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
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void nativeRetentionFailureReturnsOnlyActualMetricsStopsItsSessionAndNeverRetriesTheProvider(boolean failFirstResponse) throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture(); var runtime = PlatformOutputTokenPolicyTest.open(root, true)) {
            PlatformTaskAdmissionIntegrationTest.initialize(runtime, false);
            PlatformAiServiceTest.model(runtime, fixture, "model", "/model", AiCapabilityType.TEXT, 1);
            var stored = runtime.persistence().aiProfiles().listConfigured().getFirst();
            runtime.persistence().aiProfiles().recordVerification(stored.profile(), stored.revision(), AiCapabilityType.TOOL_CALLING, java.time.Instant.now());
            PlatformAiServiceTest.route(runtime, AiPurposeType.DEPLOYMENT, "model");
            fixture.handler.set(exchange -> PlatformAiServiceTest.Fixture.reply(exchange, 200,
                "{\"model\":\"fixture-model\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":\"private response\",\"tool_calls\":[{\"id\":\"call_" + fixture.calls.get()
                    + "\",\"type\":\"function\",\"function\":{\"name\":\"unbound_action\",\"arguments\":\"{}\"}}]}}],\"usage\":{\"prompt_tokens\":23,\"completion_tokens\":7}}"));
            var base = gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig().ai();
            var settings = new gold.debug.windowstolinux.app.config.AppConfig.Ai(base.connectSeconds(), base.recommendedMaxOutputTokens(),
                base.allowOutputTokenOverride(), base.verificationMaxOutputTokens(),
                new gold.debug.windowstolinux.app.config.AppConfig.NativeState(1, 1048576, failFirstResponse ? 1 : 1048576), base.recovery(), base.deletion(), base.file());
            try (var ai = new gold.debug.windowstolinux.app.service.ai.PlatformAiService(runtime.persistence().database(), runtime.tasks(),
                runtime.security(), runtime.persistence().aiProfiles(), PlatformTaskAdmissionIntegrationTest.OWNER, PlatformTaskAdmissionIntegrationTest.VERSION, settings)) {
                var tools = List.of(new gold.debug.windowstolinux.appapi.ai.model.AiToolDefinition("unbound_action", "No automatic execution", "{\"type\":\"object\"}"));
                PlatformServerRefTest.completed(runtime.tasks().bind(PlatformTaskAdmissionIntegrationTest.OWNER, PlatformTaskAdmissionIntegrationTest.VERSION, false)
                    .submit(PlatformTaskAdmissionIntegrationTest.request(), task -> {
                        var initial = PlatformAiServiceTest.message(gold.debug.windowstolinux.appapi.ai.model.AiPurpose.LANGUAGE);
                        var request = new gold.debug.windowstolinux.appapi.ai.model.AiRequest(initial.purpose(), initial.messages(), 1234,
                            java.time.Duration.ofSeconds(6), false, tools);
                        gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.ai.model.AiResponse> second;
                        if (failFirstResponse) second = ai.call(request, task);
                        else {
                            var first = ai.call(request, task).value().orElseThrow();
                            assertTrue(first.continuation().isPresent());
                            var history = new ArrayList<>(initial.messages());
                            history.add(gold.debug.windowstolinux.appapi.ai.model.AiMessage.assistant(first));
                            history.add(gold.debug.windowstolinux.appapi.ai.model.AiMessage.result(
                                new gold.debug.windowstolinux.appapi.ai.model.AiToolResult("call_1", "observed result", false)));
                            second = ai.call(new gold.debug.windowstolinux.appapi.ai.model.AiRequest(initial.purpose(), history, 1234,
                                java.time.Duration.ofSeconds(6), false, tools), task);
                        }
                        assertEquals(TaskResult.FAILED, second.outcome());
                        assertEquals("AI_SESSION_STATE_LIMIT", second.problem().orElseThrow().eventCode());
                        var metrics = second.value().orElseThrow();
                        assertEquals("", metrics.text());
                        assertTrue(metrics.toolCalls().isEmpty());
                        assertTrue(metrics.continuation().isEmpty());
                        assertEquals(Optional.of(23L), metrics.usage().inputTokens());
                        assertEquals(Optional.of(7L), metrics.usage().outputTokens());
                        assertTrue(metrics.usage().cachedInputTokens().isEmpty());
                        assertEquals("model", metrics.modelId());
                        assertEquals("tool_calls", metrics.finishReason());
                        assertEquals(Optional.of(1234), metrics.effectiveMaxOutputTokens());
                        assertTrue(metrics.encodedRequestBytes().orElseThrow() > 0);
                        var stopped = assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class, () -> ai.call(request, task));
                        assertEquals("AI_SESSION_BINDING_CHANGED", stopped.problem().eventCode());
                        return PlatformTaskAdmissionIntegrationTest.success(null);
                    }));
                assertEquals(failFirstResponse ? 1 : 2, fixture.calls.get(), "The paid successful response is not retried and the stopped session sends nothing");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"OPENAI_CHAT_COMPLETIONS,true", "OPENAI_CHAT_COMPLETIONS,false", "OPENAI_RESPONSES,true", "OPENAI_RESPONSES,false", "ANTHROPIC_MESSAGES,true", "ANTHROPIC_MESSAGES,false"})
    void nativeRoundTripUsesSelectedProtocolEvenWhenUrlLooksLikeChat(ModelProtocol protocol, boolean allow) throws Exception {
        nativeRoundTrip(protocol, allow, ModelReasoningLevel.DEFAULT);
    }

    @ParameterizedTest
    @EnumSource(ModelProtocol.class)
    void nativeNoneRoundTripUsesExplicitProtocolSetting(ModelProtocol protocol) throws Exception {
        nativeRoundTrip(protocol, true, ModelReasoningLevel.NONE);
    }

    private void nativeRoundTrip(ModelProtocol protocol, boolean allow, ModelReasoningLevel effort) throws Exception {
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
                    if (effort == ModelReasoningLevel.NONE) {
                        switch (protocol) {
                            case OPENAI_CHAT_COMPLETIONS ->
                                assertEquals("none", wire.path("reasoning_effort").asText());
                            case OPENAI_RESPONSES -> assertEquals("none", wire.at("/reasoning/effort").asText());
                            case ANTHROPIC_MESSAGES -> assertEquals("disabled", wire.at("/thinking/type").asText());
                        }
                    }
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
            var profile = new AiModelProfile("native", fixture.uri("/v1/chat/completions"), "model", "ai/native", null, null, effort, protocol);
            service.save(profile, "Native", "fixture-key".toCharArray(), 256);
            var result = service.verify("native", AiCapabilityType.TOOL_CALLING, null, "").completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.problem().toString());
            var saved = service.listAiConfigurations().getFirst();
            assertTrue(saved.verified(AiCapabilityType.TOOL_CALLING));
            assertFalse(saved.verified(AiCapabilityType.TEXT));
            assertEquals(protocol, saved.profile().protocol());
            assertEquals(effort, saved.profile().reasoningEffort());
            assertEquals(2, turns.get());
        }
    }
}

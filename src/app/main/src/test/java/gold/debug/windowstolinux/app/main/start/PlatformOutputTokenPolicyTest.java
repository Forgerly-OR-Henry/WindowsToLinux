package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformAiServiceTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.service.ai.PlatformAiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformOutputTokenPolicyTest {
    @TempDir
    Path root;

    static AppConfig.Ai policy(int recommendation, boolean allow, int verification) {
        var base = AppConfig.defaultsConfig().ai();
        return new AppConfig.Ai(base.connectSeconds(), recommendation, allow, verification, base.nativeState(), base.recovery(), base.deletion(), base.file());
    }

    static PlatformRuntime open(Path root, boolean allow) throws Exception {
        var base = AppConfig.defaultsConfig();
        var config = new AppConfig(base.dataDirectory(), base.ui(), base.defaults(), base.ssh(), policy(32768, allow, 8192),
            base.emergencyRecords(), base.modLoad(), base.storeUrl());
        return PlatformRuntime.open(new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS, root, root.resolve("data")),
            PlatformStartupTest.PROTECTION, config);
    }

    @ParameterizedTest
    @CsvSource({"OPENAI_CHAT_COMPLETIONS,true", "OPENAI_CHAT_COMPLETIONS,false", "OPENAI_RESPONSES,true", "OPENAI_RESPONSES,false", "ANTHROPIC_MESSAGES,true", "ANTHROPIC_MESSAGES,false"})
    void actualWireAndResponseFollowTheCompletePriorityMatrix(ModelProtocol protocol, boolean allow) throws Exception {
        try (var fixture = new Fixture(); var runtime = open(root, allow)) {
            initialize(runtime, false);
            model(runtime, fixture, "model", "/model", AiCapabilityType.TEXT, null);
            var original = runtime.persistence().aiProfiles().findNamed("model").orElseThrow();
            runtime.persistence().aiProfiles().saveVerified(new StoredAiModelProfile(original.id(), original.endpoint(), original.model(), original.credentialKey(), ModelReasoningLevel.DEFAULT, protocol), "model", Instant.now(), AiCapabilityType.TEXT);
            route(runtime, AiPurposeType.DEPLOYMENT, "model");
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, switch (protocol) {
                case OPENAI_CHAT_COMPLETIONS -> RESPONSE;
                case OPENAI_RESPONSES ->
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}";
                case ANTHROPIC_MESSAGES ->
                    "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";
            }));
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            for (Optional<Integer> requested : List.of(Optional.<Integer>empty(), Optional.of(1024), Optional.of(65536))) {
                var call = new AiRequest(AiPurpose.LANGUAGE, message(AiPurpose.LANGUAGE).messages(), requested, Duration.ofSeconds(6), false);
                int effective = allow ? requested.orElse(32768) : 32768;
                var response = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(call, task)));
                assertEquals(Optional.of(effective), response.effectiveMaxOutputTokens());
                var sent = fixture.requests.poll(5, TimeUnit.SECONDS);
                assertNotNull(sent);
                assertEquals(effective, sent.body().path(protocol == ModelProtocol.OPENAI_RESPONSES ? "max_output_tokens" : "max_tokens").intValue());
                runtime.persistence().aiProfiles().saveOutputLimit("model", 1);
            }
            assertEquals(3, fixture.calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void hostFileCallsAndFailureDiagnosticsUseTheSamePolicy(boolean allow) throws Exception {
        try (var fixture = new Fixture(); var runtime = open(root, allow)) {
            initialize(runtime, false);
            model(runtime, fixture, "model", "/model", AiCapabilityType.TEXT, 1);
            route(runtime, AiPurposeType.DEPLOYMENT, "model");
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            var call = new AiRequest(AiPurpose.LANGUAGE, message(AiPurpose.LANGUAGE).messages(), Duration.ofSeconds(6), false);
            int effective = 32768;
            var response = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.explainFile(call, task)));
            assertEquals(Optional.of(effective), response.effectiveMaxOutputTokens());
            assertEquals(effective, fixture.requests.take().body().path("max_tokens").intValue());
            fixture.handler.set(exchange -> Fixture.reply(exchange, 429, "{}"));
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var rejected = ai.call(call, task);
                assertEquals(TaskResult.FAILED, rejected.outcome());
                assertEquals(Integer.toString(effective), rejected.problem().orElseThrow().context().get("effectiveMaxOutputTokens"));
                assertEquals(Integer.toString(effective), rejected.problem().orElseThrow().context().get("maximumOutputTokens"));
                var invalidBytes = new AiRequest(call.purpose(), call.messages(), call.maxOutputTokens(), call.timeout(), false, List.of(), 1);
                var invalid = assertThrows(ApiException.class, () -> ai.call(invalidBytes, task));
                assertEquals(Integer.toString(effective), invalid.problem().context().get("effectiveMaxOutputTokens"));
                return success(null);
            }));
            assertEquals(2, fixture.calls.get());
        }
    }

    @Test
    void frozenRevisionIncludesOnlyTheActiveOutputPolicyAndIgnoresTheLegacyEditorValue() throws Exception {
        try (var fixture = new Fixture(); var runtime = open(root, true)) {
            initialize(runtime, false);
            model(runtime, fixture, "model", "/model", AiCapabilityType.TEXT, 1);
            route(runtime, AiPurposeType.DEPLOYMENT, "model");
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var frozen = ai.configurationRevision(AiPurpose.LANGUAGE, task);
                runtime.persistence().aiProfiles().saveOutputLimit("model", 65536);
                assertEquals(frozen, ai.configurationRevision(AiPurpose.LANGUAGE, task));
                for (var settings : List.of(policy(65536, true, 8192), policy(32768, false, 8192))) {
                    try (var changed = new PlatformAiService(runtime.persistence().database(), runtime.tasks(), runtime.security(), runtime.persistence().aiProfiles(), OWNER, VERSION, settings)) {
                        assertNotEquals(frozen, changed.configurationRevision(AiPurpose.LANGUAGE, task));
                        assertEquals("AI_CONFIGURATION_CHANGED", assertThrows(ApiException.class, () -> changed.call(message(AiPurpose.LANGUAGE), frozen, task)).problem().eventCode());
                    }
                }
                return ai.call(message(AiPurpose.LANGUAGE), frozen, task);
            }));
            assertEquals(1, fixture.calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nativeContinuationRetainsActualBudgetAndSurvivesLegacyEditorChanges(boolean allow) throws Exception {
        try (var fixture = new Fixture(); var runtime = open(root, allow)) {
            initialize(runtime, false);
            model(runtime, fixture, "model", "/model", AiCapabilityType.TEXT, 1);
            var saved = runtime.persistence().aiProfiles().listConfigured().getFirst();
            runtime.persistence().aiProfiles().recordVerification(saved.profile(), saved.revision(), AiCapabilityType.TOOL_CALLING, Instant.now());
            route(runtime, AiPurposeType.DEPLOYMENT, "model");
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, fixture.calls.get() == 1
                ? "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"echo_1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}"
                : RESPONSE));
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            var tools = List.of(new AiToolDefinition("echo", "echo", "{\"type\":\"object\"}"));
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var frozen = ai.configurationRevision(AiPurpose.LANGUAGE, task);
                var first = ai.call(new AiRequest(AiPurpose.LANGUAGE, message(AiPurpose.LANGUAGE).messages(), 65536, Duration.ofSeconds(6), false, tools), frozen, task).value().orElseThrow();
                assertEquals(Optional.of(allow ? 65536 : 32768), first.effectiveMaxOutputTokens());
                assertTrue(first.continuation().isPresent());
                runtime.persistence().aiProfiles().saveOutputLimit("model", 2);
                assertEquals(frozen, ai.configurationRevision(AiPurpose.LANGUAGE, task));
                var history = new ArrayList<>(message(AiPurpose.LANGUAGE).messages());
                history.add(AiMessage.assistant(first));
                history.add(AiMessage.result(new AiToolResult("echo_1", "echo result", false)));
                var second = ai.call(new AiRequest(AiPurpose.LANGUAGE, history, Duration.ofSeconds(6), false, tools), frozen, task);
                assertEquals(Optional.of(32768), second.value().orElseThrow().effectiveMaxOutputTokens());
                return second;
            }));
            assertEquals(allow ? 65536 : 32768, fixture.requests.take().body().path("max_tokens").intValue());
            assertEquals(32768, fixture.requests.take().body().path("max_tokens").intValue());
            assertEquals(2, fixture.calls.get());
        }
    }
}

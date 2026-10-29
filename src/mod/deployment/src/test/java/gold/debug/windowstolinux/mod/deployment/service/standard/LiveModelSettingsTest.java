package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiReasoningMode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LiveModelSettingsTest extends gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture {
    private static LiveModelSettings parse(String text) {
        return LiveModelSettings.parse(text.lines().toList());
    }

    @Test
    void legacyFlatDefaultsToChatAndPreservesCredentialEquals() {
        var settings = parse("base_url=https://example.invalid/v1/\napi_key=dummy=a=b\nmodel=old-model\n");
        var profile = settings.profile("live-deployment", "ai/live/deployment");
        assertEquals("https://example.invalid/v1/chat/completions", profile.endpoint().toString());
        assertEquals(ModelProtocol.OPENAI_CHAT_COMPLETIONS, profile.protocol());
        assertEquals(ModelReasoningLevel.DEFAULT, profile.reasoningEffort());
        assertTrue(profile.reasoningDefaults().isEmpty());
        assertEquals(gold.debug.windowstolinux.appapi.ai.model.AiReasoningMode.DEFAULT, settings.reasoning(null));
        assertArrayEquals("dummy=a=b".toCharArray(), settings.credential());
    }

    @Test
    void explicitGptResponsesCannotBeOverwrittenByLaterDeepseek() {
        var settings = parse("""
            active_provider = gpt
            GPT:
              base_url = http://127.0.0.1:19321/v1
              api_key = dummy-gpt
              model = gpt-6-luna
              protocol = responses
            deepseek:
              base_url=https://example.invalid/deepseek
              api_key=dummy-deepseek
              model=other-model
            """);
        assertEquals("gpt", settings.provider());
        for (String purpose : List.of("deployment", "approval")) {
            var profile = settings.profile("live-" + purpose, "ai/live/" + purpose);
            assertEquals("http://127.0.0.1:19321/v1/responses", profile.endpoint().toString());
            assertEquals(ModelProtocol.OPENAI_RESPONSES, profile.protocol());
            assertEquals(ModelReasoningLevel.DEFAULT, profile.reasoningEffort());
            assertTrue(profile.reasoningDefaults().isEmpty());
            assertEquals(gold.debug.windowstolinux.app.ai.provider.ModelProviderType.OPENAI, profile.provider());
            assertEquals(gold.debug.windowstolinux.appapi.ai.model.AiReasoningMode.HIGH, settings.reasoning(null));
            assertEquals("gpt-6-luna", profile.model());
        }
        assertArrayEquals("dummy-gpt".toCharArray(), settings.credential());
    }

    @ParameterizedTest
    @ValueSource(strings = {"base_url", "api_key", "model", "protocol"})
    void selectedProviderMissingFieldNeverFallsBack(String field) {
        String selected = "base_url=https://example.invalid/gpt\napi_key=dummy-gpt\nmodel=gpt-6-luna\nprotocol=responses\n";
        selected = selected.lines().filter(line -> !line.startsWith(field + "=")).reduce("", (a, b) -> a + b + "\n");
        String input = "active_provider=gpt\nGPT:\n" + selected
            + "deepseek:\nbase_url=https://example.invalid/v1\napi_key=dummy-deepseek\nmodel=other\n";
        assertThrows(IllegalArgumentException.class, () -> parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "GPT:\nbase_url=https://example.invalid\napi_key=dummy\nmodel=gpt-6-luna",
        "active_provider=missing\nGPT:\nbase_url=https://example.invalid\napi_key=dummy\nmodel=gpt-6-luna",
        "active_provider=gpt\nbase_url=https://example.invalid\napi_key=dummy\nmodel=gpt-6-luna\nGPT:\nprotocol=responses",
        "base_url=https://example.invalid\napi_key=dummy\napi_key=other\nmodel=some-model",
        "base_url=https://example.invalid\napi_key=dummy\nmodel=some-model\nprotocol=unknown",
        "active_provider=gpt\nactive_provider=gpt\nGPT:\nbase_url=https://example.invalid\napi_key=dummy\nmodel=some-model",
        "active_provider=gpt\nGPT:\nbase_url=https://example.invalid\napi_key=dummy\nmodel=some-model\ngpt:\nprotocol=responses"
    })
    void ambiguousOrUnsupportedConfigurationFailsClosed(String input) {
        assertThrows(IllegalArgumentException.class, () -> parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "LOW", "MEDIUM", "NONE"})
    void lunaRejectsLowerOrDefaultTestEffort(String effort) {
        assertThrows(IllegalArgumentException.class, () -> luna().reasoning(effort));
    }

    private static LiveModelSettings luna() {
        return parse("active_provider=gpt\nGPT:\nbase_url=http://127.0.0.1:19321/v1\napi_key=dummy-gpt\nmodel=gpt-6-luna\nprotocol=responses");
    }

    @Test
    void writesHighHostPurposePreferencesAndPreservesVisionAcrossProfileSave() throws Exception {
        var settings = luna();
        var profile = settings.profile("live-deployment", "ai/live/deployment");
        try (var runtime = open()) {
            runtime.models().save(profile, "Luna local fixture", "dummy-key".toCharArray(), 8192);
            var first = runtime.models().listAiConfigurations().getFirst();
            runtime.models().saveReasoning(profile.id(), first.revision(), java.util.Map.of(
                AiPurpose.LANGUAGE, AiReasoningMode.MEDIUM, AiPurpose.APPROVAL, AiReasoningMode.LOW,
                AiPurpose.VISION, AiReasoningMode.LOW));
            settings.configureReasoning(runtime.models(), profile.id(), settings.reasoning(null), settings.reasoning("HIGH"));
            var updated = runtime.models().listAiConfigurations().getFirst().profile();
            runtime.models().save(settings.profile(profile.id(), updated.credentialKey()), "Luna local fixture", "dummy-key".toCharArray(), 8192);
            var saved = runtime.models().listAiConfigurations().getFirst().profile();
            assertEquals(AiReasoningMode.HIGH, saved.reasoningDefaults().get(AiPurpose.LANGUAGE));
            assertEquals(AiReasoningMode.HIGH, saved.reasoningDefaults().get(AiPurpose.APPROVAL));
            assertEquals(AiReasoningMode.LOW, saved.reasoningDefaults().get(AiPurpose.VISION));
            assertEquals(ModelReasoningLevel.DEFAULT, saved.reasoningEffort());
        }
    }

    @Test
    void diagnosticsNeverExposeCredentialOrPrivateUrlParts() {
        String key = "dummy-key-SENSITIVE";
        for (String url : List.of("https://user:private-password@example.invalid/secret-path",
            "https://example.invalid/secret-path?api_key=private-query", "https://example.invalid/secret-path#private-fragment",
            "https://invalid host/secret-path")) {
            var error = assertThrows(IllegalArgumentException.class,
                () -> parse("base_url=" + url + "\napi_key=" + key + "\nmodel=gpt-6-luna"));
            assertNull(error.getCause());
            for (String secret : List.of(key, url, "private-password", "private-query", "private-fragment", "secret-path"))
                assertFalse(error.toString().contains(secret));
        }
        var settings = parse("base_url=https://example.invalid/private-route\napi_key=" + key + "\nmodel=gpt-6-luna");
        assertFalse(settings.toString().contains(key));
        assertFalse(settings.toString().contains("private-route"));
        char[] copy = settings.credential();
        Arrays.fill(copy, '\0');
        assertArrayEquals(key.toCharArray(), settings.credential());
    }
}

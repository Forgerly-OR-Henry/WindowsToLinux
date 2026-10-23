package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.service.ai.AiModelProfile;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformReasoningNoneTest {
    @TempDir
    Path root;

    @Test
    void unsupportedNoneFailsVerificationWithoutRetryingWithProviderDefault() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new PlatformAiServiceTest.Fixture();
             var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            var profile = new AiModelProfile("sample", fixture.uri("/chat"), "fixture-model", "ai/provider/sample/key", ModelReasoningLevel.NONE);
            fixture.handler.set(exchange -> {
                assertEquals("none", fixture.requests.remove().body().path("reasoning_effort").asText());
                calls.incrementAndGet();
                PlatformAiServiceTest.Fixture.reply(exchange, 400, "{\"error\":{\"message\":\"unsupported reasoning effort\"}}");
            });
            var result = PlatformModelServiceTest.done(PlatformModelServiceTest.saveAndVerify(service, profile,
                "Nonthinking reviewer", "fixture-key".toCharArray(), AiCapabilityType.TEXT, 8192));
            assertEquals(TaskResult.FAILED, result.outcome());
            assertEquals("AI_HTTP_REJECTED", result.problem().orElseThrow().eventCode());
            var saved = service.listAiConfigurations().getFirst();
            assertEquals(ModelReasoningLevel.NONE, saved.profile().reasoningEffort());
            assertFalse(saved.verified(AiCapabilityType.TEXT));
            assertEquals(1, calls.get());
        }
    }
}

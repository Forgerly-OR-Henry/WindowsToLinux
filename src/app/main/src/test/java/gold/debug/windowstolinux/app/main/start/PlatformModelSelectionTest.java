package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformAiServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.ai.provider.*;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModelSelectionTest {
    @TempDir
    Path root;

    @Test
    void allInventoryIncludesUnverifiedAndDirectInvocationDoesNotNeedRecommendations() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "direct", "/direct", AiCapabilityType.TEXT, null);
            var profile = runtime.persistence().aiProfiles().listConfigured().getFirst().profile();
            runtime.persistence().aiProfiles().saveConfiguration(new gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile("unverified",
                profile.endpoint(), "unknown", profile.credentialKey()), "Unverified");
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals(2, ai.models(task).size());
                assertTrue(ai.models(AiPurpose.LANGUAGE, task).isEmpty());
                assertEquals("UNVERIFIED", ai.models(task).stream().filter(value -> value.id().equals("unverified")).findFirst().orElseThrow().capabilities().get("TEXT"));
                assertEquals("AI_MODEL_UNVERIFIED", assertThrows(ApiException.class,
                    () -> ai.call(message(AiPurpose.LANGUAGE).withModel("unverified"), task)).problem().eventCode());
                var revision = ai.modelConfigurationRevision("direct", task);
                return ai.call(message(AiPurpose.LANGUAGE).withModel("direct"), revision, task);
            }));
            assertEquals(1, fixture.calls.get());
            assertEquals("/direct", fixture.requests.take().path());
        }
    }

    @Test
    void explicitlySelectedFailureNeverUsesARecommendedFallback() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "first", "/reject", AiCapabilityType.TEXT, null);
            model(runtime, fixture, "second", "/fallback", AiCapabilityType.TEXT, null);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            fixture.handler.set(exchange -> Fixture.reply(exchange, exchange.getRequestURI().getPath().equals("/reject") ? 429 : 200, RESPONSE));
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            var direct = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(message(AiPurpose.LANGUAGE).withModel("first"), task))
                .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, direct.outcome());
            assertEquals(1, fixture.calls.get());
            assertEquals("/reject", fixture.requests.take().path());
            assertEquals("second", completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(message(AiPurpose.LANGUAGE), task))).modelId());
            assertEquals(List.of("/reject", "/fallback"), List.of(fixture.requests.take().path(), fixture.requests.take().path()));
        }
    }

    @Test
    void directVisionRequiresItsOwnCapabilityWithoutRecommendationMembership() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "image", "/image", AiCapabilityType.TEXT, null);
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            var image = new gold.debug.windowstolinux.appapi.ai.vision.AiImage("image/png", PNG);
            var vision = new AiRequest(AiPurpose.VISION, List.of(new AiMessage(AiRole.USER, "Image", List.of(image))), 32, java.time.Duration.ofSeconds(6), false).withModel("image");
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals("AI_MODEL_UNVERIFIED", assertThrows(ApiException.class, () -> ai.call(vision, task)).problem().eventCode());
                return success(null);
            }));
            var saved = runtime.persistence().aiProfiles().listConfigured().getFirst();
            runtime.persistence().aiProfiles().recordVerification(saved.profile(), saved.revision(), AiCapabilityType.VISION, Instant.now());
            assertEquals("image", completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(vision, task))).modelId());
            assertEquals(1, fixture.calls.get());
            assertEquals("image_url", fixture.requests.take().body().at("/messages/0/content/1/type").asText());
        }
    }

    @Test
    void languageAndApprovalUseSeparateDefaultsAndRequestOverrideDoesNotPersist() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var settings = runtime.models();
            var profile = new AiModelProfile("shared", fixture.uri("/shared"), "gpt-6.1-sol", "ai/shared",
                ModelReasoningLevel.DEFAULT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProviderType.OPENAI, Map.of());
            settings.save(profile, "Shared", "test-key".toCharArray(), 1024);
            var saved = runtime.persistence().aiProfiles().listConfigured().getFirst();
            assertEquals(AiReasoningMode.MEDIUM, saved.profile().reasoningDefaults().get(AiPurpose.LANGUAGE));
            settings.saveReasoning("shared", saved.revision(), Map.of(AiPurpose.LANGUAGE, AiReasoningMode.LOW,
                AiPurpose.APPROVAL, AiReasoningMode.HIGH, AiPurpose.VISION, AiReasoningMode.MEDIUM));
            saved = runtime.persistence().aiProfiles().listConfigured().getFirst();
            runtime.persistence().aiProfiles().recordVerification(saved.profile(), saved.revision(), AiCapabilityType.TEXT, Instant.now());
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            for (var purpose : List.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL)) {
                var result = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(message(purpose).withModel("shared"), task)));
                assertEquals(purpose == AiPurpose.LANGUAGE ? AiReasoningMode.LOW : AiReasoningMode.HIGH, result.effectiveReasoning().orElseThrow());
                assertEquals(purpose == AiPurpose.LANGUAGE ? "low" : "high", fixture.requests.take().body().path("reasoning_effort").asText());
            }
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(message(AiPurpose.LANGUAGE).withModel("shared").withReasoning(AiReasoningMode.DEFAULT), task)));
            assertFalse(fixture.requests.take().body().has("reasoning_effort"));
            assertEquals(AiReasoningMode.LOW, runtime.persistence().aiProfiles().listConfigured().getFirst().profile().reasoningDefaults().get(AiPurpose.LANGUAGE));
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals("AI_REASONING_UNSUPPORTED", assertThrows(ApiException.class,
                    () -> ai.call(message(AiPurpose.LANGUAGE).withModel("shared").withReasoning(AiReasoningMode.STANDARD), task)).problem().eventCode());
                return success(null);
            }));
            assertEquals(3, fixture.calls.get());
        }
    }

}

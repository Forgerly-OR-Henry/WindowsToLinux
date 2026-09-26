package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformAiServiceTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformServerReferenceTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.ai.recovery.RecoveryProtocol;
import gold.debug.windowstolinux.app.linux.recovery.*;
import gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryModels;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.shared.model.ai.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformRecoveryModelsTest {
    @TempDir
    Path root;

    static final String PROPOSAL = "{\"command\":\"reboot\",\"reason\":\"review first\",\"expected\":\"interrupts access\",\"highImpact\":false}";

    static String response(String content) throws Exception {
        return JSON.writeValueAsString(
                Map.of("choices", List.of(Map.of("message", Map.of("content", content), "finish_reason", "stop")),
                        "usage", Map.of("prompt_tokens", 3, "completion_tokens", 4)));
    }

    @Test
    void actualTaskSeparatesVisionAndLanguageWithOriginalCredentialsAndBudgets() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "vision", "/vision", AiCapabilityType.VISION, 64);
            model(runtime, fixture, "language", "/language", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.VISION, "vision");
            route(runtime, AiPurposeType.DEPLOYMENT, "language");
            String vision = response("{\"observation\":\"login shell\"}");
            String language = response(PROPOSAL);
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200,
                    exchange.getRequestURI().getPath().equals("/vision") ? vision : language));
            var ai = runtime.ai(OWNER, VERSION);
            var escaped = new AtomicReference<PlatformRecoveryModels>();
            var result = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var models = new PlatformRecoveryModels(ai, task);
                escaped.set(models);
                assertEquals("local terminal",
                        models.observe(new TerminalObservation("local terminal", new byte[0], 1)).content());
                assertEquals(0, fixture.calls.get());
                var observation = models.observe(new TerminalObservation("", PNG, 1));
                assertEquals("login shell", RecoveryProtocol.parseObservation(observation.content()));
                assertEquals(Map.of("vision", "VALID"), observation.attempts());
                return success(models.decide("Untrusted terminal: " + observation.content()));
            }));
            assertEquals("ok", result.code());
            assertTrue(RecoveryProtocol.parseAction(result.content()).highImpact());
            assertFalse(result.toString().contains("reboot"));
            var image = fixture.requests.poll(5, TimeUnit.SECONDS);
            var text = fixture.requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(image);
            assertNotNull(text);
            assertEquals("Bearer fixture-key-vision", image.authorization());
            assertEquals(64, image.body().path("max_tokens").intValue());
            assertTrue(image.body().path("messages").get(1).path("content").get(1).path("image_url").path("url")
                    .asText().startsWith("data:image/png;base64,"));
            assertEquals("Bearer fixture-key-language", text.authorization());
            assertEquals(128, text.body().path("max_tokens").intValue());
            assertEquals("json_object", text.body().path("response_format").path("type").asText());
            assertTrue(text.body().path("messages").get(0).path("content").asText().contains("human approval"));
            assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> escaped.get().decide("again")).problem().code());
            assertEquals(2, fixture.calls.get());
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void malformedProposalDoesNotTryAnotherModelOrExposeItsContent() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            for (String id : List.of("first", "second"))
                model(runtime, fixture, id, "/" + id, AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            String malformed = response(
                    "{\"command\":\"private malformed proposal\",\"reason\":\"x\",\"expected\":\"x\",\"highImpact\":false,\"execute\":true}");
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, malformed));
            var ai = runtime.ai(OWNER, VERSION);
            var result = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    task -> success(new PlatformRecoveryModels(ai, task).decide("fixture"))));
            assertEquals("modelInvalid", result.code());
            assertEquals("", result.content());
            assertEquals(Map.of("first", "INVALID"), result.attempts());
            assertEquals(1, fixture.calls.get());
            assertFalse(result.toString().contains("private"));
        }
    }

    @Test
    void sentButUnconfirmedModelCallRetainsOriginalTaskAndCannotFallThroughToAnotherAdvice() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            for (String id : List.of("first", "second"))
                model(runtime, fixture, id, "/" + id, AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            fixture.handler.set(exchange -> {
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write('{');
            });
            var ai = runtime.ai(OWNER, VERSION);
            var original = new AtomicReference<TaskContext>();
            var rejected = new AtomicReference<Problem>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                original.set(task);
                try {
                    new PlatformRecoveryModels(ai, task).decide("fixture");
                    fail("unconfirmed request returned advice");
                } catch (ApiException failure) {
                    rejected.set(failure.problem());
                }
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            try {
                assertEquals(ProblemCode.IO_FAILURE, rejected.get().code());
                assertEquals("AI_TRANSPORT_UNKNOWN", rejected.get().eventCode());
                assertEquals(1, fixture.calls.get());
            } finally {
                recover(runtime, ai, handle, original.get());
            }
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void explicitProviderRejectionUsesOnlyConfiguredPlatformFallback() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            for (String id : List.of("first", "second"))
                model(runtime, fixture, id, "/" + id, AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            String valid = response(PROPOSAL);
            fixture.handler.set(exchange -> {
                boolean rejected = exchange.getRequestURI().getPath().equals("/first");
                Fixture.reply(exchange, rejected ? 503 : 200, rejected ? "unavailable" : valid);
            });
            var ai = runtime.ai(OWNER, VERSION);
            var result = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    task -> success(new PlatformRecoveryModels(ai, task).decide("fixture"))));
            assertEquals("ok", result.code());
            assertEquals(Map.of("second", "VALID"), result.attempts());
            assertEquals(2, fixture.calls.get());
        }
    }
}

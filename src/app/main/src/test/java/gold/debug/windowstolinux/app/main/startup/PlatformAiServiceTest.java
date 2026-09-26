package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformServerReferenceTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.PlatformAiService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.shared.model.ai.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformAiServiceTest {
    @TempDir
    Path root;

    static final ObjectMapper JSON = new ObjectMapper();

    static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8YQAAAAASUVORK5CYII=");

    static final String RESPONSE = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}";

    static AiRequest message(AiPurpose purpose) {
        return new AiRequest(purpose,
                List.of(new AiMessage(AiRole.SYSTEM, "精确规则", List.of()), new AiMessage(AiRole.USER, "你好", List.of())),
                32, Duration.ofSeconds(6), false);
    }

    static void model(PlatformRuntime runtime, Fixture fixture, String id, String path, AiCapabilityType capability,
            Integer limit) throws Exception {
        String credential = "ai/fixture/" + id;
        try (var operation = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
            char[] key = ("fixture-key-" + id).toCharArray();
            try {
                store.save(credential, key);
            } finally {
                Arrays.fill(key, '\0');
            }
        }
        runtime.persistence().aiProfiles().saveVerified(
                new StoredAiProviderProfile(id, fixture.uri(path).toString(), "fixture/" + id, credential), id,
                Instant.now(), capability);
        if (limit != null)
            runtime.persistence().aiProfiles().saveOutputLimit(id, limit);
    }

    static void route(PlatformRuntime runtime, AiPurposeType purpose, String... ids) throws Exception {
        runtime.persistence().aiProfiles().purposes().save(purpose,
                Arrays.stream(ids).map(id -> new AiPurposeAssignment(id, true)).toList());
    }

    static void recover(PlatformRuntime runtime, PlatformAiService ai, TaskHandle<?> handle, TaskContext context)
            throws Exception {
        state(handle, TaskState.UNKNOWN);
        callbackReturned(runtime, context);
        try (var recovery = runtime.tasks().bind(OWNER, VERSION, false).registerRecovery("fixture",
                (previous, task) -> {
                    ai.closeTaskResources(task);
                    return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.UNKNOWN, "FIXTURE_ABANDONED",
                                    "The fixture explicitly abandoned the unknown provider response", Map.of(),
                                    Optional.empty())));
                })) {
            assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void actualWireUsesSelectedModelBudgetRolesAndReportedUsageAndSurvivesDatabaseRestart() throws Exception {
        try (var fixture = new Fixture()) {
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                initialize(runtime, false);
                model(runtime, fixture, "text", "/chat", AiCapabilityType.TEXT, 128);
                route(runtime, AiPurposeType.DEPLOYMENT, "text");
                var ai = runtime.ai(OWNER, VERSION);
                var original = new AtomicReference<TaskContext>();
                var response = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                    original.set(task);
                    return ai.call(message(AiPurpose.LANGUAGE), task);
                }));
                assertEquals("你好", response.text());
                assertEquals("text", response.modelId());
                assertEquals("stop", response.finishReason());
                assertEquals(Optional.of(3L), response.usage().inputTokens());
                assertEquals(Optional.of(4L), response.usage().outputTokens());
                var sent = fixture.requests.poll(5, TimeUnit.SECONDS);
                assertNotNull(sent);
                assertEquals("Bearer fixture-key-text", sent.authorization());
                assertEquals("fixture/text", sent.body().path("model").textValue());
                assertEquals(32, sent.body().path("max_tokens").intValue());
                assertFalse(sent.body().path("stream").booleanValue());
                assertEquals("system", sent.body().path("messages").get(0).path("role").textValue());
                assertEquals("你好", sent.body().path("messages").get(1).path("content").textValue());
                assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class, () -> ai.call(message(AiPurpose.LANGUAGE), original.get()))
                                .problem().code());
                assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                sql(runtime.persistence().database(), "UPDATE mod_registry SET last_boot_id=? WHERE mod_id=?",
                        runtime.persistence().database().bootId(), OWNER);
                assertEquals(128, runtime.persistence().aiProfiles().outputLimit("text").orElseThrow());
                assertEquals("text", completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                        task -> runtime.ai(OWNER, VERSION).call(message(AiPurpose.LANGUAGE), task))).modelId());
            }
        }
    }

    @Test
    void imagePurposeAndInputBudgetsRejectBeforeSendingAndMissingUsageStaysAbsent() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "text", "/chat", AiCapabilityType.TEXT, 128);
            model(runtime, fixture, "vision", "/chat", AiCapabilityType.VISION, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "text");
            route(runtime, AiPurposeType.VISION, "vision");
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"));
            var ai = runtime.ai(OWNER, VERSION);
            var image = new AiImage("image/png", PNG);
            var vision = new AiRequest(AiPurpose.VISION, List.of(new AiMessage(AiRole.USER, "请看图", List.of(image))), 16,
                    Duration.ofSeconds(6), true);
            var response = completed(
                    runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(vision, task)));
            assertTrue(response.usage().inputTokens().isEmpty());
            assertTrue(response.usage().outputTokens().isEmpty());
            var wire = fixture.requests.poll(5, TimeUnit.SECONDS).body();
            assertEquals("json_object", wire.path("response_format").path("type").textValue());
            assertTrue(wire.path("messages").get(0).path("content").get(1).path("image_url").path("url").textValue()
                    .startsWith("data:image/png;base64,"));
            var invalid = List.of(
                    new AiRequest(AiPurpose.LANGUAGE, vision.messages(), 16, Duration.ofSeconds(6), false),
                    new AiRequest(AiPurpose.LANGUAGE,
                            List.of(new AiMessage(AiRole.USER, "中".repeat(350_000), List.of())), 16,
                            Duration.ofSeconds(6), false),
                    new AiRequest(AiPurpose.LANGUAGE, message(AiPurpose.LANGUAGE).messages(), 129,
                            Duration.ofSeconds(6), false),
                    new AiRequest(AiPurpose.VISION,
                            List.of(new AiMessage(AiRole.USER, "images", Collections.nCopies(5, image))), 16,
                            Duration.ofSeconds(6), false),
                    new AiRequest(AiPurpose.VISION,
                            List.of(new AiMessage(AiRole.USER, "wrong type", List.of(new AiImage("image/jpeg", PNG)))),
                            16, Duration.ofSeconds(6), false),
                    new AiRequest(AiPurpose.LANGUAGE,
                            Collections.nCopies(1025, new AiMessage(AiRole.USER, "", List.of())), 16,
                            Duration.ofSeconds(6), false));
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                for (var candidate : invalid)
                    assertEquals(ProblemCode.INVALID_INPUT,
                            assertThrows(ApiException.class, () -> ai.call(candidate, task)).problem().code());
                return success(null);
            }));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void onlyExplicitHttpRejectionUsesTheNextFrozenPurposeMember() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "first", "/reject", AiCapabilityType.TEXT, 128);
            model(runtime, fixture, "second", "/chat", AiCapabilityType.TEXT, 128);
            model(runtime, fixture, "approval", "/review", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            route(runtime, AiPurposeType.APPROVAL, "approval");
            fixture.handler.set(exchange -> Fixture.reply(exchange,
                    exchange.getRequestURI().getPath().equals("/reject") ? 429 : 200, RESPONSE));
            var ai = runtime.ai(OWNER, VERSION);
            assertEquals("second", completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    task -> ai.call(message(AiPurpose.LANGUAGE), task))).modelId());
            assertEquals(List.of("/reject", "/chat"),
                    List.of(fixture.requests.take().path(), fixture.requests.take().path()));
            assertEquals("approval", completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    task -> ai.call(message(AiPurpose.APPROVAL), task))).modelId());
            assertEquals("/review", fixture.requests.take().path());
        }
    }

    @Test
    void malformedSuccessIsReportedWithoutReplayingTheRequestOnAnotherModel() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "first", "/bad", AiCapabilityType.TEXT, 128);
            model(runtime, fixture, "second", "/chat", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            fixture.handler.set(exchange -> Fixture.reply(exchange, 200, "{\"choices\":[],\"choices\":[]}"));
            var ai = runtime.ai(OWNER, VERSION);
            var result = runtime.tasks().bind(OWNER, VERSION, false)
                    .submit(request(), task -> ai.call(message(AiPurpose.LANGUAGE), task)).completion()
                    .toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(Outcome.FAILED, result.outcome());
            assertEquals("AI_RESPONSE_INVALID", result.problem().orElseThrow().eventCode());
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void missingLimitAndChangedCredentialVerificationNeverReachTheProvider() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "text", "/chat", AiCapabilityType.TEXT, null);
            route(runtime, AiPurposeType.DEPLOYMENT, "text");
            var ai = runtime.ai(OWNER, VERSION);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            completed(binding.submit(request(), task -> {
                assertEquals(ProblemCode.UNAVAILABLE,
                        assertThrows(ApiException.class, () -> ai.call(message(AiPurpose.LANGUAGE), task)).problem()
                                .code());
                return success(null);
            }));
            runtime.persistence().aiProfiles().saveOutputLimit("text", 128);
            try (var operation = runtime.security().enterOperation();
                    var store = runtime.security().openCredentials()) {
                char[] changed = "changed-fixture-key".toCharArray();
                try {
                    store.save("ai/fixture/text", changed);
                } finally {
                    Arrays.fill(changed, '\0');
                }
            }
            completed(binding.submit(request(), task -> {
                assertEquals(ProblemCode.UNAVAILABLE,
                        assertThrows(ApiException.class, () -> ai.call(message(AiPurpose.LANGUAGE), task)).problem()
                                .code());
                return success(null);
            }));
            assertEquals(0, fixture.calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sentCancellationAndTimeoutRetainUnknownWithoutFallback(boolean cancel) throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "first", "/wait", AiCapabilityType.TEXT, 128);
            model(runtime, fixture, "second", "/chat", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            var release = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                try {
                    release.await(6, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Fixture.reply(exchange, 200, RESPONSE);
            });
            var ai = runtime.ai(OWNER, VERSION);
            var original = new AtomicReference<TaskContext>();
            var request = new AiRequest(AiPurpose.LANGUAGE, message(AiPurpose.LANGUAGE).messages(), 32,
                    Duration.ofMillis(cancel ? 5000 : 1500), false);
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                original.set(task);
                return ai.call(request, task);
            });
            try {
                assertNotNull(fixture.requests.poll(5, TimeUnit.SECONDS));
                if (cancel)
                    handle.requestCancel();
                state(handle, TaskState.UNKNOWN);
                callbackReturned(runtime, original.get());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(1, fixture.calls.get());
                recover(runtime, ai, handle, original.get());
                assertEquals(1, fixture.calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void droppedOrOversizedResponseIsUnknownAndDoesNotReplay(boolean oversized) throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "first", "/broken", AiCapabilityType.TEXT, 128);
            model(runtime, fixture, "second", "/chat", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "first", "second");
            fixture.handler.set(exchange -> {
                if (oversized)
                    Fixture.reply(exchange, 200, " ".repeat(8 * 1024 * 1024 + 1));
                else {
                    exchange.sendResponseHeaders(200, 256);
                    exchange.getResponseBody().write('{');
                }
            });
            var ai = runtime.ai(OWNER, VERSION);
            var original = new AtomicReference<TaskContext>();
            var observed = new AtomicReference<OperationResult<AiResponse>>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                original.set(task);
                var result = ai.call(message(AiPurpose.LANGUAGE), task);
                observed.set(result);
                return result;
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertEquals("AI_TRANSPORT_UNKNOWN", observed.get().problem().orElseThrow().eventCode());
            assertEquals(1, fixture.calls.get());
            recover(runtime, ai, handle, original.get());
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void failedConnectionLedgerReleaseIsRetriedAfterTheRealClientHasClosed() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "text", "/chat", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "text");
            var db = runtime.persistence().database();
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_ai_release CHECK(resource_kind<>'CONNECTION' OR released_at IS NULL)");
            var ai = runtime.ai(OWNER, VERSION);
            var original = new AtomicReference<TaskContext>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                original.set(task);
                return ai.call(message(AiPurpose.LANGUAGE), task);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertEquals(1, count(db,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CONNECTION' AND released_at IS NULL"));
            assertEquals(0, db.openConnections());
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_ai_release");
            recover(runtime, ai, handle, original.get());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void anAcceptedCallFinishesAcrossDesktopLockAndPreventsEarlyCapabilityTeardown() throws Exception {
        try (var fixture = new Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, fixture, "text", "/chat", AiCapabilityType.TEXT, 128);
            route(runtime, AiPurposeType.DEPLOYMENT, "text");
            var response = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                try {
                    assertTrue(response.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Fixture.reply(exchange, 200, RESPONSE);
            });
            var ai = runtime.ai(OWNER, VERSION);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var handle = binding.submit(request(), task -> ai.call(message(AiPurpose.LANGUAGE), task));
            try {
                assertNotNull(fixture.requests.poll(5, TimeUnit.SECONDS));
                runtime.security().lock();
                assertThrows(ApiException.class, () -> binding.submit(request(), task -> success(null)));
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, ai::close).problem().code());
            } finally {
                response.countDown();
            }
            assertEquals("text", completed(handle).modelId());
            ai.close();
        }
    }

    record Sent(String path, String authorization, JsonNode body) {
    }

    @FunctionalInterface
    interface Handler {
        void serve(HttpExchange exchange) throws IOException;
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        final AtomicInteger calls = new AtomicInteger();

        final BlockingQueue<Sent> requests = new LinkedBlockingQueue<>();

        final AtomicReference<Handler> handler = new AtomicReference<>(exchange -> reply(exchange, 200, RESPONSE));

        Fixture() throws IOException {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    calls.incrementAndGet();
                    requests.add(new Sent(exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Authorization"), body));
                    handler.get().serve(exchange);
                } catch (IOException expectedDisconnect) {
                    /* Cancelled test clients close their sockets. */ }
            });
            server.start();
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        static void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() throws Exception {
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.ai.voice.VoiceCapabilityProbe;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceConfigTest {
    @TempDir
    Path root;

    static AiModelProfile profile(Endpoint endpoint) {
        return new AiModelProfile("speech", endpoint.uri(), "speech-model", "ai/speech/key");
    }

    @Test
    void separateSingleUrlsUseThePackagedFixtureAndSurviveRestart() throws Exception {
        try (var endpoint = new Endpoint(); var live = new VoiceServiceTest.Endpoint()) {
            live.transcript.set(VoiceCapabilityProbe.EXPECTED);
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                var service = PlatformModelServiceTest.setup(runtime);
                service.save(profile(endpoint), "同名", "voice-key".toCharArray(), 128);
                service.save(new AiModelProfile("live", URI.create(live.ws()), "speech-model", "ai/live/key"),
                    "同名", "live-key".toCharArray(), 128);
                assertEquals(0, endpoint.calls.get());
                assertThrows(IllegalArgumentException.class, () -> service.verify("speech", AiCapabilityType.VOICE_REALTIME));
                assertEquals(0, endpoint.calls.get());
                assertEquals(TaskResult.SUCCEEDED, PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE)).outcome());
                assertEquals("Bearer voice-key", endpoint.authorization.get());
                assertTrue(contains(endpoint.body.get(), VoiceCapabilityProbe.create().audio()));
                assertTrue(new String(endpoint.body.get(), StandardCharsets.ISO_8859_1).contains("filename=\"capability-probe-en-v1.wav\""));
                assertEquals(TaskResult.SUCCEEDED, PlatformModelServiceTest.done(service.verify("live", AiCapabilityType.VOICE_REALTIME)).outcome());
                var rows = service.listAiConfigurations();
                assertTrue(rows.get(0).verified(AiCapabilityType.VOICE_FILE));
                assertFalse(rows.get(0).verified(AiCapabilityType.VOICE_REALTIME));
                assertTrue(rows.get(1).verified(AiCapabilityType.VOICE_REALTIME));
                assertFalse(rows.get(1).verified(AiCapabilityType.VOICE_FILE));
                service.saveAiPurpose(AiPurposeType.VOICE, List.of(new AiPurposeAssignment("speech"), new AiPurposeAssignment("live")));
            }
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                runtime.started();
                assertEquals(2, runtime.models().listAiConfigurations().size());
                assertEquals(List.of(new AiPurposeAssignment("speech"), new AiPurposeAssignment("live")), runtime.models().listAiPurpose(AiPurposeType.VOICE));
                assertEquals(1, endpoint.calls.get());
                assertEquals(1, live.commits.get());
            }
        }
    }

    @Test
    void unsupportedAuthenticationAndWrongTranscriptRemainDistinct() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            service.save(profile(endpoint), "Speech", "private-key".toCharArray(), 128);
            endpoint.status.set(422);
            endpoint.response.set("{\"error\":{\"code\":\"unsupported_model\",\"message\":\"private-provider-text\"}}");
            assertEquals(TaskResult.FAILED, PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE)).outcome());
            assertEquals(AiCapabilityStatus.UNSUPPORTED, service.listAiConfigurations().getFirst().checks().get(AiCapabilityType.VOICE_FILE).state());
            endpoint.status.set(401);
            endpoint.response.set("{\"error\":{\"message\":\"private-provider-text\"}}");
            PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE));
            assertEquals("MODEL_PROBE_AUTH", service.listAiConfigurations().getFirst().checks().get(AiCapabilityType.VOICE_FILE).reason());
            endpoint.status.set(200);
            endpoint.response.set("{\"text\":\"unexpected transcript\"}");
            assertEquals("MODEL_PROBE_ANSWER", PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE)).problem().orElseThrow().eventCode());
            assertFalse(service.listAiConfigurations().getFirst().verified(AiPurposeType.VOICE));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM runtime_log WHERE details LIKE '%private-provider-text%'"));
        }
    }

    static void markAll(PlatformRuntime runtime, PlatformModelService service) throws Exception {
        var observed = service.listAiConfigurations().getFirst();
        for (var capability : AiCapabilityType.values())
            runtime.persistence().aiProfiles().recordVerification(
                observed.profile().stored(), observed.revision(), capability, Instant.now());
    }

    @Test
    void identityChangesInvalidateAllEvidenceWhileRenameOrderingAndLegacyLimitDoNot() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            service.save(profile(endpoint), "First", "key".toCharArray(), 128);
            markAll(runtime, service);
            var before = service.listAiConfigurations().getFirst();
            service.save(before.profile(), "Renamed", new char[0], 256);
            service.reorderAiProviders(List.of("speech"));
            assertEquals(before.revision(), service.listAiConfigurations().getFirst().revision());
            assertEquals(before.checks(), service.listAiConfigurations().getFirst().checks());
            var old = before.profile();
            service.save(new AiModelProfile(old.id(), URI.create(endpoint.uri() + "/new"), old.model(), old.credentialKey()),
                "Renamed", new char[0], 256);
            for (var capability : AiCapabilityType.values())
                assertFalse(service.listAiConfigurations().getFirst().verified(capability));
            markAll(runtime, service);
            var current = service.listAiConfigurations().getFirst().profile();
            service.save(current, "Renamed", "replacement".toCharArray(), 256);
            for (var capability : AiCapabilityType.values())
                assertFalse(service.listAiConfigurations().getFirst().verified(capability));
            assertNotEquals(current.credentialKey(), service.listAiConfigurations().getFirst().profile().credentialKey());
            assertEquals(0, endpoint.calls.get());
        }
    }

    @Test
    void inFlightChecksHaveStableOwnershipAndRejectEditingAndStaleConfiguration() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            service.save(profile(endpoint), "Speech", "key".toCharArray(), 128);
            endpoint.entered = new CountDownLatch(1);
            endpoint.release = new CountDownLatch(1);
            var task = service.verify("speech", AiCapabilityType.VOICE_FILE);
            try {
                assertTrue(endpoint.entered.await(3, TimeUnit.SECONDS));
                assertEquals(task.id(), service.checks("speech").getFirst().id());
                assertTrue(service.checks("other").isEmpty());
                var old = service.listAiConfigurations().getFirst().profile();
                assertThrows(IllegalStateException.class, () -> service.save(old, "Rename", new char[0], 128));
                assertThrows(IllegalStateException.class, () -> service.verify("speech", AiCapabilityType.VOICE_FILE));
                runtime.persistence().aiProfiles().saveConfiguration(new AiModelProfile(old.id(), URI.create(endpoint.uri() + "/new"),
                    old.model(), old.credentialKey()).stored(), "External change");
            } finally {
                endpoint.release.countDown();
            }
            assertEquals("MODEL_PROBE_STALE", PlatformModelServiceTest.done(task).problem().orElseThrow().eventCode());
            assertFalse(service.listAiConfigurations().getFirst().verified(AiPurposeType.VOICE));
            assertEquals(1, endpoint.calls.get());
        }
    }

    static boolean contains(byte[] body, byte[] expected) {
        outer:
        for (int start = 0; start <= body.length - expected.length; start++) {
            for (int index = 0; index < expected.length; index++)
                if (body[start + index] != expected[index]) continue outer;
            return true;
        }
        return false;
    }

    static final class Endpoint implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        final AtomicInteger calls = new AtomicInteger(), status = new AtomicInteger(200);

        final AtomicReference<String> response = new AtomicReference<>("{\"text\":\"" + VoiceCapabilityProbe.EXPECTED + "\"}");

        final AtomicReference<String> authorization = new AtomicReference<>();

        final AtomicReference<byte[]> body = new AtomicReference<>();

        volatile CountDownLatch entered, release;

        Endpoint() throws IOException {
            server.setExecutor(executor);
            server.createContext("/transcriptions", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    body.set(exchange.getRequestBody().readAllBytes());
                    if (entered != null) entered.countDown();
                    if (release != null) try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/transcriptions");
        }

        @Override
        public void close() throws Exception {
            if (release != null) release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}

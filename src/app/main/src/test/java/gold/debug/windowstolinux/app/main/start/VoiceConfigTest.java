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
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceConfigTest {
    @TempDir
    Path root;

    static AiModelProfile profile(Endpoint endpoint, String realtime) {
        return new AiModelProfile("speech", null, "speech-model", "ai/speech/key", endpoint.uri(),
            realtime.isEmpty() ? null : URI.create(realtime));
    }

    @Test
    void pureVoiceSavesWithoutProbingAndBothActualModesRemainIndependentAcrossRestart() throws Exception {
        try (var endpoint = new Endpoint(); var live = new VoiceServiceTest.Endpoint()) {
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                var service = PlatformModelServiceTest.setup(runtime);
                char[] key = "voice-key".toCharArray();
                service.save(profile(endpoint, live.ws()), "纯语音模型", key, 128);
                assertArrayEquals(new char[key.length], key);
                assertEquals(0, endpoint.calls.get());
                assertNull(live.socketModel.get());
                var initial = service.listAiConfigurations().getFirst();
                assertNull(initial.profile().chatCompletionsEndpoint());
                for (var capability : AiCapabilityType.values())
                    assertEquals(AiCapabilityStatus.UNVERIFIED, initial.checks().get(capability).state());
                assertThrows(java.sql.SQLException.class, () -> service.saveAiPurpose(AiPurposeType.VOICE,
                    List.of(new AiPurposeAssignment("speech", true))));
                assertThrows(IllegalArgumentException.class, () -> service.verify("speech", AiCapabilityType.TEXT, null, ""));
                assertEquals(TaskResult.SUCCEEDED, PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE,
                    VoiceServiceTest.audio(), "local speech")).outcome());
                assertEquals("Bearer voice-key", endpoint.authorization.get());
                assertTrue(contains(endpoint.body.get(), VoiceServiceTest.audio().audio()), "Actual verification sends the reviewed WAV bytes");
                assertTrue(new String(endpoint.body.get(), StandardCharsets.ISO_8859_1).contains("filename=\"test.wav\""));
                var file = service.listAiConfigurations().getFirst();
                assertTrue(file.fileVoiceVerifiedAt().isPresent());
                assertTrue(file.realtimeVoiceVerifiedAt().isEmpty());
                assertTrue(service.listAiPurpose(AiPurposeType.VOICE).isEmpty());
                assertEquals(TaskResult.SUCCEEDED, PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_REALTIME,
                    VoiceServiceTest.audio(), " local  speech ")).outcome());
                assertEquals("speech-model", live.socketModel.get());
                assertEquals("Bearer voice-key", live.socketKey.get());
                assertEquals(1, live.commits.get());
                var both = service.listAiConfigurations().getFirst();
                assertEquals(file.fileVoiceVerifiedAt(), both.fileVoiceVerifiedAt());
                assertTrue(both.realtimeVoiceVerifiedAt().isPresent());
                assertTrue(both.textVerifiedAt().isEmpty());
                service.saveAiPurpose(AiPurposeType.VOICE, List.of(new AiPurposeAssignment("speech", true)));
                assertTrue(service.listAiPurpose(AiPurposeType.DEPLOYMENT).isEmpty());
            }
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                runtime.started();
                var service = runtime.models();
                var both = service.listAiConfigurations().getFirst();
                assertTrue(both.verified(AiCapabilityType.VOICE_FILE));
                assertTrue(both.verified(AiCapabilityType.VOICE_REALTIME));
                assertNull(both.profile().chatCompletionsEndpoint());
                assertEquals(List.of(new AiPurposeAssignment("speech", true)), service.listAiPurpose(AiPurposeType.VOICE));
                assertEquals(1, endpoint.calls.get());
                assertEquals(1, live.commits.get());
            }
        }
    }

    @Test
    void explicitUnsupportedAuthenticationAndWrongTranscriptPersistSafeIndependentStates() throws Exception {
        try (var endpoint = new Endpoint(); var live = new VoiceServiceTest.Endpoint();
             var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            service.save(profile(endpoint, live.ws()), "Speech", "private-key".toCharArray(), 128);
            assertEquals(TaskResult.SUCCEEDED, PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_REALTIME,
                VoiceServiceTest.audio(), "local speech")).outcome());
            var realtime = service.listAiConfigurations().getFirst().realtimeVoiceVerifiedAt();
            endpoint.status.set(422);
            endpoint.response.set("{\"error\":{\"code\":\"unsupported_model\",\"message\":\"private-provider-text\"}}");
            assertEquals(TaskResult.FAILED, PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE,
                VoiceServiceTest.audio(), "local speech")).outcome());
            var unsupported = service.listAiConfigurations().getFirst();
            assertEquals(AiCapabilityStatus.UNSUPPORTED, unsupported.checks().get(AiCapabilityType.VOICE_FILE).state());
            assertEquals("MODEL_PROBE_UNSUPPORTED", unsupported.checks().get(AiCapabilityType.VOICE_FILE).reason());
            assertEquals(realtime, unsupported.realtimeVoiceVerifiedAt());
            assertTrue(unsupported.verified(AiPurposeType.VOICE));
            endpoint.status.set(401);
            endpoint.response.set("{\"error\":{\"message\":\"private-provider-text\"}}");
            PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE, VoiceServiceTest.audio(), "local speech"));
            var denied = service.listAiConfigurations().getFirst();
            assertEquals(AiCapabilityStatus.FAILED, denied.checks().get(AiCapabilityType.VOICE_FILE).state());
            assertEquals("MODEL_PROBE_AUTH", denied.checks().get(AiCapabilityType.VOICE_FILE).reason());
            endpoint.status.set(200);
            endpoint.response.set("{\"text\":\"unexpected transcript\"}");
            var result = PlatformModelServiceTest.done(service.verify("speech", AiCapabilityType.VOICE_FILE,
                VoiceServiceTest.audio(), "local speech"));
            assertEquals("MODEL_PROBE_ANSWER", result.problem().orElseThrow().eventCode());
            var wrong = service.listAiConfigurations().getFirst();
            assertEquals("MODEL_PROBE_ANSWER", wrong.checks().get(AiCapabilityType.VOICE_FILE).reason());
            assertEquals(realtime, wrong.realtimeVoiceVerifiedAt());
            assertEquals(AiCapabilityStatus.UNVERIFIED, wrong.checks().get(AiCapabilityType.TEXT).state());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM runtime_log WHERE details LIKE '%private-provider-text%'"));
        }
    }

    static void markAll(PlatformRuntime runtime, PlatformModelService service) throws Exception {
        var observed = service.listAiConfigurations().getFirst();
        for (var capability : AiCapabilityType.values())
            runtime.persistence().aiProfiles().recordVerification(observed.profile().stored(), observed.revision(), capability, Instant.now());
    }

    static void availableExcept(PlatformModelService service, AiCapabilityType... absent) throws Exception {
        var no = Set.of(absent);
        var row = service.listAiConfigurations().getFirst();
        for (var capability : AiCapabilityType.values())
            assertEquals(!no.contains(capability), row.verified(capability), capability.name());
    }

    @Test
    void onlyAffectedAddressesAndTextLimitsInvalidateWhileNamesAndPurposeOrderingRetainEvidence() throws Exception {
        try (var endpoint = new Endpoint(); var live = new VoiceServiceTest.Endpoint();
             var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            var profile = new AiModelProfile("speech", URI.create("https://chat.example.test/completions"), "model", "ai/key",
                endpoint.uri(), URI.create(live.ws()));
            service.save(profile, "First", "key".toCharArray(), 128);
            markAll(runtime, service);
            var before = service.listAiConfigurations().getFirst();
            service.save(before.profile(), "Renamed", new char[0], 128);
            service.reorderAiProviders(List.of("speech"));
            service.saveAiPurpose(AiPurposeType.VOICE, List.of(new AiPurposeAssignment("speech", true)));
            service.saveAiPurpose(AiPurposeType.VOICE, List.of(new AiPurposeAssignment("speech", false)));
            assertEquals(before.revision(), service.listAiConfigurations().getFirst().revision());
            assertEquals(before.checks(), service.listAiConfigurations().getFirst().checks());
            var old = service.listAiConfigurations().getFirst().profile();
            var changed = new AiModelProfile(old.id(), URI.create("https://chat.example.test/new"), old.model(), old.credentialKey(),
                old.fileTranscriptionEndpoint(), old.realtimeTranscriptionEndpoint());
            service.save(changed, "Renamed", new char[0], 128);
            availableExcept(service, AiCapabilityType.TEXT, AiCapabilityType.VISION);
            markAll(runtime, service);
            old = service.listAiConfigurations().getFirst().profile();
            changed = new AiModelProfile(old.id(), old.chatCompletionsEndpoint(), old.model(), old.credentialKey(),
                URI.create(endpoint.uri() + "/new"), old.realtimeTranscriptionEndpoint());
            service.save(changed, "Renamed", new char[0], 128);
            availableExcept(service, AiCapabilityType.VOICE_FILE);
            markAll(runtime, service);
            old = service.listAiConfigurations().getFirst().profile();
            changed = new AiModelProfile(old.id(), old.chatCompletionsEndpoint(), old.model(), old.credentialKey(),
                old.fileTranscriptionEndpoint(), URI.create(live.ws() + "/new"));
            service.save(changed, "Renamed", new char[0], 128);
            availableExcept(service, AiCapabilityType.VOICE_REALTIME);
            markAll(runtime, service);
            old = service.listAiConfigurations().getFirst().profile();
            service.save(old, "Renamed", new char[0], 256);
            availableExcept(service, AiCapabilityType.TEXT, AiCapabilityType.VISION);
            markAll(runtime, service);
            changed = new AiModelProfile(old.id(), old.chatCompletionsEndpoint(), "new-model", old.credentialKey(),
                old.fileTranscriptionEndpoint(), old.realtimeTranscriptionEndpoint());
            service.save(changed, "Renamed", new char[0], 256);
            availableExcept(service, AiCapabilityType.values());
            markAll(runtime, service);
            service.save(changed, "Renamed", "replacement".toCharArray(), 256);
            availableExcept(service, AiCapabilityType.values());
            assertEquals(0, endpoint.calls.get());
            assertNull(live.socketModel.get());
        }
    }

    @Test
    void endpointChangeWhileAudioIsInFlightRejectsStaleEvidenceWithoutProbingAgain() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            service.save(profile(endpoint, ""), "Speech", "key".toCharArray(), 128);
            endpoint.entered = new CountDownLatch(1);
            endpoint.release = new CountDownLatch(1);
            var task = service.verify("speech", AiCapabilityType.VOICE_FILE, VoiceServiceTest.audio(), "local speech");
            try {
                assertTrue(endpoint.entered.await(3, TimeUnit.SECONDS));
                var old = service.listAiConfigurations().getFirst().profile();
                service.save(new AiModelProfile(old.id(), null, old.model(), old.credentialKey(),
                    URI.create(endpoint.uri() + "/new"), null), "Changed", new char[0], 128);
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

        final AtomicReference<String> response = new AtomicReference<>("{\"text\":\"local speech\"}");

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

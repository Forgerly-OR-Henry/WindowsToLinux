package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerReferenceTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.db.ai.*;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceServiceTest {
    @TempDir Path root;
    static final ObjectMapper JSON = new ObjectMapper();
    static VoiceRequest audio() {
        var bytes = ByteBuffer.allocate(4844).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46464952).putInt(4836).putInt(0x45564157).putInt(0x20746d66).putInt(16)
            .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2)
            .putShort((short) 16).putInt(0x61746164).putInt(4800);
        return new VoiceRequest(bytes.array(), "test.wav", Duration.ofSeconds(3));
    }
    static OperationResult<Void> success() { return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty()); }
    static void model(PlatformRuntime runtime, String id, String file, String realtime, AiCapabilityType... capabilities)
        throws Exception {
        String credential = "ai/fixture/" + id;
        try (var operation = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
            char[] key = ("key-" + id).toCharArray();
            try { store.save(credential, key); } finally { Arrays.fill(key, '\0'); }
        }
        var profile = new StoredAiProviderProfile(id, "", "model-" + id, credential, file, realtime);
        runtime.persistence().aiProfiles().saveNamed(profile);
        for (var capability : capabilities)
            runtime.persistence().aiProfiles().saveVerified(profile, id, Instant.now(), capability);
        runtime.persistence().aiProfiles().saveOutputLimit(id, 128);
    }
    static void route(PlatformRuntime runtime, String... ids) throws Exception {
        runtime.persistence().aiProfiles().purposes().save(AiPurposeType.VOICE,
            Arrays.stream(ids).map(id -> new AiPurposeAssignment(id, true)).toList());
    }

    @Test void verifiedInventoryDoesNotGrantInvocationAndVoiceModeFilteringUsesExplicitRouteOrder() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, "file", endpoint.http(), "", AiCapabilityType.VOICE_FILE);
            model(runtime, "live", "", endpoint.ws(), AiCapabilityType.VOICE_REALTIME);
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals("AI_PURPOSE_UNAVAILABLE", assertThrows(ApiException.class, () -> ai.call(audio(), task)).problem().eventCode());
                return success();
            });
            assertEquals(Outcome.SUCCEEDED, handle.completion().toCompletableFuture().get(6, TimeUnit.SECONDS).outcome());
            assertEquals(0, endpoint.httpCalls.get());
            route(runtime, "live", "file");
            var result = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(audio(), task)));
            assertEquals("file", result.modelId()); assertEquals("local speech", result.text());
            assertEquals("Bearer key-file", endpoint.httpKey.get());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test void realtimeSelectionAndConfigurationStayFrozenThroughActualStreaming() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, "file", endpoint.http(), "", AiCapabilityType.VOICE_FILE);
            model(runtime, "live", "", endpoint.ws(), AiCapabilityType.VOICE_REALTIME);
            route(runtime, "file", "live");
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            var events = new CopyOnWriteArrayList<VoiceEvent>();
            var result = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                try (var session = ai.openVoice(new VoiceOptions(Duration.ofSeconds(5), 65_536), events::add, task)) {
                    runtime.persistence().aiProfiles().saveNamed(new StoredAiProviderProfile("live", "", "changed",
                        "ai/fixture/live", "", "ws://127.0.0.1:1/elsewhere"));
                    session.send(audio().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
                    return session.finish().toCompletableFuture().get(6, TimeUnit.SECONDS);
                }
            }));
            assertEquals("live", result.modelId()); assertEquals("local speech", result.text());
            assertEquals("model-live", endpoint.socketModel.get()); assertEquals("Bearer key-live", endpoint.socketKey.get());
            assertEquals(2, events.size()); assertTrue(events.get(1).finalResult());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test void capabilityMismatchAndFrozenRevisionDriftRefuseBeforeProviderSend() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            model(runtime, "live", "", endpoint.ws(), AiCapabilityType.VOICE_REALTIME); route(runtime, "live");
            var ai = runtime.capabilities().ai(OWNER, VERSION);
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals("VOICE_MODE_UNAVAILABLE", assertThrows(ApiException.class, () -> ai.call(audio(), task)).problem().eventCode());
                String revision = ai.configurationRevision(AiPurpose.VOICE, task);
                runtime.persistence().aiProfiles().saveNamed(new StoredAiProviderProfile("live", "", "changed",
                    "ai/fixture/live", "", endpoint.ws()));
                assertEquals("AI_CONFIGURATION_CHANGED", assertThrows(ApiException.class,
                    () -> ai.call(audio(), revision, task)).problem().eventCode());
                return success();
            });
            assertEquals(Outcome.SUCCEEDED, handle.completion().toCompletableFuture().get(6, TimeUnit.SECONDS).outcome());
            assertEquals(0, endpoint.httpCalls.get()); assertNull(endpoint.socketModel.get());
        }
    }

    @Test void fileRejectionDoesNotSilentlyRotateAndSavedVoiceRoutesSurviveRestart() throws Exception {
        try (var endpoint = new Endpoint()) {
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                initialize(runtime, false);
                model(runtime, "first", endpoint.http(), "", AiCapabilityType.VOICE_FILE);
                model(runtime, "second", endpoint.http(), "", AiCapabilityType.VOICE_FILE);
                route(runtime, "first", "second");
                var ai = runtime.capabilities().ai(OWNER, VERSION);
                endpoint.httpStatus.set(422);
                var failure = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> ai.call(audio(), task))
                    .completion().toCompletableFuture().get(6, TimeUnit.SECONDS);
                assertEquals(Outcome.FAILED, failure.outcome()); assertEquals(1, endpoint.httpCalls.get());
            }
            try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
                runtime.security().unlock(PASSWORD.toCharArray());
                sql(runtime.persistence().database(), "UPDATE mod_registry SET last_boot_id=? WHERE mod_id=?",
                    runtime.persistence().database().bootId(), OWNER);
                endpoint.httpStatus.set(200);
                assertEquals("first", completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    task -> runtime.capabilities().ai(OWNER, VERSION).call(audio(), task))).modelId());
            }
        }
    }

    @Test void cancellationAfterAudioHasUnknownEffectButReleasesActualSessionWithoutReplay() throws Exception {
        try (var endpoint = new Endpoint(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false); model(runtime, "live", "", endpoint.ws(), AiCapabilityType.VOICE_REALTIME); route(runtime, "live");
            var ai = runtime.capabilities().ai(OWNER, VERSION); var context = new AtomicReference<TaskContext>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                context.set(task);
                try (var session = ai.openVoice(new VoiceOptions(Duration.ofSeconds(5), 65_536), ignored -> { }, task)) {
                    session.send(audio().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
                    session.cancel(); return session.completion().toCompletableFuture().get(4, TimeUnit.SECONDS);
                }
            });
            state(handle, TaskState.UNKNOWN);
            PlatformAiServiceTest.recover(runtime, ai, handle, context.get());
            assertEquals(0, endpoint.commits.get());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    static final class Endpoint implements AutoCloseable {
        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ServerSocket websocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicInteger httpCalls = new AtomicInteger(), httpStatus = new AtomicInteger(200), commits = new AtomicInteger();
        final AtomicReference<String> httpKey = new AtomicReference<>(), socketKey = new AtomicReference<>(), socketModel = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile Socket connected;
        Endpoint() throws IOException {
            http.setExecutor(executor);
            http.createContext("/transcriptions", exchange -> {
                httpCalls.incrementAndGet(); httpKey.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"text\":\"local speech\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(httpStatus.get(), body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); } finally { exchange.close(); }
            }); http.start();
            executor.submit(() -> {
                try (var socket = websocket.accept()) {
                    connected = socket; var input = new DataInputStream(socket.getInputStream()); var output = socket.getOutputStream();
                    line(input); var headers = new HashMap<String, String>();
                    for (String header; !(header = line(input)).isEmpty();) {
                        int colon = header.indexOf(':'); headers.put(header.substring(0, colon).toLowerCase(Locale.ROOT), header.substring(colon + 1).strip());
                    }
                    socketKey.set(headers.get("authorization"));
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                        + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
                    for (;;) {
                        int first = input.readUnsignedByte(), second = input.readUnsignedByte(), length = second & 127;
                        if (length == 126) length = input.readUnsignedShort(); else if (length == 127) length = Math.toIntExact(input.readLong());
                        if (length > 1_048_576 || (second & 128) == 0) throw new IOException("Invalid fixture client frame");
                        byte[] mask = input.readNBytes(4), body = input.readNBytes(length);
                        if (body.length != length) throw new EOFException();
                        for (int i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
                        if ((first & 15) == 8) break;
                        var event = JSON.readTree(body);
                        if (event.path("type").asText().equals("session.update")) {
                            socketModel.set(event.path("session").path("audio").path("input").path("transcription").path("model").asText());
                            var updated = JSON.createObjectNode().put("type", "session.updated"); updated.set("session", event.path("session"));
                            send(output, updated.toString());
                        } else if (event.path("type").asText().equals("input_audio_buffer.commit")) {
                            commits.incrementAndGet();
                            send(output, "{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"item-0\",\"previous_item_id\":null}");
                            send(output, "{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"item-0\",\"content_index\":0,\"delta\":\"local\"}");
                            send(output, "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"item-0\",\"content_index\":0,\"transcript\":\"local speech\"}");
                        }
                    }
                } catch (EOFException | SocketException closed) { }
                catch (Throwable unexpected) { failure.set(unexpected); }
            });
        }
        String http() { return "http://127.0.0.1:" + http.getAddress().getPort() + "/transcriptions"; }
        String ws() { return "ws://127.0.0.1:" + websocket.getLocalPort() + "/realtime"; }
        static String line(InputStream input) throws IOException {
            var bytes = new ByteArrayOutputStream();
            for (int c; (c = input.read()) != -1;) { if (c == '\n') return bytes.toString(StandardCharsets.US_ASCII).stripTrailing(); bytes.write(c); }
            throw new EOFException();
        }
        static void send(OutputStream output, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); var wire = new DataOutputStream(output); wire.writeByte(0x81);
            if (bytes.length < 126) wire.writeByte(bytes.length); else { wire.writeByte(126); wire.writeShort(bytes.length); }
            wire.write(bytes); wire.flush();
        }
        @Override public void close() throws Exception {
            http.stop(0); websocket.close(); if (connected != null) connected.close(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); assertNull(failure.get());
        }
    }
}

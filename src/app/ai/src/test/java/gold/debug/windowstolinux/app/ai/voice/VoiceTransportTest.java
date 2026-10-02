package gold.debug.windowstolinux.app.ai.voice;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.net.http.WebSocket;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;

class VoiceTransportTest {
    static VoiceOptions options() { return new VoiceOptions(Duration.ofSeconds(5), 65_536); }
    static VoiceRequest input() { return new VoiceRequest(wav(4800), "sample.wav", Duration.ofSeconds(3)); }
    static byte[] wav(int length) {
        var bytes = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46464952).putInt(36 + length).putInt(0x45564157).putInt(0x20746d66).putInt(16)
            .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2)
            .putShort((short) 16).putInt(0x61746164).putInt(length);
        for (int index = 0; index < length / 2; index++) bytes.putShort((short) (index % 73));
        return bytes.array();
    }

    @Test void validatesAudioFormatSamplingChunksAndInputOwnershipBeforeNetwork() {
        byte[] wav = wav(4800);
        var request = new VoiceRequest(wav, "sample.wav", Duration.ofSeconds(1));
        wav[44] = 99;
        assertNotEquals(99, request.audio()[44]);
        assertEquals(4800, request.pcm().length);
        byte[] bad = wav(4800); bad[24] = 0;
        assertThrows(IllegalArgumentException.class, () -> new VoiceRequest(bad, "sample.wav", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new VoiceRequest(wav(4800), "../sample.wav", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new VoiceRequest(new byte[25_000_001], "sample.wav", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new VoiceRequest(wav(4798), "sample.wav", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new VoiceOptions(Duration.ZERO, 65_536));
        assertThrows(IllegalArgumentException.class, () -> new VoiceOptions(Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> VoiceProtocol.validateEndpoint(URI.create("ws://example.com/realtime")));
        assertThrows(IllegalArgumentException.class, () -> VoiceProtocol.validateEndpoint(URI.create("wss://example.com/realtime?api_key=secret")));
    }

    @Test void multipartWireContainsActualWavModelAndJsonResponseFormat() throws Exception {
        var body = new AtomicReference<byte[]>(); var contentType = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        try (var server = new HttpFixture(exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            key.set(exchange.getRequestHeaders().getFirst("Authorization"));
            HttpFixture.reply(exchange, 200, "{\"text\":\"你好\"}");
        }); var call = new VoiceHttpCall(server.uri(), "fixture-key".toCharArray(), "fixture/transcribe", "model-a", input())) {
            var result = call.execute(() -> false);
            assertEquals(Outcome.SUCCEEDED, result.outcome()); assertEquals("你好", result.value().orElseThrow().text());
            assertEquals("model-a", result.value().orElseThrow().modelId());
            assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
            assertEquals("Bearer fixture-key", key.get());
            var wire = new String(body.get(), StandardCharsets.ISO_8859_1);
            assertTrue(wire.contains("name=\"model\"\r\n\r\nfixture/transcribe"));
            assertTrue(wire.contains("name=\"response_format\"\r\n\r\njson"));
            assertTrue(wire.contains("filename=\"sample.wav\"\r\nContent-Type: audio/wav"));
            assertTrue(wire.contains(new String(input().audio(), StandardCharsets.ISO_8859_1)));
        }
    }

    @Test void fileRejectsRedirectWithoutForwardingCredentialsAndClassifiesInvalidResponse() throws Exception {
        for (int status : new int[]{302, 401, 422, 200}) {
            try (var server = new HttpFixture(exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/leak");
                HttpFixture.reply(exchange, status, "{\"unexpected\":true}");
            }); var call = new VoiceHttpCall(server.uri(), "fixture-key".toCharArray(), "model", "id", input())) {
                var result = call.execute(() -> false);
                assertEquals(Outcome.FAILED, result.outcome());
                assertEquals(status == 200 ? "VOICE_RESPONSE_INVALID" : "VOICE_HTTP_REJECTED", result.problem().orElseThrow().eventCode());
            }
        }
    }

    @Test void fileCancellationBeforeSendHasNoRequestAndTimeoutAfterSendIsUnknown() throws Exception {
        var count = new AtomicInteger();
        try (var server = new HttpFixture(exchange -> {
            count.incrementAndGet();
            try { Thread.sleep(300); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            HttpFixture.reply(exchange, 200, "{\"text\":\"late\"}");
        })) {
            try (var call = new VoiceHttpCall(server.uri(), "key".toCharArray(), "model", "id", input())) {
                assertEquals(Outcome.CANCELLED, call.execute(() -> true).outcome());
                assertEquals(0, count.get());
            }
            var input = new VoiceRequest(wav(4800), "sample.wav", Duration.ofMillis(150));
            try (var call = new VoiceHttpCall(server.uri(), "key".toCharArray(), "model", "id", input)) {
                assertEquals(Outcome.UNKNOWN, call.execute(() -> false).outcome());
            }
            assertEquals(1, count.get());
        }
    }

    @Test void fileUnsupportedIsIndependentFromAuthenticationAndProviderMessagesRemainPrivate() throws Exception {
        for (int status : new int[]{400, 401}) {
            try (var server = new HttpFixture(exchange -> HttpFixture.reply(exchange, status,
                "{\"error\":{\"code\":\"unsupported_model\",\"message\":\"private provider text\"}}"));
                var call = new VoiceHttpCall(server.uri(), "fixture-key".toCharArray(), "model", "id", input())) {
                var result = call.execute(() -> false);
                assertEquals(Outcome.FAILED, result.outcome());
                assertEquals(status == 400 ? ProblemCode.INCOMPATIBLE : ProblemCode.ACCESS_DENIED,
                    result.problem().orElseThrow().code());
                assertFalse(result.problem().orElseThrow().toString().contains("private provider text"));
            }
        }
    }

    @Test void rejectedRealtimeUpgradeKeepsKnownFailureAndDoesNotForwardCredentialsToRedirect() throws Exception {
        var redirected = new AtomicInteger();
        try (var destination = new HttpFixture(exchange -> {
            redirected.incrementAndGet(); HttpFixture.reply(exchange, 200, "{}");
        })) {
            for (int status : new int[]{401, 302}) {
                try (var server = new HttpFixture(exchange -> {
                    exchange.getResponseHeaders().set("Location", destination.uri().toString());
                    HttpFixture.reply(exchange, status, "{}");
                })) {
                    var failure = assertThrows(ApiException.class, () -> VoiceSocketSession.open(
                        URI.create(server.uri().toString().replaceFirst("http:", "ws:")), "key".toCharArray(),
                        "model", "id", new VoiceOptions(Duration.ofSeconds(15), 65_536), ignored -> { }, () -> false));
                    assertEquals(status == 401 ? ProblemCode.ACCESS_DENIED : ProblemCode.UNAVAILABLE, failure.problem().code());
                    assertEquals("VOICE_CONNECTION_REJECTED", failure.problem().eventCode());
                }
            }
            assertEquals(0, redirected.get());
        }
    }

    @Test void actualProviderIncompatibilityAndUnassociatedFinalRemainDistinct() throws Exception {
        try (var server = new SocketFixture("unsupported")) {
            var failure = assertThrows(ApiException.class, () -> VoiceSocketSession.open(server.uri(), "key".toCharArray(),
                "model", "id", new VoiceOptions(Duration.ofSeconds(15), 65_536), ignored -> { }, () -> false));
            assertEquals(ProblemCode.INCOMPATIBLE, failure.problem().code());
            assertEquals("VOICE_UNSUPPORTED", failure.problem().eventCode());
            assertFalse(failure.problem().toString().contains("private provider text"));
        }
        try (var server = new SocketFixture("orphan"); var session = VoiceSocketSession.open(server.uri(), "key".toCharArray(),
            "model", "id", options(), ignored -> { }, () -> false)) {
            session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            var result = session.finish().toCompletableFuture().get(4, TimeUnit.SECONDS);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals(ProblemCode.CONTRACT_VIOLATION, result.problem().orElseThrow().code());
            assertTrue(result.value().isEmpty());
        }
    }

    @Test void realSocketSendsPcmContinuouslyAndOrdersFinalsByInputDespiteArrivalOrder() throws Exception {
        var events = new CopyOnWriteArrayList<VoiceEvent>();
        try (var server = new SocketFixture("reverse"); var session = VoiceSocketSession.open(server.uri(), "key".toCharArray(),
            "fixture/transcribe", "id", options(), events::add, () -> false)) {
            byte[] audio = input().pcm();
            session.send(Arrays.copyOfRange(audio, 0, 2400)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            session.send(Arrays.copyOfRange(audio, 2400, 4800)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            session.commit().toCompletableFuture().get(2, TimeUnit.SECONDS);
            session.send(audio).toCompletableFuture().get(2, TimeUnit.SECONDS);
            var result = session.finish().toCompletableFuture().get(6, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertEquals("turn-0\nturn-1", result.value().orElseThrow().text());
            assertEquals(List.of(0L, 1L), result.value().orElseThrow().segments().stream().map(VoiceEvent::inputOrder).toList());
            assertEquals(1, events.stream().filter(VoiceEvent::finalResult).findFirst().orElseThrow().inputOrder());
            assertEquals("Bearer key", server.headers.get("authorization"));
            assertTrue(server.requestLine.endsWith("/realtime?intent=transcription HTTP/1.1"));
            assertEquals(9600, server.audio.size());
            assertArrayEquals(audio, Arrays.copyOfRange(server.audio.toByteArray(), 0, 4800));
            assertEquals("session.update", server.events.get(0).path("type").asText());
            assertEquals("transcription", server.events.get(0).path("session").path("type").asText());
            assertEquals(24000, server.events.get(0).path("session").path("audio").path("input").path("format").path("rate").asInt());
            assertTrue(server.events.get(0).path("session").path("audio").path("input").path("turn_detection").isNull());
            assertThrows(ApiException.class, () -> session.send(audio));
        }
    }

    @Test void missingFinalAndAbruptDisconnectionRemainUnknownAndKeepConfirmedParts() throws Exception {
        try (var server = new SocketFixture("missing"); var session = VoiceSocketSession.open(server.uri(), "key".toCharArray(),
            "model", "id", new VoiceOptions(Duration.ofMillis(400), 65_536), ignored -> { }, () -> false)) {
            session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            var result = session.finish().toCompletableFuture().get(4, TimeUnit.SECONDS);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals(ProblemCode.TIMEOUT, result.problem().orElseThrow().code());
        }
        try (var server = new SocketFixture("lost"); var session = VoiceSocketSession.open(server.uri(), "key".toCharArray(),
            "model", "id", options(), ignored -> { }, () -> false)) {
            session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            session.commit().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(server.firstFinal.await(2, TimeUnit.SECONDS));
            session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            var result = session.finish().toCompletableFuture().get(4, TimeUnit.SECONDS);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals("turn-0", result.value().orElseThrow().text());
        }
    }

    @Test void cancelStopsNormalCallbacksAndReleasesSocketWithoutCommittingPendingAudio() throws Exception {
        var events = new CopyOnWriteArrayList<VoiceEvent>(); var cancelled = new AtomicBoolean();
        try (var server = new SocketFixture("normal"); var session = VoiceSocketSession.open(server.uri(), "key".toCharArray(),
            "model", "id", options(), events::add, cancelled::get)) {
            session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            session.cancel();
            int count = events.size();
            var result = session.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals(count, events.size()); assertTrue(events.isEmpty());
            assertFalse(server.events.stream().anyMatch(event -> event.path("type").asText().equals("input_audio_buffer.commit")));
            assertTrue(server.closed.await(2, TimeUnit.SECONDS));
            assertThrows(ApiException.class, () -> session.send(input().pcm()));
        }
    }

    @Test void pendingTurnBackpressureRejectsWithoutConsumingCurrentAudio() throws Exception {
        try (var server = new SocketFixture("missing"); var session = VoiceSocketSession.open(server.uri(), "key".toCharArray(),
            "model", "id", options(), ignored -> { }, () -> false)) {
            assertThrows(ApiException.class, () -> session.send(new byte[3]));
            assertThrows(ApiException.class, () -> session.commit());
            for (int index = 0; index < 64; index++) {
                session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
                session.commit().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, session::commit).problem().code());
            session.cancel();
        }
    }

    @Test void protocolMismatchAndListenerFailureDoNotClaimSuccessfulRecognition() throws Exception {
        try (var server = new SocketFixture("bad-config")) {
            // Allow cold JVM TLS entropy initialization before exercising a provider response. / 冷 JVM TLS 熵初始化后再覆盖提供方响应。
            var failure = assertThrows(ApiException.class, () -> VoiceSocketSession.open(server.uri(), "key".toCharArray(),
                "model", "id", new VoiceOptions(Duration.ofSeconds(15), 65_536), ignored -> { }, () -> false));
            assertEquals(ProblemCode.CONTRACT_VIOLATION, failure.problem().code());
            assertEquals("session.update", server.events.getFirst().path("type").asText());
        }
        try (var server = new SocketFixture("normal"); var session = VoiceSocketSession.open(server.uri(), "key".toCharArray(),
            "model", "id", options(), event -> { throw new IllegalStateException("author listener"); }, () -> false)) {
            session.send(input().pcm()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(Outcome.UNKNOWN, session.finish().toCompletableFuture().get(4, TimeUnit.SECONDS).outcome());
        }
    }

    static final class HttpFixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        HttpFixture(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.createContext("/transcriptions", handler); server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/transcriptions"); }
        static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String text) throws IOException {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
            finally { exchange.close(); }
        }
        @Override public void close() throws Exception {
            server.stop(0); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    static final class SocketFixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final List<JsonNode> events = new CopyOnWriteArrayList<>();
        final Map<String, String> headers = new ConcurrentHashMap<>();
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch firstFinal = new CountDownLatch(1), closed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile Socket connection;
        volatile String requestLine;
        SocketFixture(String mode) throws IOException {
            executor.submit(() -> {
                try (var socket = server.accept()) {
                    connection = socket; socket.setSoTimeout(8000);
                    var input = new DataInputStream(socket.getInputStream()); var output = socket.getOutputStream();
                    requestLine = line(input);
                    for (String line; !(line = line(input)).isEmpty();) {
                        int colon = line.indexOf(':'); headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
                    }
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                        + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
                    int turns = 0;
                    for (;;) {
                        byte[] bytes = frame(input);
                        if (bytes == null) break;
                        JsonNode event = VoiceProtocol.JSON.readTree(bytes); events.add(event);
                        switch (event.path("type").asText()) {
                            case "session.update" -> {
                                if (mode.equals("unsupported")) {
                                    send(output, "{\"type\":\"error\",\"error\":{\"code\":\"unsupported_model\",\"message\":\"private provider text\"}}");
                                    continue;
                                }
                                var response = VoiceProtocol.JSON.createObjectNode().put("type", "session.updated");
                                response.set("session", event.path("session").deepCopy());
                                if (mode.equals("bad-config")) ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("session")).put("type", "realtime");
                                send(output, response.toString());
                            }
                            case "input_audio_buffer.append" -> audio.write(Base64.getDecoder().decode(event.path("audio").asText()));
                            case "input_audio_buffer.commit" -> {
                                int order = turns++;
                                send(output, "{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"i" + order + "\",\"previous_item_id\":"
                                    + (order == 0 ? "null" : "\"i" + (order - 1) + "\"") + "}");
                                if (mode.equals("orphan")) {
                                    finalEvent(output, 99); continue;
                                }
                                if (mode.equals("lost") && order > 0) return;
                                if (!mode.equals("missing")) {
                                    send(output, "{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"i" + order + "\",\"content_index\":0,\"delta\":\"partial\"}");
                                    if (mode.equals("reverse")) {
                                        if (order == 1) { finalEvent(output, 1); finalEvent(output, 0); }
                                    } else finalEvent(output, order);
                                }
                            }
                            default -> throw new IOException("Unexpected client event");
                        }
                    }
                } catch (EOFException | SocketException expectedClose) {
                } catch (Throwable unexpected) { failure.set(unexpected); }
                finally { closed.countDown(); }
            });
        }
        URI uri() { return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/realtime"); }
        void finalEvent(OutputStream output, int order) throws IOException {
            send(output, "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"i" + order + "\",\"content_index\":0,\"transcript\":\"turn-" + order + "\"}");
            firstFinal.countDown();
        }
        static String line(InputStream input) throws IOException {
            var text = new ByteArrayOutputStream();
            for (int c; (c = input.read()) != -1;) {
                if (c == '\n') return text.toString(StandardCharsets.US_ASCII).stripTrailing();
                text.write(c); if (text.size() > 16_384) throw new IOException("Header too large");
            }
            throw new EOFException();
        }
        static byte[] frame(DataInputStream input) throws IOException {
            int first = input.readUnsignedByte(), second = input.readUnsignedByte();
            int length = second & 127;
            if (length == 126) length = input.readUnsignedShort();
            else if (length == 127) { long value = input.readLong(); if (value > 1_048_576) throw new IOException("Frame too large"); length = (int) value; }
            if ((second & 128) == 0) throw new IOException("Client frame is not masked");
            byte[] mask = input.readNBytes(4), data = input.readNBytes(length);
            if (data.length != length || mask.length != 4) throw new EOFException();
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if ((first & 15) == 8) return null;
            if ((first & 15) != 1) throw new IOException("Unexpected frame type");
            return data;
        }
        static void send(OutputStream output, String text) throws IOException {
            byte[] data = text.getBytes(StandardCharsets.UTF_8); var bytes = new DataOutputStream(output);
            bytes.writeByte(0x81);
            if (data.length < 126) bytes.writeByte(data.length);
            else { bytes.writeByte(126); bytes.writeShort(data.length); }
            bytes.write(data); bytes.flush();
        }
        @Override public void close() throws Exception {
            server.close(); if (connection != null) connection.close(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); assertNull(failure.get(), () -> "Fixture failed: " + failure.get());
        }
    }
}

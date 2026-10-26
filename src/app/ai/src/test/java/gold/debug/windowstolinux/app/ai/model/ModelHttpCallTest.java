package gold.debug.windowstolinux.app.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;

class ModelHttpCallTest {
    @Test
    void invalidNativeBodyRetainsFailedUsageAndFixedValidationCodeWithoutReplay() throws Exception {
        var calls = new AtomicInteger();
        var peerFailure = new AtomicReference<Throwable>();
        byte[] owned = "{\"secretmarker-request\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] reply = """
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[{"type":"function","id":123,"function":{"name":"echo","arguments":"secretmarker-body"}}]}}],"usage":{"prompt_tokens":17,"completion_tokens":5,"prompt_cache_hit_tokens":10,"prompt_cache_miss_tokens":7}}
            """.getBytes(StandardCharsets.UTF_8);
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    calls.incrementAndGet();
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var headers = new StringBuilder();
                    while (!headers.toString().endsWith("\r\n\r\n") && headers.length() < 8192) {
                        int next = input.read();
                        if (next < 0) throw new IOException("Fixture request ended before headers");
                        headers.append((char) next);
                    }
                    if (input.readNBytes(owned.length).length != owned.length)
                        throw new IOException("Fixture request body was incomplete");
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + reply.length
                        + "\r\nX-Private-Fixture: secretmarker-header\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(reply);
                    socket.getOutputStream().flush();
                } catch (IOException failure) {
                    peerFailure.set(failure);
                }
            });
            try (var call = new ModelHttpCall(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/secretmarker-endpoint"),
                "secretmarker-key".toCharArray(), owned, Duration.ofSeconds(5), "fixture", false)) {
                var result = call.execute(() -> false);
                assertEquals(TaskResult.FAILED, result.outcome());
                assertTrue(result.value().isEmpty());
                var problem = result.problem().orElseThrow();
                assertEquals(gold.debug.windowstolinux.appapi.task.result.ProblemCode.CONTRACT_VIOLATION, problem.code());
                assertEquals("AI_RESPONSE_INVALID", problem.eventCode());
                assertEquals("8", problem.context().get("nativeValidationCode"));
                assertEquals("17", problem.context().get("inputTokens"));
                assertEquals("5", problem.context().get("outputTokens"));
                assertEquals("10", problem.context().get("cachedInputTokens"));
                assertEquals("7", problem.context().get("cacheMissInputTokens"));
                assertEquals("true", problem.context().get("httpHeadersReceived"));
                assertEquals("200", problem.context().get("httpStatus"));
                assertEquals(Integer.toString(reply.length), problem.context().get("httpBodyBytesAccepted"));
                assertFalse(problem.toString().contains("secretmarker"));
                assertNull(call.continuation());
                assertEquals(1, calls.get());
                assertThrows(IllegalStateException.class, () -> call.execute(() -> false));
                assertEquals(1, calls.get());
            } finally {
                server.close();
                peer.join(5000);
                assertFalse(peer.isAlive(), "The owned loopback peer must exit");
            }
            assertNull(peerFailure.get());
            for (byte value : owned)
                assertEquals(0, value, "Confirmed physical close clears only the owned request bytes");
        }
    }

    @Test
    void incompleteRealResponseRemainsUnknownAndCannotBeSentAgain() throws Exception {
        var calls = new AtomicInteger();
        byte[] owned = "{\"private-request\":true}".getBytes(StandardCharsets.UTF_8);
        var peerFailure = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    calls.incrementAndGet();
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var headers = new StringBuilder();
                    while (!headers.toString().endsWith("\r\n\r\n") && headers.length() < 8192) {
                        int next = input.read();
                        if (next < 0) throw new IOException("Fixture request ended before its headers");
                        headers.append((char) next);
                    }
                    input.readNBytes(owned.length);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\nConnection: close\r\n\r\n"
                        + "private-provider-body").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException failure) {
                    peerFailure.set(failure);
                }
            });
            try (var call = new ModelHttpCall(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/private-endpoint"),
                "private-key".toCharArray(), owned, Duration.ofSeconds(5), "test-model", true)) {
                var result = call.execute(() -> false);
                assertEquals(TaskResult.UNKNOWN, result.outcome(), result::toString);
                var problem = result.problem().orElseThrow();
                assertEquals("AI_TRANSPORT_UNKNOWN", problem.eventCode());
                assertEquals("true", problem.context().get("httpHeadersReceived"));
                assertEquals("200", problem.context().get("httpStatus"));
                assertEquals(Integer.toString("private-provider-body".getBytes(StandardCharsets.UTF_8).length), problem.context().get("httpBodyBytesAccepted"));
                assertTrue(problem.context().get("transportFailureType").matches("java[.](?:io|net)[.][A-Za-z0-9_.$]+"), problem::toString);
                assertFalse(problem.toString().contains("private"), problem::toString);
                assertEquals(1, calls.get());
                assertThrows(IllegalStateException.class, () -> call.execute(() -> false));
                assertEquals(1, calls.get(), "Unknown provider outcomes must not replay the request");
            } finally {
                server.close();
                peer.join(5000);
                assertFalse(peer.isAlive(), "The task-owned HTTP fixture must exit");
            }
            assertNull(peerFailure.get());
            for (byte value : owned)
                assertEquals(0, value, "Owned request bytes must be cleared after physical client shutdown");
        }
    }

    @Test
    void deadlineBeforeResponseHeadersRecordsNoHeaderObservationWithoutReplay() throws Exception {
        assertStalledResponseObservation(false, false);
    }

    @Test
    void deadlineAfterResponseHeadersRecordsAcceptedBodyBytesWithoutReplay() throws Exception {
        assertStalledResponseObservation(true, false);
    }

    @Test
    void cancellationBeforeResponseHeadersRetainsUnknownAndClosesWithoutReplay() throws Exception {
        assertStalledResponseObservation(false, true);
    }

    private void assertStalledResponseObservation(boolean sendHeaders, boolean cancelAfterRequest) throws Exception {
        var calls = new AtomicInteger();
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var peerFailure = new AtomicReference<Throwable>();
        byte[] owned = "private-request".getBytes(StandardCharsets.UTF_8);
        byte[] partial = "private-provider-body".getBytes(StandardCharsets.UTF_8);
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    calls.incrementAndGet();
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var headers = new StringBuilder();
                    while (!headers.toString().endsWith("\r\n\r\n") && headers.length() < 8192) {
                        int next = input.read();
                        if (next < 0) throw new IOException("Fixture request ended before its headers");
                        headers.append((char) next);
                    }
                    if (input.readNBytes(owned.length).length != owned.length)
                        throw new IOException("Fixture request body was incomplete");
                    received.countDown();
                    if (sendHeaders) {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().write(partial);
                        socket.getOutputStream().flush();
                    }
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IOException("Fixture release was not confirmed");
                } catch (IOException | InterruptedException failure) {
                    peerFailure.set(failure);
                }
            });
            try {
                if (cancelAfterRequest) {
                    byte[] unsent = "private-unsent".getBytes(StandardCharsets.UTF_8);
                    try (var call = new ModelHttpCall(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/private-endpoint"),
                        "private-key".toCharArray(), unsent, Duration.ofSeconds(2), "test-model", true)) {
                        var result = call.execute(() -> true);
                        assertEquals(TaskResult.CANCELLED, result.outcome());
                        assertEquals("AI_CANCELLED_BEFORE_SEND", result.problem().orElseThrow().eventCode());
                        assertEquals("false", result.problem().orElseThrow().context().get("httpHeadersReceived"));
                        assertEquals("0", result.problem().orElseThrow().context().get("httpBodyBytesAccepted"));
                        assertFalse(result.problem().orElseThrow().context().containsKey("httpStatus"));
                        assertEquals(0, calls.get());
                    }
                    for (byte value : unsent) assertEquals(0, value);
                }
                try (var call = new ModelHttpCall(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/private-endpoint"),
                    "private-key".toCharArray(), owned, Duration.ofSeconds(2), "test-model", true)) {
                    var result = call.execute(() -> cancelAfterRequest && received.getCount() == 0);
                    assertEquals(TaskResult.UNKNOWN, result.outcome(), result::toString);
                    var problem = result.problem().orElseThrow();
                    assertEquals(cancelAfterRequest ? "AI_CANCELLED_AFTER_SEND" : "AI_TIMEOUT", problem.eventCode());
                    if (!cancelAfterRequest)
                        assertTrue(Set.of("REQUEST_BUDGET", "HTTP_REQUEST").contains(problem.context().get("timeoutPhase")));
                    assertEquals(Boolean.toString(sendHeaders), problem.context().get("httpHeadersReceived"));
                    assertEquals(Integer.toString(sendHeaders ? partial.length : 0), problem.context().get("httpBodyBytesAccepted"));
                    if (sendHeaders) assertEquals("200", problem.context().get("httpStatus"));
                    else assertFalse(problem.context().containsKey("httpStatus"));
                    assertFalse(problem.toString().contains("private"), problem::toString);
                    assertEquals(0, received.getCount());
                    assertEquals(1, calls.get());
                    assertThrows(IllegalStateException.class, () -> call.execute(() -> false));
                    assertEquals(1, calls.get());
                }
            } finally {
                release.countDown();
                server.close();
                peer.join(5000);
                assertFalse(peer.isAlive(), "The task-owned HTTP fixture must exit");
            }
            assertNull(peerFailure.get());
            for (byte value : owned) assertEquals(0, value, "Only confirmed client shutdown clears the owned bytes");
        }
    }

    @Test
    void timeoutDiagnosticsDistinguishPhasesWithoutExceptionMessagesOrApplicationTypes() {
        assertEquals(Map.of("transportFailureType", "java.net.http.HttpConnectTimeoutException", "timeoutPhase", "HTTP_CONNECT"),
            ModelHttpCall.transportDetails(new ExecutionException(new CompletionException(new HttpConnectTimeoutException("private-key/endpoint")))));
        assertEquals(Map.of("transportFailureType", "java.net.http.HttpTimeoutException", "timeoutPhase", "HTTP_REQUEST"),
            ModelHttpCall.transportDetails(new CompletionException(new HttpTimeoutException("private-provider-body"))));
        class PrivateFailure extends IOException {
        }
        assertEquals(Map.of("transportFailureType", "OTHER"), ModelHttpCall.transportDetails(new PrivateFailure()));
    }

    @Test
    void transportDiagnosticsClassifyStandardHttpBoundariesAndKeepNestedCausesBodyFree() {
        var nested = new IOException("HTTP/1.1 header parser received no bytes",
            new java.io.EOFException("private-provider-body/password=fixture"));
        var details = ModelHttpCall.transportDetails(new ExecutionException(nested));
        assertEquals("HTTP_HEADERS_EMPTY", details.get("transportFailureKind"));
        assertEquals("java.io.IOException>java.io.EOFException", details.get("transportCauseTypes"));
        assertFalse(details.toString().contains("private-provider-body"));
        assertFalse(details.toString().contains("password"));
        assertEquals("HTTP_BODY_INCOMPLETE", ModelHttpCall.transportDetails(
            new IOException("fixed content-length: 125, bytes received: 17")).get("transportFailureKind"));
        assertEquals("HTTP_CHUNK_INCOMPLETE", ModelHttpCall.transportDetails(
            new IOException("chunked transfer encoding, state: READING_LENGTH")).get("transportFailureKind"));
        assertEquals("RESPONSE_BYTE_LIMIT", ModelHttpCall.transportDetails(
            new IOException("AI response exceeded the configured transport limit")).get("transportFailureKind"));
        var privateFailure = new IOException("HTTP/1.1 header parser received no bytes") {
        };
        assertEquals(Map.of("transportFailureType", "OTHER"), ModelHttpCall.transportDetails(privateFailure));
    }
}

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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;

class ModelHttpCallTest {
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

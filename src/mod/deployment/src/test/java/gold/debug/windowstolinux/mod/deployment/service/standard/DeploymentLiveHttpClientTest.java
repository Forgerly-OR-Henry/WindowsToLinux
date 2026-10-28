package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies direct acceptance HTTP using local peers, without a deployment or model. / 使用本地端点验证直连验收，不执行部署或模型调用。 */
@ResourceLock("java.net.ProxySelector")
class DeploymentLiveHttpClientTest {
    @ParameterizedTest
    @ValueSource(strings = {"SAME_ORIGIN", "PATH_PREFIX", "SEPARATE_PORT"})
    void usesTheRuntimeConfiguredApiForBusinessAndBrowserRequests(String mode) throws Exception {
        var configuration = new java.util.concurrent.atomic.AtomicReference<String>();
        var reads = new AtomicInteger();
        var writes = new AtomicInteger();
        var preflights = new AtomicInteger();
        var frontend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        if (backend.getAddress().getPort() == frontend.getAddress().getPort() + 1) {
            var adjacent = backend;
            try {
                backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } finally {
                adjacent.stop(0);
            }
        }
        String web = "http://127.0.0.1:" + frontend.getAddress().getPort();
        String api = mode.equals("SEPARATE_PORT") ? "http://127.0.0.1:" + backend.getAddress().getPort()
            : mode.equals("PATH_PREFIX") ? web + "/gateway/" : web;
        configuration.set(mode.equals("SEPARATE_PORT") ? api : mode.equals("PATH_PREFIX") ? "/gateway/" : "");
        frontend.createContext("/runtime-config.json", exchange -> {
            byte[] data = ("{\"apiBase\":\"" + configuration.get() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
                output.write(data);
            } finally {
                exchange.close();
            }
        });
        var target = mode.equals("SEPARATE_PORT") ? backend : frontend;
        target.createContext(mode.equals("PATH_PREFIX") ? "/gateway//api/tasks" : "/api/tasks", exchange -> {
            String method = exchange.getRequestMethod();
            if (method.equals("OPTIONS")) preflights.incrementAndGet();
            else if (method.equals("POST")) writes.incrementAndGet();
            else if (method.equals("GET")) reads.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("X-Sample-Protocol", "2");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", web);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "content-type");
            exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Sample-Protocol");
            byte[] data = "{\"id\":17}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
                output.write(data);
            } finally {
                exchange.close();
            }
        });
        frontend.start();
        backend.start();
        try {
            if (mode.equals("SEPARATE_PORT"))
                assertNotEquals(frontend.getAddress().getPort() + 1, backend.getAddress().getPort());
            var actualBase = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("browserApiBase", String.class);
            actualBase.setAccessible(true);
            String selected = (String) actualBase.invoke(null, web);
            assertEquals(api, selected);
            assertEquals("{\"id\":17}", new String(request(URI.create(selected + "/api/tasks"), "POST",
                "{\"title\":\"retained\"}".getBytes(StandardCharsets.UTF_8), "application/json"), StandardCharsets.UTF_8));
            assertEquals("{\"id\":17}", new String(request(URI.create(selected + "/api/tasks/17"), "GET", null,
                "application/json"), StandardCharsets.UTF_8));
            var browser = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("browserApi", String.class, String.class, String.class);
            browser.setAccessible(true);
            var response = (com.fasterxml.jackson.databind.JsonNode) browser.invoke(null, web, "/api/tasks/17", "/api/tasks");
            assertEquals(17, response.path("id").asInt());
            assertEquals(2, reads.get());
            assertEquals(1, writes.get());
            assertEquals(mode.equals("SEPARATE_PORT") ? 2 : 0, preflights.get());
            for (String invalid : List.of("http://localhost:" + frontend.getAddress().getPort(), web.replace("http:", "https:"), "/")) {
                configuration.set(invalid);
                var refusal = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> actualBase.invoke(null, web));
                assertInstanceOf(org.opentest4j.AssertionFailedError.class, refusal.getCause());
            }
            assertEquals(2, reads.get(), "Foreign host/scheme must be rejected before an API request");
        } finally {
            backend.stop(0);
            frontend.stop(0);
        }
    }

    @Test
    void bypassesGlobalProxyThatClosesWithoutAnHttpResponse() throws Exception {
        var proxyHits = new AtomicInteger();
        byte[] expected = "真实直连网页：文件收发".getBytes(StandardCharsets.UTF_8);
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, expected.length);
            try (var output = exchange.getResponseBody()) {
                output.write(expected);
            } finally {
                exchange.close();
            }
        });
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxyHits.incrementAndGet();
            exchange.close();
        });
        var previous = ProxySelector.getDefault();
        target.start();
        proxy.start();
        HttpURLConnection defaultConnection = null;
        try {
            ProxySelector.setDefault(selected(new Proxy(Proxy.Type.HTTP, proxy.getAddress())));
            var uri = URI.create("http://127.0.0.1:" + target.getAddress().getPort() + "/");
            defaultConnection = (HttpURLConnection) uri.toURL().openConnection();
            defaultConnection.setConnectTimeout(2000);
            defaultConnection.setReadTimeout(2000);
            var original = defaultConnection;
            assertThrows(IOException.class, original::getResponseCode);
            assertTrue(proxyHits.get() > 0);
            int beforeDirect = proxyHits.get();
            assertArrayEquals(expected, request(uri, "GET", null, "application/json"));
            assertEquals(beforeDirect, proxyHits.get());
        } finally {
            if (defaultConnection != null) defaultConnection.disconnect();
            ProxySelector.setDefault(previous);
            proxy.stop(0);
            target.stop(0);
        }
    }

    @Test
    void preservesBinaryPostAndResponseWithoutConsultingTheGlobalSelector() throws Exception {
        byte[] expected = new byte[65536];
        new java.util.Random(402).nextBytes(expected);
        var selections = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            byte[] received = exchange.getRequestBody().readAllBytes();
            boolean matches = exchange.getRequestMethod().equals("POST")
                && "application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                && java.util.Arrays.equals(expected, received);
            exchange.sendResponseHeaders(matches ? 200 : 400, received.length);
            try (var output = exchange.getResponseBody()) {
                output.write(received);
            } finally {
                exchange.close();
            }
        });
        var previous = ProxySelector.getDefault();
        target.start();
        try {
            ProxySelector.setDefault(new ProxySelector() {
                public List<Proxy> select(URI uri) {
                    selections.incrementAndGet();
                    throw new IllegalStateException("The direct acceptance client must not query a global proxy");
                }

                public void connectFailed(URI uri, java.net.SocketAddress address, IOException failure) {
                    throw new AssertionError("Unexpected proxy connection");
                }
            });
            var uri = URI.create("http://127.0.0.1:" + target.getAddress().getPort() + "/");
            assertArrayEquals(expected, request(uri, "POST", expected, "application/octet-stream"));
            assertEquals(0, selections.get());
        } finally {
            ProxySelector.setDefault(previous);
            target.stop(0);
        }
    }

    @Test
    @Timeout(40)
    void chunkBudgetReceivesTwentyFiveSecondOneMiBAckOnce() throws Exception {
        try (var peer = new ChunkPeer("ACK", 25000, false)) {
            long started = System.nanoTime();
            chunk(peer.upload("2".repeat(32)));
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed >= 24000 && elapsed < 39000, "Actual delayed ACK: " + elapsed);
            assertEquals(1, peer.requests.get());
            assertEquals(CHUNK_BYTES.length, peer.received.get());
            System.out.println("LOCAL_CHUNK_DELAYED_ACK_VERIFIED bytes=1048576 requests=1 elapsedMillis=" + elapsed);
        }
    }

    @Test
    @Timeout(60)
    void silentChunkStillTimesOutOnceWithinTheFixedFortySecondBudget() throws Exception {
        try (var peer = new ChunkPeer("SILENT", 0, true)) {
            long started = System.nanoTime();
            assertThrows(java.net.SocketTimeoutException.class, () -> chunk(peer.upload("3".repeat(32))));
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed >= 39000 && elapsed < 50000, "Finite chunk read timeout: " + elapsed);
            assertEquals(1, peer.requests.get());
            System.out.println("LOCAL_CHUNK_SILENT_TIMEOUT elapsedMillis=" + elapsed + " requests=1");
        }
    }

    @Test
    void selectsOnlyCanonicalChunkPutsRegardlessOfTrace() throws Exception {
        var budget = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("responseReadTimeoutMillis", URI.class, String.class);
        budget.setAccessible(true);
        String base = "http://127.0.0.1/api/uploads/" + "a".repeat(32);
        for (String suffix : List.of("/chunks/0", "/chunks/49", "/chunks/9007199254740991", "/chunks/1?diagnostic=off"))
            assertEquals(40000, invoke(budget, URI.create(base + suffix), "PUT"));
        for (String suffix : List.of("", "/complete", "/cancel", "/chunks/-1", "/chunks/01", "/chunks/9007199254740992", "/chunks/1/"))
            assertEquals(20000, invoke(budget, URI.create(base + suffix), "PUT"));
        for (String method : List.of("GET", "POST", "HEAD"))
            assertEquals(20000, invoke(budget, URI.create(base + "/chunks/0"), method));
        for (String path : List.of("/api/uploads/invalid/chunks/0", "/other/api/uploads/" + "a".repeat(32) + "/chunks/0",
            "/api/versions/" + "a".repeat(32) + "/download", "/healthz", "/%61pi/uploads/" + "a".repeat(32) + "/chunks/0"))
            assertEquals(20000, invoke(budget, URI.create("http://127.0.0.1" + path), "PUT"));
        for (boolean traced : List.of(false, true)) {
            try (var peer = new ChunkPeer("ACK", 0, false)) {
                var ack = new com.fasterxml.jackson.databind.ObjectMapper().readTree(chunkRequest(peer.upload("4".repeat(32)), 0, traced));
                assertEquals(0, ack.path("number").asInt()); assertEquals(chunkSha(), ack.path("sha256").asText());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BAD_NUMBER", "BAD_SHA", "NON_2XX", "TRUNCATED", "CANCELLED", "ABORT", "REDIRECT"})
    @Timeout(12)
    void chunkBudgetNeverRescuesInvalidOrCancelledAcknowledgements(String mode) throws Exception {
        try (var peer = new ChunkPeer(mode, 0, false)) {
            Class<? extends Throwable> expected = switch (mode) {
                case "BAD_NUMBER", "BAD_SHA", "NON_2XX", "CANCELLED" -> org.opentest4j.AssertionFailedError.class;
                case "TRUNCATED" -> com.fasterxml.jackson.core.JsonProcessingException.class;
                case "REDIRECT" -> java.net.HttpRetryException.class;
                default -> IOException.class;
            };
            assertThrows(expected, () -> chunk(peer.upload("5".repeat(32))));
            assertEquals(CHUNK_BYTES.length, peer.received.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE", "CONFLICT", "CANCELLED"})
    @Timeout(12)
    void duplicateConflictAndCancelledPutsKeepTheirOriginalStatusContract(String mode) throws Exception {
        try (var peer = new ChunkPeer(mode, 0, false)) {
            int expected = mode.equals("DUPLICATE") ? 0 : 409;
            var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(chunkRequest(peer.upload("6".repeat(32)), expected, false));
            if (expected == 0) {
                assertTrue(result.path("duplicate").asBoolean());
                assertEquals(0, result.path("number").asInt()); assertEquals(chunkSha(), result.path("sha256").asText());
            } else assertEquals(mode.toLowerCase(java.util.Locale.ROOT), result.path("error").asText());
        }
    }

    private static byte[] chunkRequest(String upload, int status, boolean traced) throws Exception {
        var traceType = java.util.Arrays.stream(DeploymentStaticLiveAcceptanceTest.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("FileTransferChunkTrace")).findFirst().orElseThrow();
        var constructor = traceType.getDeclaredConstructor(String.class, int.class, int.class); constructor.setAccessible(true);
        Object trace = traced ? constructor.newInstance(upload.substring(upload.lastIndexOf('/') + 1), 0, CHUNK_BYTES.length) : null;
        var method = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("http", String.class, String.class, byte[].class,
            String.class, int.class, traceType); method.setAccessible(true);
        return (byte[]) invoke(method, upload + "/chunks/0", "PUT", CHUNK_BYTES, "application/octet-stream", status, trace);
    }

    private static final byte[] CHUNK_BYTES = new byte[1024 * 1024];
    static { new java.util.Random(20261028).nextBytes(CHUNK_BYTES); }

    private static String chunkSha() throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(CHUNK_BYTES));
    }

    private static void chunk(String upload) throws Exception {
        var method = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("fileTransferChunk", String.class, int.class, byte[].class);
        method.setAccessible(true);
        invoke(method, upload, 0, CHUNK_BYTES);
    }

    private static Object invoke(java.lang.reflect.Method method, Object... arguments) throws Exception {
        try { return method.invoke(null, arguments); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }

    /** One real loopback PUT; it verifies the full body before scheduling any reply. */
    private static final class ChunkPeer implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger(), received = new AtomicInteger();
        final java.util.concurrent.CountDownLatch accepted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.Set<Thread> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.ExecutorService executor;
        final HttpServer server;
        final String mode;

        ChunkPeer(String mode, long delayMillis, boolean expectedDisconnect) throws Exception {
            this.mode = mode;
            executor = java.util.concurrent.Executors.newFixedThreadPool(1, action -> {
                var thread = new Thread(action, "local-chunk-ack-peer"); threads.add(thread); return thread;
            });
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                try {
                    assertEquals("PUT", exchange.getRequestMethod());
                    assertEquals("1048576", exchange.getRequestHeaders().getFirst("Content-Length"));
                    assertEquals("application/octet-stream", exchange.getRequestHeaders().getFirst("Content-Type"));
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    received.set(bytes.length);
                    assertArrayEquals(CHUNK_BYTES, bytes);
                    assertEquals(chunkSha(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
                    accepted.countDown();
                    if (mode.equals("SILENT")) { release.await(55, java.util.concurrent.TimeUnit.SECONDS); return; }
                    if (delayMillis > 0 && release.await(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
                    if (mode.equals("ABORT")) return;
                    int status = mode.equals("REDIRECT") ? 307 : mode.equals("NON_2XX") ? 503 : mode.equals("CONFLICT") || mode.equals("CANCELLED") ? 409 : 200;
                    if (mode.equals("REDIRECT")) exchange.getResponseHeaders().set("Location", upload("7".repeat(32)) + "/chunks/0");
                    String body = status >= 400 ? "{\"error\":\"" + mode.toLowerCase(java.util.Locale.ROOT) + "\"}"
                        : "{\"number\":" + (mode.equals("BAD_NUMBER") ? 1 : 0) + ",\"sha256\":\""
                            + (mode.equals("BAD_SHA") ? "0".repeat(64) : chunkSha()) + "\",\"duplicate\":" + mode.equals("DUPLICATE") + "}";
                    byte[] response = (mode.equals("TRUNCATED") ? "{\"number\":" : body).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, mode.equals("TRUNCATED") ? response.length + 64 : response.length);
                    exchange.getResponseBody().write(response);
                } catch (IOException disconnected) {
                    if (!expectedDisconnect && !mode.equals("TRUNCATED")) failure.compareAndSet(null, disconnected);
                } catch (Throwable invalid) {
                    failure.compareAndSet(null, invalid);
                } finally {
                    exchange.close(); finished.countDown();
                }
            });
            server.start();
        }

        String upload(String id) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/uploads/" + id; }

        @Override
        public void close() throws Exception {
            if (mode.equals("SILENT")) release.countDown();
            boolean done = finished.await(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) release.countDown();
            server.stop(0); executor.shutdown();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS), "Peer executor must exit");
            for (var thread : threads) { thread.join(1000); assertFalse(thread.isAlive()); }
            assertTrue(done, "Controlled response handler must exit");
            assertEquals(1, requests.get(), "No retry, redirect, GET recovery or second PUT");
            assertNull(failure.get(), () -> "Controlled peer failed: " + failure.get());
            System.out.println("LOCAL_CHUNK_PEER_CLOSED requests=1 bytes=" + received.get() + " workers=0");
        }
    }

    private static ProxySelector selected(Proxy proxy) {
        return new ProxySelector() {
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            public void connectFailed(URI uri, java.net.SocketAddress address, IOException failure) {
            }
        };
    }

    private static byte[] request(URI uri, String method, byte[] body, String type) throws Exception {
        var actual = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("http", String.class,
            String.class, byte[].class, String.class);
        actual.setAccessible(true);
        try {
            return (byte[]) actual.invoke(null, uri.toString(), method, body, type);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
}

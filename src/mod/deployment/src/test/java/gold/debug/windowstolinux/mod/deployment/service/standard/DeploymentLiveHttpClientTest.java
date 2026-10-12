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

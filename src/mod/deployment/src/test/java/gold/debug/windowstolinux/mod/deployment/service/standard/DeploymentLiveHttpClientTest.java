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

/** Verifies direct acceptance HTTP using local peers, without a deployment or model. / 使用本地端点验证直连验收，不执行部署或模型调用。 */
@ResourceLock("java.net.ProxySelector")
class DeploymentLiveHttpClientTest {
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

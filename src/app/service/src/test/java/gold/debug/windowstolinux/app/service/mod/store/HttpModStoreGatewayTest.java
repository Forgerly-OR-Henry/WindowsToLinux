package gold.debug.windowstolinux.app.service.mod.store;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.service.mod.store.StoreDeclarations.*;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpModStoreGatewayTest {
    @TempDir
    Path root;

    static final byte[] PAYLOAD = "body".getBytes(StandardCharsets.UTF_8);

    static ModStoreGateway.Release declaration(String sha, long bytes) throws Exception {
        var original = release("root", "1.0.0", Map.of());
        return new ModStoreGateway.Release(original.manifest(), URI.create("root-1.0.0.wtlmod"), bytes, sha, Map.of());
    }

    @Test
    void actualCatalogAndStreamingDownloadPublishOnlyCompleteOriginal() throws Exception {
        try (var server = new Server()) {
            var release = declaration(ModStoreProtocol.hash(PAYLOAD), 4);
            server.handler("/store/catalog.json", exchange -> reply(exchange, 200, wire(release)));
            server.handler("/store/root-1.0.0.wtlmod", exchange -> reply(exchange, 200, PAYLOAD));
            var gateway = new HttpModStoreGateway(Duration.ofSeconds(5));
            assertEquals("root", gateway.catalog(server.base(), () -> false).releases().getFirst().owner());
            var progress = new ArrayList<ModStoreGateway.Progress>();
            var target = root.resolve("store-real.wtlmod");
            var downloaded = gateway.download(server.base(), release, target, progress::add, () -> false);
            assertArrayEquals(PAYLOAD, Files.readAllBytes(target));
            assertTrue(gateway.verify(release, downloaded).verified());
            assertEquals(4, progress.getLast().completed());
            assertEquals(4, progress.getLast().total());
            assertEquals(List.of("store-real.wtlmod"), children());
        }
    }

    @Test
    void bodyDeadlineAppliesAfterHeadersAndCleansActualPartialTemporary() throws Exception {
        try (var server = new Server()) {
            server.handler("/store/root-1.0.0.wtlmod", exchange -> {
                exchange.sendResponseHeaders(200, 4);
                exchange.getResponseBody().write('b');
                exchange.getResponseBody().flush();
                server.started.countDown();
                server.release.await(10, TimeUnit.SECONDS);
                try {
                    exchange.getResponseBody().write("ody".getBytes(StandardCharsets.UTF_8));
                } finally {
                    exchange.close();
                }
            });
            long begin = System.nanoTime();
            assertThrows(HttpTimeoutException.class, () -> new HttpModStoreGateway(Duration.ofMillis(300))
                .download(server.base(), declaration(ModStoreProtocol.hash(PAYLOAD), 4), root.resolve("deadline.wtlmod"), ignored -> {
                }, () -> false));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin) < 5);
            assertEquals(List.of(), children());
        }
    }

    @Test
    void cooperativeCancelStopsActualStreamWithoutPublishingHalfPackage() throws Exception {
        try (var server = new Server(); var worker = Executors.newSingleThreadExecutor()) {
            server.handler("/store/root-1.0.0.wtlmod", exchange -> {
                exchange.sendResponseHeaders(200, 4);
                exchange.getResponseBody().write('b');
                exchange.getResponseBody().flush();
                server.started.countDown();
                server.release.await(10, TimeUnit.SECONDS);
                exchange.close();
            });
            var cancelled = new AtomicBoolean();
            var future = worker.submit(() -> new HttpModStoreGateway(Duration.ofSeconds(5)).download(server.base(),
                declaration(ModStoreProtocol.hash(PAYLOAD), 4), root.resolve("cancel.wtlmod"), ignored -> {
                }, cancelled::get));
            assertTrue(server.started.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            var failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(List.of(), children());
        }
    }

    @Test
    void digestLengthRedirectAndWrongEncodingNeverPublishAnOriginal() throws Exception {
        for (String scenario : List.of("digest", "length", "redirect", "encoding")) {
            try (var server = new Server()) {
                server.handler("/store/root-1.0.0.wtlmod", exchange -> {
                    if (scenario.equals("redirect")) {
                        exchange.getResponseHeaders().add("Location", "/store/other.wtlmod");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    } else {
                        if (scenario.equals("encoding")) exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                        reply(exchange, 200, PAYLOAD);
                    }
                });
                var release = declaration(scenario.equals("digest") ? "0".repeat(64) : ModStoreProtocol.hash(PAYLOAD), scenario.equals("length") ? 5 : 4);
                assertThrows(IOException.class, () -> new HttpModStoreGateway(Duration.ofSeconds(5)).download(server.base(), release,
                    root.resolve(scenario + ".wtlmod"), ignored -> {
                    }, () -> false));
                assertEquals(List.of(), children());
            }
        }
    }

    @Test
    void unsafeHttpRootAndExistingDestinationAreRejectedWithoutNetwork() throws Exception {
        var gateway = new HttpModStoreGateway(Duration.ofSeconds(1));
        for (String base : List.of("http://localhost/store", "http://10.0.0.1/store", "http://127.0.0.1:0/store"))
            assertThrows(IOException.class, () -> gateway.catalog(URI.create(base), () -> false));
        var target = root.resolve("existing.wtlmod");
        Files.writeString(target, "preserved");
        assertThrows(IOException.class, () -> gateway.download(BASE, declaration(ModStoreProtocol.hash(PAYLOAD), 4), target, ignored -> {
        }, () -> false));
        assertEquals("preserved", Files.readString(target));
    }

    @Test
    void catalogCannotExceedEightMibDuringActualHttpBody() throws Exception {
        try (var server = new Server()) {
            server.handler("/store/catalog.json", exchange -> reply(exchange, 200, new byte[ModStoreProtocol.MAX_CATALOG_BYTES + 1]));
            assertThrows(IOException.class, () -> new HttpModStoreGateway(Duration.ofSeconds(5)).catalog(server.base(), () -> false));
        }
    }

    List<String> children() throws IOException {
        try (var files = Files.list(root)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    interface Work {
        void handle(HttpExchange exchange) throws Exception;
    }

    static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    static final class Server implements AutoCloseable {
        final HttpServer server;

        final ExecutorService workers = Executors.newFixedThreadPool(2);

        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);

        Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.start();
        }

        URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/store/");
        }

        void handler(String path, Work work) {
            server.createContext(path, exchange -> {
                try {
                    work.handle(exchange);
                } catch (Exception failure) {
                    exchange.close();
                }
            });
        }

        public void close() throws Exception {
            release.countDown();
            server.stop(0);
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Actual HTTP test workers remain");
        }
    }
}

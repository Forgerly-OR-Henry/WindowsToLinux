package gold.debug.windowstolinux.web.main;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Owns an argument-free WebMain child process and proves cleanup on every exit path. */
final class WebProcessProbe implements AutoCloseable {
    private final Process process;
    private final Path log;
    private final String origin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private WebProcessProbe(Process process, Path log, int port) { this.process = process; this.log = log; origin = "http://127.0.0.1:" + port; }

    static WebProcessProbe launch(List<String> arguments, Path working, Path keys) throws Exception {
        Files.createDirectories(working); int port;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        Files.writeString(working.resolve("application.yml"), "server:\n  address: 127.0.0.1\n  port: " + port
                + "\nw2l:\n  secrets:\n    directory: '" + keys.toString().replace("'", "''") + "'\n");
        Path log = working.resolve("startup-" + UUID.randomUUID() + ".log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(working.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        for (String name : List.of("WEB_TEST_MASTER_KEY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(name);
        // Keep JDK temporary sockets in the test runner's isolated, short temporary directory.
        builder.environment().put("TEMP", System.getProperty("java.io.tmpdir"));
        builder.environment().put("TMP", System.getProperty("java.io.tmpdir"));
        var probe = new WebProcessProbe(builder.start(), log, port);
        try { probe.awaitReady(); return probe; } catch (Exception | AssertionError failure) { probe.close(); throw failure; }
    }

    private void awaitReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), () -> diagnostic());
            try { if (get("/api/v1/health").statusCode() == 200) return; } catch (IOException ignored) { }
            Thread.sleep(100);
        }
        fail("Web process did not become ready: " + diagnostic());
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> json(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5))
                .header("Origin", origin).header("X-W2L-Client", "web").header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String diagnostic() { try { return Files.readString(log); } catch (IOException failure) { return "Startup log unavailable"; } }
    @Override public void close() throws Exception {
        try {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
            assertFalse(process.isAlive());
        } finally { client.close(); }
    }
}

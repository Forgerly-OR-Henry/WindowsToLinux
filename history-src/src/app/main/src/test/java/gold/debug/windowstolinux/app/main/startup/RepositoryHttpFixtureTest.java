package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** Builds complete multi-file JARs and exercises their modules over HTTP. / 构建完整多文件 JAR 并经 HTTP 验证模块协作。 */
class RepositoryHttpFixtureTest {
    @TempDir
    Path temporaryDirectory;

    @TestFactory
    Stream<DynamicTest> successAndHealthFailureHaveDistinctObservableBehavior() {
        return List
                .of("success-deployment-smoke", "success-json-api", "success-runtime-config", "failure-health-rollback")
                .stream().map(scenario -> DynamicTest.dynamicTest(scenario, () -> exercise(scenario)));
    }

    private void exercise(String scenario) throws Exception {
        exercise(scenario, "多文件服务-中文");
    }

    private void exercise(String scenario, String label) throws Exception {
        Path fixture = repositoryRoot().resolve("test/single-language/java/jdk/http-service").resolve(scenario);
        Path classes = Files.createDirectories(
                temporaryDirectory.resolve(scenario + (label == null ? "-default" : "")).resolve("classes"));
        assertEquals(0, compile(fixture, classes, false));
        Path artifact = classes.getParent().resolve("app.jar");
        packageJar(classes, artifact);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-jar", artifact.toString());
        builder.environment().put("PORT", Integer.toString(port));
        if (label == null)
            builder.environment().remove("FIXTURE_LABEL");
        else
            builder.environment().put("FIXTURE_LABEL", label);
        builder.redirectErrorStream(true).redirectOutput(classes.getParent().resolve("server.log").toFile());
        Process process = builder.start();
        try {
            HttpResult result = awaitHttp(process, port, classes.getParent().resolve("server.log"));
            assertEquals(scenario.equals("failure-health-rollback") ? 503 : 200, result.status());
            switch (scenario) {
                case "success-json-api" -> {
                    assertTrue(result.contentType().startsWith("application/json"));
                    var json = new ObjectMapper().readTree(result.body());
                    assertEquals("ok", json.required("status").asText());
                    assertEquals(3, json.required("items").size());
                    assertEquals(6, json.required("total").asInt());
                }
                case "success-runtime-config" ->
                    assertEquals(label == null ? "runtime-config-default" : label, result.body());
                default -> assertEquals("deployment-smoke-ok", result.body());
            }
            if (!scenario.equals("failure-health-rollback")) {
                assertSummary(request(port, "/api/summary"), List.of(1, 2, 3));
                assertSummary(request(port, "/api/summary?values=2,3,5"), List.of(2, 3, 5));
                assertSummary(request(port, "/api/summary?values=0,10000"), List.of(0, 10000));
                assertSummary(request(port, "/api/summary?values=2%2C3%2C5"), List.of(2, 3, 5));
                assertSummary(
                        request(port,
                                "/api/summary?values=" + String.join(",", java.util.Collections.nCopies(20, "10000"))),
                        java.util.Collections.nCopies(20, 10000));
                for (String invalid : List.of("", "-1", "1.5", "abc", "10001", "1,,2", "1,", "9999999999",
                        String.join(",", java.util.Collections.nCopies(21, "1")))) {
                    assertEquals(400, request(port, "/api/summary?values=" + invalid).status(), invalid);
                }
                assertSummary(request(port, "/api/summary?values=4,6"), List.of(4, 6));
            } else {
                assertEquals(503, request(port, "/api/summary?values=2,3,5").status());
                assertEquals(503, request(port, "/").status());
            }
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "fixture process did not exit");
            }
            process.getOutputStream().close();
            deleteStoppedFixture(artifact);
        }
    }

    private static void deleteStoppedFixture(Path artifact) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                Files.deleteIfExists(artifact);
                return;
            } catch (java.nio.file.FileSystemException locked) {
                if (System.nanoTime() >= deadline)
                    throw locked;
                Thread.sleep(50);
            }
        }
    }

    @Test
    void omittingTheModelPreventsACompleteBuild() throws Exception {
        Path fixture = repositoryRoot().resolve("test/single-language/java/jdk/http-service/success-json-api");
        Path classes = Files.createDirectories(temporaryDirectory.resolve("missing-model"));
        assertNotEquals(0, compile(fixture, classes, true));
    }

    @Test
    void runtimeConfigurationHasAnUnconfiguredDefault() throws Exception {
        exercise("success-runtime-config", null);
    }

    private static int compile(Path fixture, Path classes, boolean omitModel) throws Exception {
        List<String> arguments = new ArrayList<>(
                List.of("--release", "21", "-encoding", "UTF-8", "-d", classes.toString()));
        try (var sources = Files.walk(fixture.resolve("src"))) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !omitModel || !path.getFileName().toString().equals("Summary.java")).sorted()
                    .forEach(path -> arguments.add(path.toString()));
        }
        var diagnostics = new java.io.ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                arguments.toArray(String[]::new));
        if (!omitModel)
            assertEquals(0, result, diagnostics.toString(StandardCharsets.UTF_8));
        return result;
    }

    private static void packageJar(Path classes, Path artifact) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "acceptance.Main");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact), manifest);
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static void assertSummary(HttpResult result, List<Integer> values) throws Exception {
        assertEquals(200, result.status());
        assertTrue(result.contentType().startsWith("application/json"));
        var json = new ObjectMapper().readTree(result.body());
        assertEquals("ok", json.required("status").asText());
        assertEquals(values.stream().mapToInt(Integer::intValue).sum(), json.required("total").asInt());
        assertEquals(new ObjectMapper().valueToTree(values), json.required("items"));
    }

    private static HttpResult request(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL()
                .openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(2000);
        try {
            int status = connection.getResponseCode();
            try (var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                byte[] bytes = input.readAllBytes();
                assertEquals(bytes.length, connection.getContentLengthLong(), "UTF-8 content length");
                return new HttpResult(status, connection.getContentType(), new String(bytes, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpResult awaitHttp(Process process, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception lastError = null;
        do {
            assertTrue(process.isAlive(), () -> "fixture stopped before HTTP verification: " + readLog(log));
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL()
                    .openConnection();
            connection.setConnectTimeout(300);
            connection.setReadTimeout(1000);
            try {
                int status = connection.getResponseCode();
                try (var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    return new HttpResult(status,
                            connection.getContentType() == null ? "" : connection.getContentType(),
                            new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException error) {
                lastError = error;
                Thread.sleep(40);
            } finally {
                connection.disconnect();
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("fixture HTTP response was unavailable", lastError);
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (java.io.IOException error) {
            return error.toString();
        }
    }

    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("test/single-language/matrix.json")))
                return path;
        }
        throw new IllegalStateException("fixture repository root was not found");
    }

    private record HttpResult(int status, String contentType, String body) {
    }
}

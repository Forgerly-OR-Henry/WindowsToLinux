package gold.debug.windowstolinux.mod.deployment.linux.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

final class DeploymentMirrorRuntimeTest {
    @Test
    void olderTasksRetainTheirOriginalScript() {
        assertEquals("set -e\necho original\n", DeploymentMirrorRuntime.wrapBuild("set -e\necho original\n", ""));
        assertEquals("echo preparation", DeploymentMirrorRuntime.wrapPreparation("echo preparation", null));
    }

    @Test
    void renderedBootstrapHasValidShellSyntaxAndQuotesFrozenInput() throws Exception {
        Path directory = directory();
        try {
            String policy = """
                {"formatVersion":1,"task":"00000000-0000-0000-0000-000000000001","timeouts":{"connectSeconds":15,"readSeconds":90,"noProgressSeconds":45,"lowSpeedWindowSeconds":90,"lowSpeedBytesPerSecond":32768,"attemptsPerEndpoint":1},"points":{"NODE_PACKAGE":[],"APT_DEBIAN":[{"providerId":"company","protocol":"APT","baseUrl":"https://mirror.example/debian/","allowedHosts":["mirror.example"],"official":false,"custom":true,"routes":[{"origin":"https://origin.example/debian/","prefix":"https://mirror.example/debian/"}],"repoTemplates":{}}]}}
                """;
            String script = DeploymentMirrorRuntime.wrapBuild("printf 'finished\\n'\n", policy);
            assertTrue(script.contains("wtl_apt()"));
            assertTrue(script.contains("WTL_MIRROR_POLICY"));
            assertTrue(script.contains("wtl_mirror_connect_seconds=15\nwtl_mirror_idle_seconds=45"));
            assertTrue(script.contains("wtl_mirror_low_window_seconds=90\nwtl_mirror_low_rate=32768\nwtl_mirror_attempts=1"));
            assertTrue(script.contains("printf 'finished\\n'\n"));
            assertTrue(script.endsWith("\n)\n"));
            Path file = directory.resolve("bootstrap.sh");
            Files.writeString(file, script);
            String bash = System.getProperty("os.name").startsWith("Windows")
                ? "E:\\Program\\Git\\bin\\bash.exe" : "/bin/bash";
            execute(directory, List.of(bash, "-n", file.toString()), Duration.ofSeconds(15));
        } finally {
            cleanup(directory);
        }
    }

    @Test
    void executesBoundedTransfersAndTaskLocalProtocolAdaptersAgainstLoopback() throws Exception {
        Path directory = directory();
        try {
            Path runtime = directory.resolve("runtime.py");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            Path test = directory.resolve("runtime_test.py");
            try (var stream = getClass().getResourceAsStream("runtime_test.py")) {
                if (stream == null) throw new IOException("Mirror integration test resource absent");
                Files.copy(stream, test);
            }
            try (var stream = getClass().getResourceAsStream("apt_native_fixture.py")) {
                if (stream == null) throw new IOException("APT native fixture resource absent");
                Files.copy(stream, directory.resolve("apt_native_fixture.py"));
            }
            Path metadata = directory.resolve("release-metadata.py");
            try (var stream = getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain/10-release-metadata.py")) {
                if (stream == null) throw new IOException("Toolchain metadata resource absent");
                Files.writeString(metadata, DeploymentMirrorRuntime.renderToolchainSources(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            }
            List<String> arguments = new ArrayList<>();
            if (System.getProperty("os.name").startsWith("Windows")) arguments.addAll(List.of("py", "-3"));
            else arguments.add("python3");
            arguments.add(test.toString());
            arguments.add(runtime.toString());
            arguments.add(metadata.toString());
            String output = execute(directory, arguments, Duration.ofSeconds(60));
            assertTrue(output.contains("OK"), output);
            assertFalse(output.contains("FAILED"), output);
        } finally {
            cleanup(directory);
        }
    }

    private static String execute(Path directory, List<String> command, Duration timeout) throws Exception {
        Path log = directory.resolve("process-" + UUID.randomUUID() + ".log");
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile());
        builder.environment().put("TEMP", directory.toString());
        builder.environment().put("TMP", directory.toString());
        builder.environment().put("TMPDIR", directory.toString());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start();
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (!finished) throw new AssertionError("Mirror test process exceeded its bounded wait\n" + output);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor();
        }
    }

    private static Path directory() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve(".ai-workspace"))) root = root.getParent();
        if (root == null) throw new IOException("Project .ai-workspace not found");
        return Files.createDirectories(root.resolve(".ai-workspace/tmp/deployment-mirrors-20261104/runtime/junit-" + UUID.randomUUID()));
    }

    private static void cleanup(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}

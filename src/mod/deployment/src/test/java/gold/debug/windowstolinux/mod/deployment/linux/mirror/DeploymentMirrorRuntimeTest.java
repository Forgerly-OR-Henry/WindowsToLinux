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
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.mirror.MirrorSelector;
import gold.debug.windowstolinux.mod.deployment.service.mirror.MirrorSettings;

final class DeploymentMirrorRuntimeTest {
    @Test
    void goModuleRedirectRulesPreserveStrictInitialAndPerHopBoundaries() throws Exception {
        Path directory = Files.createTempDirectory("go-module-redirect-");
        try {
            Path runtime = directory.resolve("runtime.py");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            Path test = directory.resolve("go_redirect_test.py");
            try (var stream = getClass().getResourceAsStream("go_redirect_test.py")) {
                if (stream == null) throw new IOException("Go redirect fixture absent");
                Files.copy(stream, test);
            }
            var settings = new MirrorSettings(1, java.util.Map.of(DownloadPoint.GO_MODULE, List.of(
                new MirrorSettings.Row("official-go_module", "", false, false, ""))), java.util.Map.of());
            var policy = MirrorSelector.resolve(MirrorCatalog.load(), settings, "synthetic-server", "US");
            Path frozen = directory.resolve("policy.json");
            Files.writeString(frozen, policy.toJson());
            var command = new ArrayList<String>();
            if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("py", "-3"));
            else command.add("python3");
            command.addAll(List.of(test.toString(), runtime.toString(), frozen.toString()));
            System.out.println("GO_REDIRECT_JVM pid=" + ProcessHandle.current().pid() + " birth="
                + ProcessHandle.current().info().startInstant().map(Object::toString).orElse("UNKNOWN")
                + " cwd=" + Path.of("").toAbsolutePath() + " java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " jna.tmpdir=" + System.getProperty("jna.tmpdir")
                + " jdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir"));
            String output = execute(directory, command, Duration.ofSeconds(30));
            System.out.println(output);
            assertTrue(output.contains("OK"), output);
            assertFalse(output.contains("FAILED"), output);
        } finally { cleanup(directory); }
    }

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

    @Test
    void nativeHelperAptPolicyReachesPlanAndFinalNoDownloadArguments() throws Exception {
        Path directory = Files.createTempDirectory("native-helper-apt-");
        try {
            var installer = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.class;
            var scripts = List.of(
                gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.render(),
                gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.renderFiles(),
                gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.renderTracked(
                    UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "b".repeat(64)));
            var batches = new ArrayList<List<String>>();
            for (String script : scripts) {
                var invocation = java.util.regex.Pattern.compile("(?m)^\\s*deployment_apt (.* install .*)$").matcher(script);
                assertTrue(invocation.find(), installer.getSimpleName());
                var arguments = List.of(invocation.group(1).trim().split(" +"));
                assertFalse(invocation.find(), "Only one native APT install per installer");
                assertTrue(arguments.containsAll(List.of("--no-upgrade", "--no-remove", "--no-install-recommends")));
                assertEquals(List.of(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.APT_PACKAGES.split(" ")),
                    arguments.subList(arguments.indexOf("--no-install-recommends") + 1, arguments.size()));
                batches.add(arguments);
            }
            Path runtime = directory.resolve("runtime.py");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            Path test = directory.resolve("runtime_test.py");
            for (String resource : List.of("runtime_test.py", "apt_native_fixture.py")) {
                try (var stream = getClass().getResourceAsStream(resource)) {
                    if (stream == null) throw new IOException("Native APT test resource absent");
                    Files.copy(stream, directory.resolve(resource));
                }
            }
            Path argumentsFile = directory.resolve("native-helper-apt-arguments.json");
            Files.writeString(argumentsFile, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(batches));
            var command = new ArrayList<String>();
            if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("py", "-3"));
            else command.add("python3");
            command.addAll(List.of(test.toString(), runtime.toString(), "unused-metadata", argumentsFile.toString()));
            String output = execute(directory, command, Duration.ofSeconds(60));
            System.out.println(output);
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

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
    void pythonNamedIndexesPreserveArtifactNamesAndMetadataBindings() throws Exception {
        runPythonIndexFixture(List.of(), 11);
    }

    @Test
    void pythonHtmlIndexUsesParserConsistentAttributesAndOffsets() throws Exception {
        runPythonIndexFixture(List.of(
            "PythonIndexTests.test_named_html_lexical_variants_install_without_upstream_bypass",
            "PythonIndexTests.test_html_rewrite_matches_parser_attributes_positions_and_pip_links",
            "PythonIndexTests.test_named_html_index_installs_unchanged_hashed_wheel",
            "PythonIndexTests.test_named_index_still_rejects_a_nonmatching_lock_hash",
            "PythonIndexTests.test_percent_encoding_html_entities_relative_json_and_metadata_query_binding"), 5);
    }

    private void runPythonIndexFixture(List<String> selected, int count) throws Exception {
        Path ownTmp = Path.of(System.getenv("TEMP")).toAbsolutePath();
        Path directory = Files.createTempDirectory(ownTmp, "python-named-index-");
        Path cwd = Path.of("").toAbsolutePath();
        try {
            Path runtime = directory.resolve("runtime.py");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            Path test = directory.resolve("python_index_test.py");
            try (var stream = getClass().getResourceAsStream("python_index_test.py")) {
                if (stream == null) throw new IOException("Python named-index fixture absent");
                Files.copy(stream, test);
            }
            var command = new ArrayList<String>();
            if (System.getProperty("wtl.python.executable") != null) command.add(System.getProperty("wtl.python.executable"));
            else if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("py", "-3"));
            else command.add("python3");
            command.addAll(List.of("-B", test.toString(), runtime.toString()));
            command.addAll(selected);
            var self = ProcessHandle.current();
            System.out.println("PYTHON_INDEX_JVM pid=" + self.pid() + " birth="
                + self.info().startInstant().map(instant -> new java.time.format.DateTimeFormatterBuilder()
                    .appendInstant(7).toFormatter().format(instant)).orElse("UNKNOWN")
                + " birthFiletime=" + self.info().startInstant().map(instant -> Long.toString(
                    Math.addExact(Math.multiplyExact(instant.getEpochSecond() + 11644473600L, 10000000L), instant.getNano() / 100))).orElse("UNKNOWN")
                + " cwd=" + cwd + " java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " jna.tmpdir=" + System.getProperty("jna.tmpdir")
                + " jdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir"));
            Path log = directory.resolve("output.log");
            var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = builder.start();
            System.out.println("PYTHON_INDEX_LAUNCHER pid=" + process.pid() + " birth="
                + process.info().startInstant().map(instant -> new java.time.format.DateTimeFormatterBuilder()
                    .appendInstant(7).toFormatter().format(instant)).orElse("UNKNOWN")
                + " birthFiletime=" + process.info().startInstant().map(instant -> Long.toString(
                    Math.addExact(Math.multiplyExact(instant.getEpochSecond() + 11644473600L, 10000000L), instant.getNano() / 100))).orElse("UNKNOWN"));
            try {
                boolean finished = process.waitFor(45, TimeUnit.SECONDS);
                String output = Files.readString(log, StandardCharsets.UTF_8);
                System.out.println(output);
                assertTrue(finished, "Python named-index fixture exceeded its bounded wait\n" + output);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("Ran " + count + " tests"), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                process.waitFor();
                System.out.println("PYTHON_INDEX_LAUNCHER_EXIT pid=" + process.pid() + " exit=" + process.exitValue() + " alive=" + process.isAlive());
            }
        } finally {
            cleanup(directory);
        }
    }

    @Test
    void goModuleRedirectRulesPreserveStrictInitialAndPerHopBoundaries() throws Exception {
        runRedirectFixture(DownloadPoint.GO_MODULE, "official-go_module", "go_redirect_test.py", "GO_REDIRECT", 8);
    }

    @Test
    void gradleDistributionRedirectRulesPreserveStrictInitialAndPerHopBoundaries() throws Exception {
        runRedirectFixture(DownloadPoint.GRADLE_DISTRIBUTION, "official-gradle_distribution",
            "gradle_redirect_test.py", "GRADLE_REDIRECT", 8);
    }

    @Test
    void realGradleCorePluginsRetainWrapperBindingsAndIdempotentRepositories() throws Exception {
        runGradleRepositoryFixture("core");
    }

    @Test
    void realGradleLocalArtifactsRetainExactPointOriginAndAdditionalArtifactRoutes() throws Exception {
        runGradleRepositoryFixture("local");
    }

    @Test
    void realGradleRejectsUnboundRepositoriesAndLateSourceChangesBeforeNetwork() throws Exception {
        runGradleRepositoryFixture("reject");
    }

    @Test
    void gradleRepositoryNotFoundRetainsExactLedgerEvidenceWithoutMaskingTransferFailures() throws Exception {
        runGradleRepositoryFixture("status");
    }

    private void runGradleRepositoryFixture(String group) throws Exception {
        String executable = System.getProperty("wtl.gradle.executable");
        String evidenceRoot = System.getProperty("wtl.gradle.test.evidence");
        assertTrue(executable != null && Files.isRegularFile(Path.of(executable)),
            "Supply an existing Gradle 8.10.2 executable with -Dwtl.gradle.executable; this test never downloads Gradle");
        assertTrue(evidenceRoot != null, "Supply task-owned evidence with -Dwtl.gradle.test.evidence");
        Path directory = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(), "gradle-repositories-");
        try {
            Path runtime = directory.resolve("runtime.py");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            Path fixture = directory.resolve("gradle_repository_test.py");
            try (var stream = getClass().getResourceAsStream("gradle_repository_test.py")) {
                if (stream == null) throw new IOException("Real Gradle repository fixture absent");
                Files.copy(stream, fixture);
            }
            Path evidence = Files.createDirectories(Path.of(evidenceRoot).toAbsolutePath().resolve(group + "-" + UUID.randomUUID()));
            var command = new ArrayList<String>();
            if (System.getProperty("wtl.python.executable") != null) command.add(System.getProperty("wtl.python.executable"));
            else if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("py", "-3"));
            else command.add("python3");
            command.addAll(List.of("-B", fixture.toString(), runtime.toString(), executable, evidence.toString(), group));
            System.out.println("GRADLE_REPOSITORY_JVM pid=" + ProcessHandle.current().pid() + " birth="
                + ProcessHandle.current().info().startInstant().map(Object::toString).orElse("UNKNOWN")
                + " cwd=" + Path.of("").toAbsolutePath() + " java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " jna.tmpdir=" + System.getProperty("jna.tmpdir")
                + " jdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir"));
            String output = execute(directory, command, Duration.ofMinutes(15));
            System.out.println(output);
            assertTrue(output.contains("Ran 1 test"), output);
            assertTrue(output.contains("OK"), output);
        } finally { cleanup(directory); }
    }

    private void runRedirectFixture(DownloadPoint point, String provider, String resource, String marker, int count) throws Exception {
        Path directory = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(), "mirror-redirect-");
        try {
            Path runtime = directory.resolve("runtime.py");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            Path test = directory.resolve(resource);
            try (var stream = getClass().getResourceAsStream(resource)) {
                if (stream == null) throw new IOException("Redirect fixture absent: " + resource);
                Files.copy(stream, test);
            }
            var settings = new MirrorSettings(1, java.util.Map.of(point, List.of(
                new MirrorSettings.Row(provider, "", false, false, ""))), java.util.Map.of());
            var policy = MirrorSelector.resolve(MirrorCatalog.load(), settings, "synthetic-server", "US");
            Path frozen = directory.resolve("policy.json");
            Files.writeString(frozen, policy.toJson());
            var command = new ArrayList<String>();
            if (System.getProperty("wtl.python.executable") != null) command.add(System.getProperty("wtl.python.executable"));
            else if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("py", "-3"));
            else command.add("python3");
            command.addAll(List.of("-B", test.toString(), runtime.toString(), frozen.toString()));
            System.out.println(marker + "_JVM pid=" + ProcessHandle.current().pid() + " birth="
                + ProcessHandle.current().info().startInstant().map(Object::toString).orElse("UNKNOWN")
                + " cwd=" + Path.of("").toAbsolutePath() + " java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " jna.tmpdir=" + System.getProperty("jna.tmpdir")
                + " jdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir"));
            String output = execute(directory, command, Duration.ofSeconds(30));
            System.out.println(output);
            assertTrue(output.contains("Ran " + count + " tests"), output);
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
        Path cwd = directory.toAbsolutePath();
        var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile());
        builder.environment().put("TEMP", directory.toString());
        builder.environment().put("TMP", directory.toString());
        builder.environment().put("TMPDIR", directory.toString());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process process = builder.start();
        System.out.println("MIRROR_FIXTURE_LAUNCHER pid=" + process.pid() + " birth="
            + process.info().startInstant().map(Object::toString).orElse("UNKNOWN") + " cwd=" + cwd);
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
            System.out.println("MIRROR_FIXTURE_LAUNCHER_EXIT pid=" + process.pid() + " exit=" + process.exitValue()
                + " alive=" + process.isAlive());
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

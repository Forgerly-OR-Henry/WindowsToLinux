package gold.debug.windowstolinux.mod.deployment.linux.mirror.locked;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.mirror.MirrorSelector;
import gold.debug.windowstolinux.mod.deployment.service.mirror.MirrorSettings;
import gold.debug.windowstolinux.mod.deployment.service.mirror.ResolvedMirrorPolicy;

final class LockedRepositoryTransportTest {
    @Test
    void nugetContentHashesPreserveSignedZipBytesAndOperationBindings() throws Exception {
        Path directory = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "nc-");
        Process process = null;
        try {
            Path runtime = directory.resolve("runtime.py"), output = directory.resolve("output.log");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            System.out.println("NUGET_HASH_JVM pid=" + ProcessHandle.current().pid() + " birth="
                + ProcessHandle.current().info().startInstant().map(Object::toString).orElse("UNKNOWN")
                + " cwd=" + Path.of("").toAbsolutePath() + " java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " jna.tmpdir=" + System.getProperty("jna.tmpdir")
                + " jdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir")
                + " runtimeSha256=" + DeploymentMirrorRuntime.runtimeSha256());
            for (String name : List.of("locked_transport_test.py", "nuget_content_hash_test.py")) {
                try (var input = getClass().getResourceAsStream(name)) {
                    if (input == null) throw new IOException("NuGet content hash fixture absent: " + name);
                    Files.copy(input, directory.resolve(name));
                }
            }
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            var command = List.of(windows ? "E:\\Program\\Python\\Python3.12\\python.exe" : "python3", "-B",
                directory.resolve("nuget_content_hash_test.py").toString(), runtime.toString(),
                windows ? "E:\\Program\\Git\\mingw64\\bin\\openssl.exe" : "/usr/bin/openssl",
                "NugetArchiveHashTests.test_signed_hash_reconstructs_original_bytes_for_physical_and_central_orders",
                "NugetArchiveHashTests.test_malformed_or_unsupported_signed_archives_fail_closed",
                "NugetArchiveHashTests.test_unsigned_zip64_remains_a_raw_archive_hash",
                "NugetContentHashTransportTests.test_signed_package_content_hash_transport_and_cache",
                "NugetContentHashTransportTests.test_same_url_cannot_change_locked_content_hash",
                "NugetContentHashTransportTests.test_another_transport_without_a_lock_inherits_bound_content_hash",
                "NugetContentHashTransportTests.test_binding_between_precheck_and_transfer_is_enforced_without_a_race_sleep",
                "NugetContentHashTransportTests.test_wrong_lock_and_custom_only_failure_do_not_fall_back",
                "shared.LockedTransportTests.test_nuget_tls_preserves_config_and_switches_only_corrupt_locked_package");
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            for (String key : List.of("TEMP", "TMP", "TMPDIR")) builder.environment().put(key, directory.toString());
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            process = builder.start();
            System.out.println("NUGET_HASH_PYTHON pid=" + process.pid() + " birth="
                + process.info().startInstant().map(Object::toString).orElse("UNKNOWN") + " cwd=" + directory);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Local NuGet content hash proof timed out");
            String result = Files.readString(output);
            System.out.println(result);
            System.out.println("NUGET_HASH_PYTHON terminal=" + process.exitValue() + " alive=" + process.isAlive());
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("Ran 9 tests"), result);
            assertTrue(result.contains("OK"), result);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    void catalogNugetSignatureRoutesPreserveBytesAndSelectedSourceBoundaries() throws Exception {
        Path directory = Files.createTempDirectory("n-");
        Process process = null;
        try {
            Path runtime = directory.resolve("runtime.py"), output = directory.resolve("output.log");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            System.out.println("NUGET_SIGNATURE_JVM pid=" + ProcessHandle.current().pid() + " birth="
                + ProcessHandle.current().info().startInstant().map(Object::toString).orElse("UNKNOWN")
                + " cwd=" + Path.of("").toAbsolutePath() + " java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " jna.tmpdir=" + System.getProperty("jna.tmpdir")
                + " jdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir")
                + " runtimeSha256=" + DeploymentMirrorRuntime.runtimeSha256());
            for (String name : List.of("locked_transport_test.py", "nuget_signature_route_test.py")) {
                try (var input = getClass().getResourceAsStream(name)) {
                    if (input == null) throw new IOException("NuGet transport fixture absent: " + name);
                    Files.copy(input, directory.resolve(name));
                }
            }
            for (String source : List.of("official-nuget", "huawei", "custom")) {
                var row = source.equals("custom")
                    ? new MirrorSettings.Row("", "https://custom.invalid/nuget/", false, false, "")
                    : new MirrorSettings.Row(source, "", false, false, "");
                var settings = new MirrorSettings(1, Map.of(DownloadPoint.NUGET, List.of(row)), Map.of());
                var selected = MirrorSelector.resolve(MirrorCatalog.load(), settings, "synthetic-server", "US").withTask("synthetic-task");
                var frozen = ResolvedMirrorPolicy.fromJson(selected.toJson(), "synthetic-task", "synthetic-server");
                assertEquals(selected, frozen);
                assertEquals(1, frozen.points().get(DownloadPoint.NUGET).size());
                Files.writeString(directory.resolve(source + ".json"), frozen.toJson());
            }
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            var command = List.of(windows ? "E:\\Program\\Python\\Python3.12\\python.exe" : "python3",
                directory.resolve("nuget_signature_route_test.py").toString(), runtime.toString(),
                windows ? "E:\\Program\\Git\\mingw64\\bin\\openssl.exe" : "/usr/bin/openssl",
                "NugetSignatureRouteTests.test_catalog_signature_routes_preserve_bytes_and_boundaries");
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            for (String key : List.of("TEMP", "TMP", "TMPDIR")) builder.environment().put(key, directory.toString());
            process = builder.start();
            System.out.println("NUGET_SIGNATURE_PYTHON pid=" + process.pid() + " birth="
                + process.info().startInstant().map(Object::toString).orElse("UNKNOWN") + " cwd=" + directory);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Local NuGet transport proof timed out");
            String result = Files.readString(output);
            System.out.println(result);
            System.out.println("NUGET_SIGNATURE_PYTHON terminal=" + process.exitValue() + " alive=" + process.isAlive());
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("Ran 1 test"), result);
            assertTrue(result.contains("OK"), result);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    void fixedLockTransportAndScopedCredentialsWorkWithTwoLocalHttpsRepositories() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve(".ai-workspace"))) root = root.getParent();
        if (root == null) throw new IOException("Project workspace unavailable");
        Path directory = Files.createDirectories(root.resolve(".ai-workspace/tmp/deployment-mirrors-20261104/locked-transport/j-" + UUID.randomUUID().toString().substring(0, 8)));
        Process process = null;
        try {
            Path runtime = directory.resolve("runtime.py"), test = directory.resolve("test.py"), output = directory.resolve("output.log");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            try (var input = getClass().getResourceAsStream("locked_transport_test.py")) {
                if (input == null) throw new IOException("Locked transport test resource absent");
                Files.copy(input, test);
            }
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            var command = windows ? List.of("py", "-3", test.toString(), runtime.toString(), "E:\\Program\\Git\\mingw64\\bin\\openssl.exe")
                : List.of("python3", test.toString(), runtime.toString(), "/usr/bin/openssl");
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            for (String key : List.of("TEMP", "TMP", "TMPDIR")) builder.environment().put(key, directory.toString());
            process = builder.start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Local HTTPS transport proof timed out");
            String result = Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("Ran 7 tests"), result);
            assertTrue(result.contains("OK"), result);
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                process.waitFor();
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
